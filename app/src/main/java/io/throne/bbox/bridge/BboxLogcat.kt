package io.throne.bbox.bridge

import io.throne.bbox.bridge.BboxLogcat.Batch
import io.throne.bbox.bridge.BboxLogcat.Entry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale

/** A bounded tail of the app's logcat, using the same event shape as [BboxLogs]. */
object BboxLogcat {

    data class Entry(val id: Long, val level: String, val message: String, val receivedAt: Long)

    data class Batch(
        val entries: List<Entry> = emptyList(),
        val reset: Boolean = false,
        val firstId: Long = 0L,
    )

    private val reader = LogcatReader()

    fun start(scope: CoroutineScope, emit: (Batch) -> Unit) = reader.start(scope, emit)

    fun stop() = reader.stop()

    /** The clear command has a timeout; the reader publishes the reset itself. */
    suspend fun clear() = reader.clear()

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

/** Process injection keeps the real reader, cancellation and batching paths testable on the JVM. */
internal class LogcatReader(
    private val spawn: (List<String>) -> Process = { ProcessBuilder(it).start() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val capacity: Int = 1500,
    private val flushMs: Long = 500L,
    private val restartMs: Long = 2000L,
    private val clearTimeoutMs: Long = 3000L,
) {
    private class Session(val emit: (Batch) -> Unit) {
        lateinit var job: Job
        var process: Process? = null
        var epoch = 0L
        val buffer = ArrayDeque<Entry>()
        val pending = ArrayDeque<Entry>()
        var pendingReset = true

        fun reset() {
            epoch++
            buffer.clear()
            pending.clear()
            pendingReset = true
        }
    }

    private val lock = Any()
    private val clearMutex = Mutex()
    private var current: Session? = null
    // Also gates a session started while logcat -c is still running.
    private var clearing = false
    private var nextId = 1L
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val levels = mapOf(
        'V' to "trace", 'D' to "debug", 'I' to "info",
        'W' to "warn", 'E' to "error", 'F' to "fatal",
    )

    fun start(scope: CoroutineScope, emit: (Batch) -> Unit) {
        val session = Session(emit)
        val previous = synchronized(lock) {
            session.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    coroutineScope {
                        // A blocking read is not cancellable. This sibling destroys its process
                        // as soon as cancellation reaches the caller's dispatcher, unblocking IO.
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                release(session)
                            }
                        }
                        launch(io) { read(session) }
                        while (isActive) {
                            delay(flushMs)
                            flush(session)
                        }
                    }
                } finally {
                    release(session)
                }
            }
            current.also { current = session }
        }
        previous?.job?.cancel()
        previous?.let(::release)
        // A launch into an already cancelled scope never enters its body/finally.
        session.job.invokeOnCompletion { release(session) }
        session.job.start()
    }

    fun stop() {
        val session = synchronized(lock) { current.also { current = null } } ?: return
        session.job.cancel()
        release(session)
    }

    private fun release(session: Session) {
        val owned = synchronized(lock) {
            if (current === session) current = null
            session.process.also { session.process = null }
        }
        destroy(owned)
    }

    suspend fun clear() = clearMutex.withLock {
        withContext(io) {
            val owned = synchronized(lock) {
                clearing = true
                current?.let { session ->
                    session.reset()
                    session.process.also { session.process = null }
                }
            }
            try {
                destroy(owned)
                withTimeout(clearTimeoutMs) {
                    val command = spawn(listOf("logcat", "-c"))
                    try {
                        // Process.waitFor(timeout, unit) is unavailable on older Android releases.
                        // Polling exitValue is cancellable and never blocks a worker indefinitely.
                        var status: Int? = null
                        while (status == null) {
                            ensureActive()
                            status = try {
                                command.exitValue()
                            } catch (_: IllegalThreadStateException) {
                                delay(25)
                                null
                            }
                        }
                        if (status != 0) throw IOException("logcat -c exited with $status")
                    } finally {
                        destroy(command)
                    }
                }
            } finally {
                synchronized(lock) { clearing = false }
            }
        }
    }

    private suspend fun read(session: Session) {
        while (currentCoroutineContext().isActive) {
            val epoch = synchronized(lock) {
                if (current === session && !clearing) session.epoch else null
            }
            if (epoch == null) {
                delay(restartMs)
                continue
            }
            var owned: Process? = null
            try {
                val process = spawn(listOf("logcat", "-v", "threadtime", "-T", "500"))
                owned = process
                currentCoroutineContext().ensureActive()
                val readingEpoch = synchronized(lock) {
                    if (current === session && session.job.isActive && !clearing && session.epoch == epoch) {
                        session.process = process
                        // Each open replays the last 500 lines. Replace the previous snapshot,
                        // rather than appending that history again under freshly allocated ids.
                        session.reset()
                        session.epoch
                    } else null
                }
                if (readingEpoch != null) {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = reader.readLine() ?: break
                            if (!append(session, readingEpoch, line)) break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // A killed/failed reader reopens after the pause below.
            } catch (_: SecurityException) {
                // A denied logcat may become available on a later attempt.
            } finally {
                synchronized(lock) {
                    if (session.process === owned) session.process = null
                }
                // Always clean up this attempt, including a spawn that returned after stop/clear.
                destroy(owned)
            }
            delay(restartMs)
        }
    }

    private fun append(session: Session, epoch: Long, line: String): Boolean = synchronized(lock) {
        if (current !== session || !session.job.isActive || clearing || session.epoch != epoch) return false
        val entry = parse(line) ?: return true
        if (session.buffer.size == capacity) session.buffer.removeFirst()
        if (session.pending.size == capacity) session.pending.removeFirst()
        session.buffer.addLast(entry)
        session.pending.addLast(entry)
        true
    }

    private fun flush(session: Session) = synchronized(lock) {
        // Runs on the supplied scope's dispatcher (Main in the bridge). Check and drain here,
        // not on IO before posting a batch: no queued old batch can cross a stop/clear boundary.
        if (current !== session || !session.job.isActive || clearing) return
        if (session.pending.isEmpty() && !session.pendingReset) return
        val batch = Batch(session.pending.toList(), session.pendingReset, session.buffer.firstOrNull()?.id ?: 0L)
        session.pending.clear()
        session.pendingReset = false
        // Keep emission inside the boundary lock so an IO clear cannot overtake this batch.
        session.emit(batch)
    }

    private fun destroy(process: Process?) {
        try {
            process?.destroy()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort if the OS has already disposed of this particular process.
        }
    }

    /** Both `LEVEL/TAG: message` and `LEVEL TAG: message` keep the tag and message. */
    private fun parse(line: String): Entry? {
        if (line.isBlank()) return null
        val parts = line.trim().split(Regex("""\s+"""), limit = 6)
        if (parts.size < 6) {
            return Entry(id = nextId++, level = "info", message = line, receivedAt = System.currentTimeMillis())
        }
        val receivedAt = parseStamp(parts[0], parts[1]) ?: System.currentTimeMillis()
        val tagPart = parts[4]
        val level = levels[tagPart.firstOrNull()?.uppercaseChar()] ?: "info"
        val slashIdx = tagPart.indexOf('/')
        val message = if (slashIdx >= 0) {
            val tag = tagPart.substring(slashIdx + 1).removeSuffix(":")
            if (tag.isNotEmpty()) "$tag: ${parts[5]}" else parts[5]
        } else {
            parts[5]
        }
        return Entry(id = nextId++, level = level, message = message, receivedAt = receivedAt)
    }

    private fun parseStamp(date: String, time: String): Long? = runCatching {
        stampFormat.parse("${Calendar.getInstance().get(Calendar.YEAR)}-$date $time")?.time
    }.getOrNull()
}
