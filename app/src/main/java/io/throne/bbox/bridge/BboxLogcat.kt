package io.throne.bbox.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Calendar
import java.util.Locale

/**
 * A tail of the app's system logcat, shaped like [BboxLogs] so the log screen treats both the same.
 *
 * The old UI's own log view tails core.log through a RandomAccessFile poll/commit reader; logcat is
 * a pipe rather than a file, so the tail-reader becomes a `logcat -T` process (the last [INITIAL_LINES]
 * lines, then everything new) read line by line on the IO thread. Nothing needs committing: a line is
 * shown once its batch is flushed, every [FLUSH_MS]. The reader survives the process dying — it
 * reopens after a pause — and cancellation destroys the process, which unblocks the read.
 *
 * The queue is bounded like the core log's; when the oldest record falls off the batch reports the
 * [Batch.firstId] to drop, and a clear (or a reopen) makes the next batch [Batch.reset].
 */
object BboxLogcat {

    data class Entry(val id: Long, val level: String, val message: String, val receivedAt: Long)

    data class Batch(
        val entries: List<Entry> = emptyList(),
        val reset: Boolean = false,
        val firstId: Long = 0L,
    )

    private const val CAPACITY = 1500

    /** Where the stream opens, so the screen does not have to watch an empty page until traffic flows. */
    private const val INITIAL_LINES = 500

    private const val RESTART_MS = 2000L
    private const val FLUSH_MS = 500L

    private val levels = mapOf(
        'V' to "trace", 'D' to "debug", 'I' to "info",
        'W' to "warn", 'E' to "error", 'F' to "fatal",
    )
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Everything still inside the window, for the UI's retention; [pending] is what it has not seen yet. */
    private val buffer = ArrayDeque<Entry>()
    private val pending = ArrayList<Entry>()
    private var nextId = 1L
    private var pendingReset = true

    private var job: Job? = null

    @Volatile
    private var process: Process? = null

    fun start(scope: CoroutineScope, emit: (Batch) -> Unit) {
        stop()
        synchronized(this) {
            buffer.clear()
            pending.clear()
            pendingReset = true
        }
        job = scope.launch {
            val flusher = launch {
                while (isActive) {
                    delay(FLUSH_MS)
                    drain()?.let(emit)
                }
            }
            while (isActive) {
                val reader = withContext(Dispatchers.IO) { open() }
                if (reader == null) {
                    delay(RESTART_MS)
                    continue
                }
                try {
                    reader.useLines { lines ->
                        for (line in lines) {
                            if (!isActive) break
                            append(line)
                        }
                    }
                } catch (e: Exception) {
                    // Cancellation closes the stream through the destroy below; a dead process
                    // lands here too and the loop simply reopens it.
                    if (!isActive) break
                } finally {
                    runCatching { process?.destroy() }
                    process = null
                }
                if (isActive) delay(RESTART_MS)
            }
            flusher.cancel()
            drain()?.let(emit)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { process?.destroy() }
        process = null
    }

    /** Throne has no logcat counterpart of the core log's file truncate: `logcat -c` is the system's own clear. */
    fun clear() {
        runCatching { ProcessBuilder("logcat", "-c").start().waitFor() }
        synchronized(this) {
            buffer.clear()
            pending.clear()
            pendingReset = true
        }
    }

    /** The streaming reader; null when the device denies logcat (rare, and only an app's own is readable anyway). */
    private fun open(): BufferedReader? = try {
        ProcessBuilder("logcat", "-v", "threadtime", "-T", INITIAL_LINES.toString())
            .start()
            .also { process = it }
            .inputStream
            .let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)) }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun append(line: String) {
        val entry = parse(line) ?: return
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(entry)
        pending.add(entry)
    }

    @Synchronized
    private fun drain(): Batch? {
        val entries = ArrayList<Entry>(pending)
        pending.clear()
        val reset = pendingReset
        pendingReset = false
        if (entries.isEmpty() && !reset) return null
        return Batch(entries = entries, reset = reset, firstId = buffer.firstOrNull()?.id ?: 0L)
    }

    /**
     * threadtime is `MM-DD HH:MM:SS.mmm PID TID P TAG: message`; the priority letter is the level the
     * screen's filter already knows. Anything else passes through as ordinary output.
     */
    private fun parse(line: String): Entry? {
        if (line.isBlank()) return null
        val parts = line.trim().split(Regex("""\s+"""), limit = 6)
        if (parts.size < 6) {
            return Entry(id = nextId++, level = "info", message = line, receivedAt = System.currentTimeMillis())
        }
        val receivedAt = parseStamp(parts[0], parts[1]) ?: System.currentTimeMillis()
        return Entry(
            id = nextId++,
            level = levels[parts[4].firstOrNull()?.uppercaseChar()] ?: "info",
            message = parts[5],
            receivedAt = receivedAt,
        )
    }

    private fun parseStamp(date: String, time: String): Long? = runCatching {
        synchronized(stampFormat) {
            stampFormat.parse("${Calendar.getInstance().get(Calendar.YEAR)}-$date $time")?.time
        }
    }.getOrNull()

    fun wire(batch: Batch): Map<String, Any?> = mapOf(
        "entries" to batch.entries.map { entry ->
            mapOf(
                "id" to entry.id,
                "level" to entry.level,
                "message" to entry.message,
                "receivedAt" to entry.receivedAt,
            )
        },
        "reset" to batch.reset,
        "firstId" to batch.firstId,
    )
}
