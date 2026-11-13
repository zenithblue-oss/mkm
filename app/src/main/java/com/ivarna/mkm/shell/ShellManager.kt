package com.ivarna.mkm.shell

import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Manages shell command execution with explicit privilege-aware fallback.
 * Kernel tuning uses root first, then Shizuku, and never silently falls back
 * to an unprivileged write.
 */
object ShellManager {
    private const val COMMAND_TIMEOUT_MS = 10_000L
    private const val STREAM_JOIN_TIMEOUT_MS = 1_000L

    /** Last Shizuku transport failure, surfaced on the permission screen for bug reports. */
    @Volatile
    var lastShizukuError: String? = null
        private set


    /**
     * Access method enum for identifying the current execution mode
     */
    enum class AccessMethod {
        ROOT,       // Root via libsu
        LOCAL       // Non-root local shell
    }

    enum class PrivilegeRequirement {
        NORMAL,
        ELEVATED_SYSFS
    }

    enum class ExecutionBackend {
        ROOT,
        SHIZUKU,
        LOCAL
    }

    /**
     * Get the currently available access method
     * Priority: Root → Local
     */
    fun getAvailableMethod(): AccessMethod {
        return if (Shell.getShell().isRoot) AccessMethod.ROOT else AccessMethod.LOCAL
    }

    /**
     * Check if elevated access is available (Root or Shizuku)
     * Note: Currently detects Shizuku but commands run via root
     */
    fun hasElevatedAccess(): Boolean {
        // Check if Shizuku is available and permitted
        val hasShizuku = ShizukuManager.hasPermission()
        
        // Check if root is available
        val hasRoot = Shell.getShell().isRoot
        
        // Return true if either is available
        return hasShizuku || hasRoot
    }
    
    /**
     * Check if Shizuku is available and has permission
     */
    fun hasShizuku(): Boolean {
        return ShizukuManager.hasPermission()
    }
    
    /**
     * Check if root access is available via libsu
     */
    fun hasRoot(): Boolean {
        return Shell.getShell().isRoot
    }

    /** Shizuku server started as uid 0 (e.g. `adb root` + rooted debugging): behaves as root without su. */
    fun hasShizukuRoot(): Boolean = hasShizuku() && ShizukuManager.isRootMode()

    fun appString(id: Int): String = ShizukuManager.appContext?.getString(id) ?: ""

    /** Single source of truth for "can perform root-only operations" (libsu root OR Shizuku running as uid 0). */
    fun hasRootCapability(): Boolean = hasRoot() || hasShizukuRoot()

    /** Execute a command according to its privilege requirement. */
    fun exec(
        command: String,
        requirement: PrivilegeRequirement = PrivilegeRequirement.NORMAL,
        timeoutMs: Long = COMMAND_TIMEOUT_MS
    ): CommandResult {
        val order = PrivilegeExecutionPolicy.order(requirement, hasRoot(), hasShizuku())
        if (order.isEmpty()) return CommandResult(-1, "", "No elevated backend available")

        var lastResult: CommandResult? = null
        for (backend in order) {
            val result = when (backend) {
                ExecutionBackend.ROOT -> execRoot(command, timeoutMs)
                ExecutionBackend.SHIZUKU -> execShizuku(command, timeoutMs)
                ExecutionBackend.LOCAL -> execLocal(command, timeoutMs)
            }
            lastResult = result
            // Normal commands preserve the existing behavior: a real shell
            // failure is returned, while an unavailable backend may fall back.
            // A timed-out command may still be running, so it is never re-run.
            // Elevated operations use a root-first order and never include
            // LOCAL, so permission failures cannot become fake sysfs writes.
            if (result.isSuccess || result.exitCode != -1 || result.timedOut ||
                requirement == PrivilegeRequirement.ELEVATED_SYSFS) {
                return result
            }
        }
        return lastResult ?: CommandResult(-1, "", "No execution backend available")
    }

    /** Runs a trivial command on Shizuku only, to surface transport errors when permission is granted. */
    fun probeShizuku(): CommandResult = execShizuku("id -u", 3_000L)

    /** Root-first, never-local execution for kernel nodes (see [PrivilegeExecutionPolicy]). */
    fun execElevated(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): CommandResult =
        exec(command, PrivilegeRequirement.ELEVATED_SYSFS, timeoutMs)

    /**
     * Execute via root (libsu). libsu has no per-command timeout, so the job
     * is enqueued and awaited with a deadline.
     * ponytail: a hung command still occupies libsu's single root shell until it exits.
     */
    private fun execRoot(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): CommandResult {
        val result = try {
            Shell.cmd(command).enqueue().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            return CommandResult(-1, "", "Command timeout after ${timeoutMs / 1000} seconds", ExecutionBackend.ROOT, timedOut = true)
        } catch (e: Exception) {
            return CommandResult(-1, "", "Root execution failed: ${e.message}", ExecutionBackend.ROOT)
        }
        return CommandResult(
            result.code,
            result.out.joinToString("\n").trim(),
            result.err.joinToString("\n").trim(),
            ExecutionBackend.ROOT
        )
    }

    /**
     * Execute via Shizuku (ADB shell with uid=2000)
     */
    private fun execShizuku(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): CommandResult {
        return try {
            // Use reflection to access private Shizuku.newProcess()
            val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            
            val process = method.invoke(
                null,  // static method
                arrayOf("sh", "-c", command),
                null,  // environment
                null   // working directory
            ) as Process
            
            collectProcess(process, timeoutMs, ExecutionBackend.SHIZUKU).also {
                if (it.timedOut) lastShizukuError = it.stderr
            }
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            CommandResult(-1, "", "Shizuku execution failed: ${cause.javaClass.simpleName}: ${cause.message}", ExecutionBackend.SHIZUKU)
                .also { lastShizukuError = it.stderr }
        }
    }

    /**
     * Execute via local shell (non-root)
     */
    private fun execLocal(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): CommandResult {
        return try {
            collectProcess(Runtime.getRuntime().exec(arrayOf("sh", "-c", command)), timeoutMs, ExecutionBackend.LOCAL)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Unknown local error", ExecutionBackend.LOCAL)
        }
    }

    /** Drains stdout and stderr concurrently so the deadline holds even when a stream blocks. */
    private fun collectProcess(process: Process, timeoutMs: Long, backend: ExecutionBackend): CommandResult {
        // Close stdin immediately to prevent process from waiting for input
        runCatching { process.outputStream.close() }
        val output = StringBuffer()
        val error = StringBuffer()
        fun drain(stream: java.io.InputStream, sink: StringBuffer) = Thread {
            try {
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.lineSequence().forEach { line -> sink.append(line).append("\n") }
                }
            } catch (_: Exception) {
                // Stream closed, normal for process completion
            }
        }.apply { isDaemon = true; start() }
        val outThread = drain(process.inputStream, output)
        val errThread = drain(process.errorStream, error)

        val finished = waitForProcess(process, timeoutMs)
        if (!finished) process.destroy()
        outThread.join(STREAM_JOIN_TIMEOUT_MS)
        errThread.join(STREAM_JOIN_TIMEOUT_MS)
        if (!finished) {
            return CommandResult(-1, output.toString().trim(), "Command timeout after ${timeoutMs / 1000} seconds", backend, timedOut = true)
        }
        return CommandResult(process.exitValue(), output.toString().trim(), error.toString().trim(), backend)
    }

    private fun waitForProcess(process: Process, timeoutMillis: Long): Boolean {
        val waiter = Thread {
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        waiter.start()
        waiter.join(timeoutMillis)
        return !waiter.isAlive
    }

    /**
     * Command execution result
     */
    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val backend: ExecutionBackend? = null,
        val timedOut: Boolean = false
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * Executes a command and streams output line by line.
     * @param onOutput Callback for each line of stdout/stderr
     */
    fun execStreaming(command: String, onOutput: (String) -> Unit): CommandResult {
        // Try Shizuku first if available
        if (hasShizuku()) {
            val result = execShizukuStreaming(command, onOutput)
            // Fall back to root if Shizuku fails
            if (result.isSuccess || result.exitCode != -1) {
                return result
            }
            onOutput("Shizuku failed, falling back to root...")
        }
        
        // Fall back to root or local
        return when (getAvailableMethod()) {
            AccessMethod.ROOT -> execRootStreaming(command, onOutput)
            AccessMethod.LOCAL -> execLocalStreaming(command, onOutput)
        }
    }

    private fun execRootStreaming(command: String, onOutput: (String) -> Unit): CommandResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        
        val stdoutCallback = object : java.util.ArrayList<String>() {
            override fun add(element: String): Boolean {
                stdout.append(element).append("\n")
                onOutput(element)
                return super.add(element)
            }
        }
        
        val stderrCallback = object : java.util.ArrayList<String>() {
            override fun add(element: String): Boolean {
                stderr.append(element).append("\n")
                onOutput("ERR: $element")
                return super.add(element)
            }
        }

        val result = Shell.cmd(command)
            .to(stdoutCallback)
            .to(stderrCallback)
            .exec()
            
        return CommandResult(result.code, stdout.toString().trim(), stderr.toString().trim())
    }

    private fun execShizukuStreaming(command: String, onOutput: (String) -> Unit): CommandResult {
        return try {
            // Use reflection to access private Shizuku.newProcess()
            val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process
            
            // Close stdin immediately
            process.outputStream.close()
            
            val output = StringBuilder()
            val error = StringBuilder()
            
            // Read streams in parallel threads
            val outThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            output.append(line).append("\n")
                            onOutput(line)
                        }
                    }
                } catch (e: Exception) {
                    // Stream closed
                }
            }
            
            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            error.append(line).append("\n")
                            onOutput("ERR: $line")
                        }
                    }
                } catch (e: Exception) {
                    // Stream closed
                }
            }
            
            outThread.start()
            errThread.start()
            
            val finished = waitForProcess(process, COMMAND_TIMEOUT_MS)
            
            outThread.join(STREAM_JOIN_TIMEOUT_MS)
            errThread.join(STREAM_JOIN_TIMEOUT_MS)
            
            if (!finished) {
                process.destroy()
                val msg = "Command timeout after 10 seconds"
                onOutput("TIMEOUT: $msg")
                return CommandResult(-1, output.toString().trim(), msg)
            }
            
            val exitCode = process.exitValue()
            CommandResult(exitCode, output.toString().trim(), error.toString().trim())
        } catch (e: Exception) {
            val msg = "Shizuku streaming execution failed: ${e.message}"
            onOutput("EXCEPTION: $msg")
            CommandResult(-1, "", msg)
        }
    }

    private fun execLocalStreaming(command: String, onOutput: (String) -> Unit): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = StringBuilder()
            val error = StringBuilder()
            
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            
            var line: String?
            while (outReader.readLine().also { line = it } != null) {
                line?.let {
                    output.append(it).append("\n")
                    onOutput(it)
                }
            }
            
            while (errReader.readLine().also { line = it } != null) {
                line?.let {
                    error.append(it).append("\n")
                    onOutput("ERR: $it")
                }
            }
            
            process.waitFor()
            CommandResult(process.exitValue(), output.toString().trim(), error.toString().trim())
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown local error"
            onOutput("EXCEPTION: $msg")
            CommandResult(-1, "", msg)
        }
    }
}

/** Pure backend ordering so privilege fallback is explicit and testable. */
object PrivilegeExecutionPolicy {
    fun order(
        requirement: ShellManager.PrivilegeRequirement,
        rootAvailable: Boolean,
        shizukuAvailable: Boolean
    ): List<ShellManager.ExecutionBackend> = when {
        requirement == ShellManager.PrivilegeRequirement.ELEVATED_SYSFS && rootAvailable ->
            listOf(ShellManager.ExecutionBackend.ROOT)
        requirement == ShellManager.PrivilegeRequirement.ELEVATED_SYSFS && shizukuAvailable ->
            listOf(ShellManager.ExecutionBackend.SHIZUKU)
        requirement == ShellManager.PrivilegeRequirement.ELEVATED_SYSFS -> emptyList()
        shizukuAvailable -> listOf(ShellManager.ExecutionBackend.SHIZUKU) +
            if (rootAvailable) listOf(ShellManager.ExecutionBackend.ROOT) else listOf(ShellManager.ExecutionBackend.LOCAL)
        rootAvailable -> listOf(ShellManager.ExecutionBackend.ROOT)
        else -> listOf(ShellManager.ExecutionBackend.LOCAL)
    }
}
