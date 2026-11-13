package com.ivarna.mkm.shell

/**
 * Swap / zram / VM shell scripts.
 *
 * Every multi-line script runs inside a subshell `( ... )`: libsu keeps one long-lived
 * root shell, so a bare `exit` or `set -e` would kill it.
 * Swap creation needs real root (uid 0); Shizuku's ADB uid cannot call swapon.
 */
object ShellScripts {

    const val DEFAULT_SWAP_PATH = "/data/local/tmp/swapfile"
    const val MIN_SWAP_MB = 64
    private const val FREE_MARGIN_MB = 256

    private val SWAP_PATH = Regex("^/data/[A-Za-z0-9._/-]+$")

    /** Only plain files under /data, no `..`, no shell metacharacters. */
    fun isValidSwapPath(path: String): Boolean =
        SWAP_PATH.matches(path) && ".." !in path && !path.endsWith("/")

    /** Validated size: null for garbage/0/negative or when /data cannot fit [MIN_SWAP_MB]; else clamped to 64MB..50% of free space. */
    fun clampSwapSizeMb(requested: Int?, freeMb: Long): Int? {
        if (requested == null || requested <= 0) return null
        val max = (freeMb / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (max < MIN_SWAP_MB) return null
        return requested.coerceIn(MIN_SWAP_MB, max)
    }

    private fun requirePath(path: String) =
        require(isValidSwapPath(path)) { "Invalid swap path: $path" }

    /** Common helpers. P is single-quoted; the path is regex-validated so it contains no quote. */
    private fun helpers(path: String): String = $$"""
        P='$$path'
        fail() { echo "$1" >&2; exit 1; }
        in_swaps() { awk -v p="$1" '$1==p{f=1} END{exit !f}' /proc/swaps 2>/dev/null; }
        loop_of() { losetup -j "$P" 2>/dev/null | cut -d: -f1 | head -n 1; }
        has_sig() { for o in 4086 16374 65526; do [ "$(dd if="$P" bs=1 skip=$o count=10 2>/dev/null)" = SWAPSPACE2 ] && return 0; done; return 1; }
        is_ours() { in_swaps "$P" && return 0; L0=$(loop_of); [ -n "$L0" ] && in_swaps "$L0" && return 0; has_sig; }
        release() { L0=$(loop_of); if [ -n "$L0" ]; then swapoff "$L0" 2>/dev/null; losetup -d "$L0" 2>/dev/null; fi; swapoff "$P" 2>/dev/null; true; }
        [ -L "$P" ] && fail "Refusing symlink: $P"
        if [ -e "$P" ]; then [ -f "$P" ] || fail "Refusing non-regular file: $P"; fi
    """.trimIndent()

    /** Create (or resize) and enable a swap file. Refuses to overwrite files that are not swap. */
    fun createSwap(path: String, sizeMb: Int): String {
        requirePath(path)
        require(sizeMb in MIN_SWAP_MB..1_048_576) { "Invalid swap size: $sizeMb" }
        return $$"""
            (
            $${helpers(path)}
            SIZE=$$sizeMb
            LOOP=
            CREATED=0
            OLD_KB=0
            cleanup() {
                [ "$CREATED" = 1 ] || return 0
                if [ -n "$LOOP" ]; then swapoff "$LOOP" 2>/dev/null; losetup -d "$LOOP" 2>/dev/null; fi
                swapoff "$P" 2>/dev/null
                rm -f "$P"
            }
            die() { cleanup; fail "$1"; }
            trap 'die "Interrupted"' HUP INT TERM

            if [ -e "$P" ]; then
                is_ours || fail "Refusing to overwrite non-swap file: $P"
                OLD_KB=$(( $(stat -c %s "$P" 2>/dev/null || echo 0) / 1024 ))
            fi
            AVAIL_KB=$(df -k /data 2>/dev/null | awk 'NR==2{print $4}')
            case "$AVAIL_KB" in ''|*[!0-9]*) fail "Cannot read free space on /data";; esac
            [ $((AVAIL_KB + OLD_KB)) -ge $(( (SIZE + $$FREE_MARGIN_MB) * 1024 )) ] || fail "Not enough free space on /data for ${SIZE}MB swap"

            # Release the old swap (direct or loop-backed) before replacing it
            release
            CREATED=1
            newfile() { rm -f "$P" && touch "$P" && chmod 600 "$P" || die "Cannot create $P"; chattr +P "$P" 2>/dev/null; true; }
            newfile

            # Fast path: fallocate. Any failure (incl. swapon rejecting holes) falls through to dd.
            if command -v fallocate >/dev/null 2>&1 && fallocate -l ${SIZE}M "$P" 2>/dev/null \
                && mkswap "$P" >/dev/null 2>&1 && swapon "$P" 2>/dev/null; then
                echo "fallocate swap ok"
            else
                swapoff "$P" 2>/dev/null
                newfile
                dd if=/dev/zero of="$P" bs=1M count=$$sizeMb 2>/dev/null || die "dd failed (disk full?)"
                chmod 600 "$P"
                mkswap "$P" >/dev/null || die "mkswap failed"
                if ! swapon "$P" 2>/dev/null; then
                    echo "Direct swapon failed. Attempting loop device fallback..."
                    LOOP=$(losetup -f)
                    [ -n "$LOOP" ] || die "No free loop device found"
                    losetup "$LOOP" "$P" || die "losetup failed"
                    mkswap "$LOOP" >/dev/null && swapon "$LOOP" || die "swapon failed"
                fi
            fi
            echo "swap ok"
            )
        """.trimIndent()
    }

    /** Re-enable an existing swap file (used at boot and for inactive managed files). */
    fun enableSwap(path: String, priority: Int = -1): String {
        requirePath(path)
        val prio = if (priority in 0..32767) "-p $priority" else ""
        return $$"""
            (
            $${helpers(path)}
            [ -f "$P" ] || fail "Swap file missing: $P"
            has_sig || fail "No swap signature: $P"
            in_swaps "$P" && exit 0
            L=$(loop_of)
            [ -n "$L" ] && in_swaps "$L" && exit 0
            swapon $$prio "$P" 2>/dev/null && exit 0
            [ -n "$L" ] || L=$(losetup -f)
            [ -n "$L" ] || fail "No free loop device found"
            losetup "$L" "$P" 2>/dev/null
            swapon $$prio "$L" || { losetup -d "$L" 2>/dev/null; fail "swapon failed for $P"; }
            )
        """.trimIndent()
    }

    /** Disable a swap file; handles loop-backed swap. File stays on disk. */
    fun disableSwap(path: String): String {
        requirePath(path)
        return $$"""
            (
            $${helpers(path)}
            L=$(loop_of)
            if [ -n "$L" ]; then swapoff "$L" && losetup -d "$L"; else swapoff "$P"; fi
            )
        """.trimIndent()
    }

    /** Disable and delete a swap file. Refuses /dev/ * and anything that is not a swap file. */
    fun removeSwap(path: String): String {
        requirePath(path)
        return $$"""
            (
            $${helpers(path)}
            [ -e "$P" ] || exit 0
            is_ours || fail "Refusing to delete non-swap file: $P"
            release
            rm -f "$P"
            )
        """.trimIndent()
    }

    /** Prints `<path> <bytes>` for each existing candidate file (one shell call). */
    fun statSwapFiles(paths: Collection<String>): String {
        val valid = paths.filter(::isValidSwapPath).distinct()
        if (valid.isEmpty()) return "true"
        return valid.joinToString("; ") { "[ -f '$it' ] && [ ! -L '$it' ] && echo \"$it \$(stat -c %s '$it')\"" } + "; true"
    }

    // ---- zram / VM -----------------------------------------------------------------

    /** Writable vm tunables and their accepted ranges. */
    val VM_TUNABLES: Map<String, IntRange> = linkedMapOf(
        "swappiness" to 0..200,
        "vfs_cache_pressure" to 1..1000,
        "watermark_scale_factor" to 1..3000,
        "min_free_kbytes" to 1024..262_144
    )

    fun isValidVmValue(name: String, value: Int?): Boolean =
        value != null && VM_TUNABLES[name]?.contains(value) == true

    fun setVmTunable(name: String, value: Int): String {
        require(isValidVmValue(name, value)) { "Invalid $name=$value" }
        return "printf '%s' $value > /proc/sys/vm/$name"
    }

    private val ALGO = Regex("^[a-z0-9-]{1,32}$")

    /** swapoff, reset, set algorithm + disksize, mkswap, swapon. Keeps the old priority. */
    fun zramReconfigure(algorithm: String, sizeBytes: Long): String {
        require(ALGO.matches(algorithm)) { "Invalid algorithm" }
        require(sizeBytes >= 64L * 1024 * 1024) { "Invalid zram size" }
        return $$"""
            (
            fail() { echo "$1" >&2; exit 1; }
            S=/sys/block/zram0
            D=/dev/block/zram0; [ -b "$D" ] || D=/dev/zram0
            [ -d "$S" ] || fail "zram0 not present"
            PRIO=$(awk '$1 ~ /zram0$/ {print $5}' /proc/swaps)
            case "$PRIO" in ''|-*|*[!0-9]*) PRIO=;; esac
            swapoff "$D" 2>/dev/null
            echo 1 > "$S/reset" || fail "zram reset failed (still in use?)"
            echo '$$algorithm' > "$S/comp_algorithm" || fail "Algorithm $$algorithm rejected"
            echo $$sizeBytes > "$S/disksize" || fail "disksize rejected"
            mkswap "$D" >/dev/null || fail "mkswap zram failed"
            if [ -n "$PRIO" ]; then swapon -p "$PRIO" "$D"; else swapon "$D"; fi || fail "swapon zram failed"
            )
        """.trimIndent()
    }

    fun fstrim(): String =
        "(if command -v fstrim >/dev/null 2>&1; then fstrim -v /data; else sm fstrim && echo 'fstrim requested via StorageManager'; fi)"
}
