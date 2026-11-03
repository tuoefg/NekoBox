package io.nekohasekai.sagernet.utils

import android.content.Intent
import android.util.Log
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.BlankActivity
import java.util.concurrent.atomic.AtomicLong

/**
 * Logs an uncaught exception and restarts into [BlankActivity], which shares a redacted log export.
 *
 * The rebirth is rate-limited: a crash raised by the startup path itself (a class initializer, a
 * static regex that this Android build rejects) would otherwise restart into the same crash
 * forever — the user sees a white screen that comes back every second and never gets the log.
 * [_restarts] consecutive crashes with no clean run in between ([noteStarted]) stop the loop, so
 * the app stays down and the record on disk is what gets reported.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val MAX_RESTARTS = 2
    private const val RESTART_WINDOW_MS = 60_000L

    private val lastRestart = AtomicLong(0L)
    private val restarts = AtomicLong(0L)

    /** Called once the UI is really up: the next crash gets the full restart budget again. */
    fun noteStarted() {
        restarts.set(0L)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // note: libc / go panic is in android log

        try {
            Log.e(thread.toString(), throwable.stackTraceToString())
        } catch (e: Exception) {
        }

        try {
            // core.log rotates: keep the crash in its own file too, or it can scroll
            // away before anyone exports it. Throwable: the crash may itself be an OOM.
            CrashStore.record(thread, throwable)
        } catch (e: Throwable) {
        }

        try {
            Logs.e(thread.toString())
            Logs.e(throwable.stackTraceToString())
        } catch (e: Exception) {
        }

        val now = System.currentTimeMillis()
        val previous = lastRestart.get()
        // A crash long after the last one is a fresh incident, not a loop.
        if (now - previous > RESTART_WINDOW_MS) restarts.set(0L)
        lastRestart.set(now)
        if (restarts.incrementAndGet() > MAX_RESTARTS) {
            // No rebirth: exiting here keeps the crash on disk and ends the white-screen loop.
            try {
                Logs.e("Crash loop: ${throwable.javaClass.name} crashed more than $MAX_RESTARTS times in " +
                        "${RESTART_WINDOW_MS / 1000}s, not restarting. The record is in files/crashes/.")
            } catch (e: Throwable) {
            }
            Runtime.getRuntime().halt(1)
            return
        }

        ProcessPhoenix.triggerRebirth(app, Intent(app, BlankActivity::class.java).apply {
            putExtra(BlankActivity.EXTRA_SEND_LOG, true)
        })
    }
}
