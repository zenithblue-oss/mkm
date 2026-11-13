# Changelog - MKM v1.10

## Version 1.10 (November 2026)

### Features Added
- CPU: throttling indicator, governor tunables editor, CPU core online/offline control.
- Thermal: restore button for the original thermal configuration.
- RAM/Storage: zram and VM tunables panel, UFS health (wear/EOL), I/O scheduler, read-ahead, fstrim and a swap file manager.
- Battery: health card (cycles, health trend), charge limit, top battery drainers.
- FPS: frame-time stats (1% / 0.1% lows, jank) and session CSV/JSON export.
- Game Boost: per-app auto boost and a Quick Settings tile.
- Overlay: Quick Settings tile; status bar icon shows up to 2 metrics, including network speed.
- Settings: tuning profile export/import.

### Fixes & Improvements
- Charging power sign is correct; the battery current sign convention is learned per device (#15).
- Overlay FPS fixed: pending-fence frames, Android 12+ BLAST layers, idle shows 0, FPS source badge (#16).
- Overlay update interval honored and overlay paused while the screen is off (#17).
- Overlay battery temperature fixed with unit handling and a BatteryManager fallback (#19).
- Theme and language are saved (#20).
- Shizuku permission prompt shown once, denial remembered, backend errors shown (#21).
- Swap creation explains that it needs root; safer swap create/resize/delete (#25).
- CPU min/max snap to supported frequencies with a clear error when a kernel/vendor limit wins (#23, #27).
- Much lower MKM self-drain: pages poll only while visible, fewer shell calls (#3).
- Safety: thermal disable no longer stops logd, thermal limit only on CPU zones, swap path validation, FPS layer-name shell injection fix, boot-apply deadlock with Game Boost fixed, boot safe mode with Cancel and auto-disable after a failed boot, no chmod on probes, root command timeouts, validated devfreq/UFS writes, RAM frequency no longer locked at boot.
- Battery stats reset on unplug/full works, battery tracker ANR fixed, deep-sleep and drain math fixed.
- Exynos/Tensor GPU support and UFS detection on Qualcomm.
- Build date now comes from the commit date so F-Droid reproducible builds match.
