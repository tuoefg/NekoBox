package io.throne.bbox.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.CoreLog
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale

/**
 * A tail of Throne's own core log, shaped the way the ported UI reads it.
 *
 * Every process writes its lines into one file (`CoreLog.file`), which is rotated in place at the size cap — Throne's
 * own log screen tails that file, so this is the same source the app uses, not a second copy. The difference is that
 * the UI wants records, not text: each line becomes an [Entry] with a level, so the log screen's level filter can work
 * on it. Levels have to be guessed from the text because both cores write them inline and differently.
 *
 * The queue is bounded. When the oldest record falls off, the batch reports a [Batch.firstId] so the UI drops what it
 * kept from before the window slid; when the file rotates, the batch says [Batch.reset] because nothing old lines up.
 */
object BboxLogs {

    data class Entry(val id: Long, val level: String, val message: String, val receivedAt: Long)

    data class Batch(
        val entries: List<Entry> = emptyList(),
        val reset: Boolean = false,
        val firstId: Long = 0L,
        val defaultLogLevel: String? = null,
    )

    private const val CAPACITY = 1500
    private const val POLL_MS = 1200L

    /** Where the first read starts, so opening the screen does not have to replay a 512 KB file. */
    private const val INITIAL_BYTES = 96 * 1024

    private const val STAMP_LENGTH = 19

    private val levels = listOf("TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "PANIC")
    private val squareLevel = Regex("""\[\s*(Info|Warning|Error|Debug|Trace|Fatal|Panic)\s*\]""", RegexOption.IGNORE_CASE)
    private val leadingLevel = Regex("""^\+?\d{0,4}\s*[-/]?\s*(Trace|Debug|Info|Warn|Warning|Error|Fatal|Panic)\b""", RegexOption.IGNORE_CASE)
    private val stampFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)

    private val buffer = ArrayDeque<Entry>()
    private var nextId = 1L

    /** Bytes of the file already turned into entries; always a line start. Negative until the first read. */
    private var offset = -1L

    /** Set by a rotate (or a clear) so the next batch tells the UI to start over. */
    private var pendingReset = true

    private var job: Job? = null

    fun start(scope: CoroutineScope, emit: (Batch) -> Unit) {
        stop()
        job = scope.launch {
            while (isActive) {
                val batch = withContext(Dispatchers.IO) { runCatching { collect() }.getOrNull() }
                if (batch != null) emit(batch)
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Throne's own clear, which truncates the file; the screen empties on the next poll. */
    fun clear() {
        CoreLog.clear()
        synchronized(this) {
            buffer.clear()
            offset = -1L
            pendingReset = true
        }
    }

    fun level(): String? = runCatching { CoreLog.logLevel }.getOrNull()

    @Synchronized
    private fun collect(): Batch? {
        val file = CoreLog.file
        val length = if (file.exists()) file.length() else 0L
        if (offset >= 0 && length < offset) {
            // Rotated in place: the file restarted, so every id the UI holds is past the end.
            buffer.clear()
            pendingReset = true
            offset = 0L
        }
        if (offset < 0L) {
            if (length == 0L) {
                if (!pendingReset) return null
                return Batch(reset = true, firstId = 0L, defaultLogLevel = level()).also { pendingReset = false }
            }
            offset = if (length <= INITIAL_BYTES) 0L else lineStartAfter(file, length - INITIAL_BYTES, length)
        }
        if (length <= offset) return null

        val chunk = RandomAccessFile(file, "r").use { raf ->
            val size = (length - offset).toInt()
            val data = ByteArray(size)
            raf.seek(offset)
            raf.readFully(data)
            data
        }
        // A half-written line waits for the next pass rather than reaching the UI torn.
        val end = chunk.indexOfLast { it == '\n'.code.toByte() }
        if (end < 0) return null
        val text = String(chunk, 0, end, Charsets.UTF_8)
        offset += end + 1

        val added = ArrayList<Entry>()
        for (line in text.split('\n')) {
            val entry = parse(line) ?: continue
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            added += entry
        }
        val reset = pendingReset
        pendingReset = false
        if (added.isEmpty() && !reset) return null
        return Batch(
            entries = added,
            reset = reset,
            firstId = buffer.firstOrNull()?.id ?: 0L,
            defaultLogLevel = level(),
        )
    }

    /** The first line start at or after [from]: the one before it is a fragment left by an earlier append. */
    private fun lineStartAfter(file: java.io.File, from: Long, length: Long): Long {
        RandomAccessFile(file, "r").use { raf ->
            val remaining = length - from
            val window = ByteArray(minOf(remaining, 8192).toInt())
            raf.seek(from)
            raf.readFully(window)
            val breakAt = window.indexOfFirst { it == '\n'.code.toByte() }
            return if (breakAt < 0) length else from + breakAt + 1
        }
    }

    private fun parse(line: String): Entry? {
        if (line.isBlank()) return null
        val receivedAt = parseStamp(line)
        val body = (if (receivedAt != null) line.substring(STAMP_LENGTH) else line).trimStart()
        if (body.isEmpty()) return null
        return Entry(id = nextId++, level = levelOf(body), message = body, receivedAt = receivedAt ?: System.currentTimeMillis())
    }

    private fun parseStamp(line: String): Long? {
        if (line.length < STAMP_LENGTH) return null
        val head = line.substring(0, STAMP_LENGTH)
        return runCatching { synchronized(stampFormat) { stampFormat.parse(head)?.time } }.getOrNull()
    }

    /**
     * Both cores put the level in the message text: sing-box leads with it (`INFO[0001] …`), Xray brackets it
     * mid-line (`… [Info] …`). Anything unrecognised is ordinary output.
     */
    private fun levelOf(text: String): String {
        levels.firstOrNull { level ->
            text.startsWith(level, ignoreCase = true) && text.length > level.length && text[level.length] == '['
        }?.let { return normalize(it) }
        squareLevel.find(text)?.groupValues?.get(1)?.let { return normalize(it) }
        leadingLevel.find(text)?.groupValues?.get(1)?.let { return normalize(it) }
        return "info"
    }

    private fun normalize(level: String): String = when (level.lowercase()) {
        "warning" -> "warn"
        else -> level.lowercase()
    }

    fun wire(entry: Entry): Map<String, Any?> = mapOf(
        "id" to entry.id,
        "level" to entry.level,
        "message" to entry.message,
        "receivedAt" to entry.receivedAt,
    )

    fun wire(batch: Batch): Map<String, Any?> = mapOf(
        "entries" to batch.entries.map(::wire),
        "reset" to batch.reset,
        "firstId" to batch.firstId,
        "defaultLogLevel" to batch.defaultLogLevel,
    )
}
