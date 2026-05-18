package com.dyrpa.agent.shizuku

import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * All V9.1 actions reduce to shell commands; this wrapper runs them via Shizuku
 * (which executes in the shell UID, identical privilege to `adb shell`).
 *
 * Note: Shizuku 13.x marked Shizuku.newProcess() as @hide/private. The blessed
 * path is bindUserService() + AIDL, but for our V9.1 direct shell-command model
 * we keep newProcess() via reflection — localized to this one file.
 */
object ShellExecutor {
    private const val TAG = "ShellExecutor"

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    private fun newProcess(cmd: Array<String>): Process =
        newProcessMethod.invoke(null, cmd, null, null) as Process

    // Exposed so the workflow's stop path (AgentService.onControl / onTask preempt)
    // can SIGTERM+SIGKILL whatever shell is in-flight without waiting for its
    // 8-15s timeout. Single-slot: workflows are serial (taskLock in AgentService),
    // so at most one ShellExecutor.run() is active per device at any time.
    private val currentProc = AtomicReference<Process?>(null)

    /** External "kill the shell that's running RIGHT NOW" hook. Safe to call when
     *  nothing's running (no-op). Spawns the kill -9 in a background thread so the
     *  caller (typically MQTT callback) isn't blocked on Shizuku binder IPC. */
    fun killCurrent() {
        val proc = currentProc.getAndSet(null) ?: return
        try { proc.destroy() } catch (_: Throwable) {}
        Thread {
            try {
                Thread.sleep(800)
                if (proc.isAlive) forceKill(proc, "killCurrent")
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; name = "shell-killer" }.start()
    }

    fun run(cmd: String, timeoutMs: Long = 8_000L): Result {
        val proc: Process = try {
            newProcess(arrayOf("sh", "-c", cmd))
        } catch (e: Exception) {
            Log.e(TAG, "shell start failed: $cmd", e)
            return Result(-1, "", e.message ?: "exception")
        }
        // Publish to the single-slot reference so AgentService.onControl("stop")
        // or onTask preempt can SIGTERM+SIGKILL us without waiting for timeout.
        currentProc.set(proc)
        // Escalation watchdog: SIGTERM first, then SIGKILL via a fresh Shizuku
        // shell if the child still won't die. `am broadcast` / `uiautomator dump`
        // can sit in kernel D-state and ignore SIGTERM forever; SIGKILL via
        // `kill -9` from outside the hung process tree always works.
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                try { proc.destroy() } catch (_: Throwable) {}
                Thread.sleep(1_500)
                if (proc.isAlive) forceKill(proc, cmd)
            } catch (_: InterruptedException) {}
        }
        watchdog.start()
        // Drain stdout/stderr concurrently. Linux pipe buffer is ~64KB; if the child
        // writes more than that without us reading, write() blocks and waitFor() hangs
        // forever. Was killing uiautomator dump on dense pages (XML >64KB → SIGTERM).
        var stdout = ""
        var stderr = ""
        val tOut = Thread { try { stdout = proc.inputStream.bufferedReader().readText() } catch (_: Throwable) {} }
        val tErr = Thread { try { stderr = proc.errorStream.bufferedReader().readText() } catch (_: Throwable) {} }
        tOut.start(); tErr.start()
        // Use the no-arg blocking waitFor() — Shizuku's Process subclass overrides
        // that one cleanly. The timed `waitFor(long, TimeUnit)` inherits from the
        // base class which polls exitValue(), and Shizuku's exitValue() over binder
        // returns inconsistent results during the brief window after child exit,
        // making the base-impl falsely return true then have exitValue() throw
        // `IllegalThreadStateException("process hasn't exited")` again. We avoid
        // that path entirely with our own waiter-thread + join() timeout.
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val exitCaptured = AtomicBoolean(false)
        val waiter = Thread {
            try {
                val rc = proc.waitFor()
                exitCode.set(rc)
                exitCaptured.set(true)
            } catch (_: InterruptedException) {
                // Workflow thread bailing out — don't keep this thread spinning.
            } catch (e: Exception) {
                Log.w(TAG, "waiter exception: ${e.message}")
            }
        }.apply { isDaemon = true; name = "shell-waiter" }
        waiter.start()
        // Hard ceiling: workflow thread bails after timeoutMs+3s even if the
        // child is wedged in D-state. timeoutMs is the soft watchdog start;
        // we add 3s for destroy() + kill -9 to take effect.
        try {
            waiter.join(timeoutMs + 3_000)
        } catch (e: InterruptedException) {
            // Stop arrived while we were waiting on the shell. Kill the child
            // synchronously (SIGTERM here, SIGKILL from forceKill below) so we
            // don't leak a long-running shell after the workflow unwinds.
            Log.w(TAG, "shell run interrupted by caller: $cmd")
            try { proc.destroy() } catch (_: Throwable) {}
            if (proc.isAlive) forceKill(proc, cmd)
            watchdog.interrupt()
            waiter.interrupt()
            currentProc.compareAndSet(proc, null)
            // Preserve the interrupt flag so caller's outer InterruptedException
            // handling (Interpreter.runSection) still fires correctly.
            Thread.currentThread().interrupt()
            throw e
        }
        watchdog.interrupt()
        // Don't block forever on the drain threads either — they're reading
        // from pipes of a potentially still-alive child.
        tOut.join(500)
        tErr.join(500)
        currentProc.compareAndSet(proc, null)
        return if (exitCaptured.get()) {
            Result(exitCode.get(), stdout, stderr)
        } else {
            // Waiter never captured a clean exit code. Treat as watchdog timeout.
            waiter.interrupt()
            Log.w(TAG, "shell timeout after ${timeoutMs}ms (waiter never captured exit): $cmd")
            Result(-1, stdout, "watchdog timeout after ${timeoutMs}ms; stderr-fragment='${stderr.take(200)}'")
        }
    }

    /** SIGKILL the child via a fresh Shizuku-shell. Best-effort — if even this fails,
     *  the workflow thread will still bail via the proc.waitFor timeout in `run`. */
    private fun forceKill(proc: Process, originalCmd: String) {
        // proc.pid() is Java 9 / Android API 26+, but Shizuku's Process subclass
        // (ShizukuRemoteProcess) doesn't override it — the base impl throws
        // UnsupportedOperationException. Use reflection so we degrade gracefully.
        val pid: Long = try {
            val m = Process::class.java.getMethod("pid")
            (m.invoke(proc) as? Long) ?: return
        } catch (_: Throwable) {
            Log.w(TAG, "forceKill: cannot get PID for hung shell: $originalCmd")
            return
        }
        Log.w(TAG, "forceKill pid=$pid for hung shell: $originalCmd")
        try {
            val killProc = newProcess(arrayOf("sh", "-c", "kill -9 $pid"))
            killProc.waitFor(2_000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "kill -9 $pid failed: ${e.message}")
        }
    }

    /** Like `run` but returns stdout as raw bytes — needed for binary files (screencap PNG). */
    fun runBinary(cmd: String, timeoutMs: Long = 8_000L): ByteArray? = try {
        val proc = newProcess(arrayOf("sh", "-c", cmd))
        val watchdog = Thread {
            try { Thread.sleep(timeoutMs); try { proc.destroy() } catch (_: Throwable) {} }
            catch (_: InterruptedException) {}
        }
        watchdog.start()
        val bytes = proc.inputStream.readBytes()
        proc.waitFor()
        watchdog.interrupt()
        bytes.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.e(TAG, "runBinary failed: $cmd", e)
        null
    }

    fun inputTap(x: Int, y: Int) = run("input tap $x $y")
    fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) =
        run("input swipe $x1 $y1 $x2 $y2 $ms")
    fun inputText(text: String): Result {
        val escaped = text.replace("'", "'\\''")
        return run("am broadcast -a ADB_INPUT_TEXT --es msg '$escaped'")
    }
    fun keyEvent(key: String) = run("input keyevent $key")
    // Uses /data/local/tmp/ (always shell-writable) instead of /sdcard/ (scoped storage).
    // Keep stderr visible — silently swallowing it hid the root cause of UI-dump fails.
    // Timeout = 15s: dense pages (抖音 video detail with comments + sidebar) routinely
    // need 6-10s for full AccessibilityNodeInfo traversal + XML serialize. 6s was killing
    // the dump with SIGTERM (exit 143) on complex pages.
    fun uiDump() = run(
        "uiautomator dump /data/local/tmp/dyrpa_ui.xml >/dev/null && cat /data/local/tmp/dyrpa_ui.xml",
        timeoutMs = 15_000L,
    )
    fun screencap(savePath: String = "/sdcard/dyrpa_shot.png") = run("screencap -p $savePath")
    fun amStart(packageName: String) =
        run("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    fun amForceStop(packageName: String) = run("am force-stop $packageName")
}
