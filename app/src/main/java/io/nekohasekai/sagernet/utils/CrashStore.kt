package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.ktx.app
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Durable crash records beside [CrashHandler]'s log line: core.log truncates and rotates,
 * a crash written there can scroll away before anyone looks. One file per crash under
 * `filesDir/crashes/`, kept for [RETENTION_DAYS] days. Pure file logic — the directory
 * and the clock are injectable so the pruning rules run as plain JVM tests.
 */
object CrashStore {

    const val RETENTION_DAYS = 7

    @Volatile
    internal var dir: File? = null

    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val serial = AtomicInteger()

    private fun storage(): File = dir ?: File(app.filesDir, "crashes").apply { mkdirs() }

    // SimpleDateFormat is not thread-safe and record() runs on whichever thread crashed;
    // a fresh instance per call is cheaper than a lock on the crash path.
    private fun stamp(at: Long) = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date(at))

    /** Writes one crash record; returns the file, or null when the disk said no. */
    fun record(thread: Thread, throwable: Throwable): File? {
        val at = clock()
        val name = "crash-${stamp(at)}-%04d.log".format(Locale.US, serial.incrementAndGet())
        val file = File(storage(), name)
        val written = runCatching {
            file.writeText(
                buildString {
                    appendLine("time: ${stamp(at)}")
                    appendLine("thread: ${thread.name}")
                    appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine()
                    appendLine(throwable.stackTraceToString())
                },
            )
        }.isSuccess
        if (written) prune()
        return if (written) file else null
    }

    /** The kept records, newest first. */
    fun list(): List<File> = storage()
        .listFiles { file -> file.isFile && file.name.startsWith("crash-") }
        ?.sortedByDescending { it.name }
        .orEmpty()

    fun read(name: String): String = File(storage(), name).readText()

    /** Deletes records older than the retention window; the age comes from the file name. */
    fun prune() {
        val cutoff = clock() - RETENTION_DAYS * 24 * 60 * 60 * 1000L
        val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
        list().forEach { file ->
            val stamp = file.name.removePrefix("crash-").removeSuffix(".log").take(19)
            val at = runCatching { format.parse(stamp)?.time }.getOrNull()
            // A record whose name does not parse has no honest age: drop it rather than
            // keep an unprunable file forever.
            if (at == null || at < cutoff) runCatching { file.delete() }
        }
    }
}
