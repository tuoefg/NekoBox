package io.throne.bbox.bridge

import io.throne.bbox.bridge.BboxLogcat.Batch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BboxLogcatTest {
    private fun CountDownLatch.awaitReady() {
        assertTrue("Timed out waiting for test barrier", await(5, TimeUnit.SECONDS))
    }

    private suspend fun eventually(check: () -> Boolean) {
        withTimeout(5000) { while (!check()) delay(5) }
    }

    /** Real blocking reads, with optional barriers to model bytes/close arriving after destroy. */
    private class Pipe : InputStream() {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private val ended = AtomicBoolean()
        private var chunk = byteArrayOf()
        private var offset = 0
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val readThreads = CopyOnWriteArrayList<String>()
        var beforeReadReturn: (() -> Unit)? = null
        var beforeClose: (() -> Unit)? = null

        fun send(text: String) { chunks.add(text.toByteArray()) }
        fun end() { if (ended.compareAndSet(false, true)) chunks.add(byteArrayOf()) }
        override fun read(): Int {
            val data = ByteArray(1)
            return if (read(data, 0, 1) < 0) -1 else data[0].toInt() and 255
        }
        override fun read(data: ByteArray, start: Int, length: Int): Int {
            readThreads += Thread.currentThread().name
            reading.countDown()
            if (offset == chunk.size) {
                chunk = chunks.take()
                offset = 0
                if (chunk.isEmpty()) return -1
            }
            val size = minOf(length, chunk.size - offset)
            chunk.copyInto(data, start, offset, offset + size)
            offset += size
            beforeReadReturn?.invoke()
            return size
        }
        override fun close() {
            beforeClose?.invoke()
            end()
            closed.countDown()
        }
    }

    private class FakeProcess(val pipe: Pipe = Pipe()) : Process() {
        val destroyed = CountDownLatch(1)
        val destroys = AtomicInteger()
        @Volatile var status: Int? = null
        override fun getInputStream() = pipe
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = error("An unbounded waitFor must never be used")
        override fun exitValue(): Int = status ?: throw IllegalThreadStateException()
        override fun destroy() {
            destroys.incrementAndGet()
            status = status ?: 0
            pipe.end()
            destroyed.countDown()
        }
        fun finish(code: Int = 0) { status = code; pipe.end() }
    }

    private class Fixture(capacity: Int = 1500, clearTimeoutMs: Long = 1000) : AutoCloseable {
        val main = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "logcat-test-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val io = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "logcat-test-io").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val root = SupervisorJob()
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(root + main + CoroutineExceptionHandler { _, error -> failures += error })
        val opens = LinkedBlockingQueue<() -> Process>()
        val batches = CopyOnWriteArrayList<Batch>()
        val emitThreads = CopyOnWriteArrayList<String>()
        val spawnThreads = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger()
        @Volatile var clearCommand: () -> Process = { FakeProcess().apply { finish() } }
        val reader = LogcatReader(
            spawn = { command ->
                spawnThreads += Thread.currentThread().name
                if (command.last() == "-c") clearCommand() else {
                    attempts.incrementAndGet()
                    (opens.poll() ?: throw IOException("No scripted process"))()
                }
            },
            io = io,
            capacity = capacity,
            flushMs = 10,
            restartMs = 10,
            clearTimeoutMs = clearTimeoutMs,
        )
        fun enqueue(process: FakeProcess) { opens.add { process } }
        fun start() = reader.start(scope) {
            emitThreads += Thread.currentThread().name
            batches += it
        }
        override fun close() {
            reader.stop()
            runBlocking { withTimeout(5000) { root.cancelAndJoin() } }
            main.close()
            io.close()
            // A bare "at line N" frame is all the Gradle console shows for this assert; print the
            // whole stacks so a CI-only failure is diagnosable from the log alone.
            failures.forEach { it.printStackTrace() }
            assertTrue("Unexpected worker failures: $failures", failures.isEmpty())
        }
    }

    @Test
    fun blockingReadsStayOnIoWhileMainFlushesAndScopeCancellationDestroysProcess() = runBlocking {
        Fixture().use { f ->
            val process = FakeProcess()
            process.pipe.send("11-02 12:00:00.123 10 11 I/Tag: hello  world\n")
            f.enqueue(process)
            f.start()
            eventually { f.batches.any { it.entries.isNotEmpty() } }
            assertEquals("Tag: hello  world", f.batches.flatMap { it.entries }.single().message)
            // Gradle enables coroutine debugging, which appends " @coroutine#N" to thread names.
            assertTrue("Read threads: ${process.pipe.readThreads}",
                process.pipe.readThreads.all { it.substringBefore(" @") == "logcat-test-io" })
            assertTrue("Spawn threads: ${f.spawnThreads}",
                f.spawnThreads.all { it.substringBefore(" @") == "logcat-test-io" })
            assertTrue("Emit threads: ${f.emitThreads}",
                f.emitThreads.all { it.substringBefore(" @") == "logcat-test-main" })
            withTimeout(1000) { withContext(f.main) { assertTrue(true) } }
            withTimeout(1000) { f.root.cancelAndJoin() }
            process.destroyed.awaitReady()
            process.pipe.closed.awaitReady()
        }
    }

    @Test
    fun oldReaderFinallyCannotKillReplacementSession() = runBlocking {
        Fixture().use { f ->
            val closeEntered = CountDownLatch(1)
            val allowClose = CountDownLatch(1)
            val old = FakeProcess()
            old.pipe.beforeClose = { closeEntered.countDown(); allowClose.awaitReady() }
            f.enqueue(old)
            f.start()
            old.pipe.reading.awaitReady()
            f.reader.stop()
            closeEntered.awaitReady()
            val fresh = FakeProcess()
            fresh.pipe.send("replacement\n")
            f.enqueue(fresh)
            try {
                f.start()
                eventually { f.batches.any { b -> b.entries.any { it.message == "replacement" } } }
            } finally {
                allowClose.countDown()
            }
            eventually { old.destroys.get() >= 2 }
            assertEquals(0, fresh.destroys.get())
            fresh.pipe.send("still alive\n")
            eventually { f.batches.any { b -> b.entries.any { it.message == "still alive" } } }
        }
    }

    @Test
    fun processReturnedAfterStopIsDestroyedWithoutTouchingNewReader() = runBlocking {
        Fixture().use { f ->
            val spawning = CountDownLatch(1)
            val allowSpawn = CountDownLatch(1)
            val old = FakeProcess()
            f.opens.add { spawning.countDown(); allowSpawn.awaitReady(); old }
            f.start()
            spawning.awaitReady()
            f.reader.stop()
            val fresh = FakeProcess()
            f.enqueue(fresh)
            try {
                f.start()
                fresh.pipe.reading.awaitReady()
            } finally {
                allowSpawn.countDown()
            }
            old.destroyed.awaitReady()
            assertEquals(0, fresh.destroys.get())
            assertTrue(old.pipe.readThreads.isEmpty())
        }
    }

    @Test
    fun clearDropsBytesAlreadyReadBeforeDestroyAndPublishesOnlyNewEpoch() = runBlocking {
        Fixture().use { f ->
            val bytesRead = CountDownLatch(1)
            val allowReturn = CountDownLatch(1)
            val old = FakeProcess()
            old.pipe.beforeReadReturn = { bytesRead.countDown(); allowReturn.awaitReady() }
            old.pipe.send("stale one\nstale two\nstale three\n")
            f.enqueue(old)
            f.start()
            bytesRead.awaitReady()
            val fresh = FakeProcess()
            fresh.pipe.send("fresh\n")
            f.enqueue(fresh)
            try {
                f.reader.clear()
                eventually { f.batches.any { it.reset && it.entries.isEmpty() } }
                old.destroyed.awaitReady()
            } finally {
                allowReturn.countDown()
            }
            eventually { f.batches.any { b -> b.entries.any { it.message == "fresh" } } }
            assertFalse(f.batches.flatMap { it.entries }.any { it.message.startsWith("stale") })
            assertEquals(0, fresh.destroys.get())
        }
    }

    @Test
    fun aSpawnCrossingClearIsRejectedEvenAfterClearFinished() = runBlocking {
        Fixture().use { f ->
            val spawning = CountDownLatch(1)
            val allowSpawn = CountDownLatch(1)
            val stale = FakeProcess().apply { pipe.send("stale\n") }
            f.opens.add { spawning.countDown(); allowSpawn.awaitReady(); stale }
            f.start()
            spawning.awaitReady()
            val fresh = FakeProcess().apply { pipe.send("fresh\n") }
            f.enqueue(fresh)
            try {
                f.reader.clear()
            } finally {
                allowSpawn.countDown()
            }
            stale.destroyed.awaitReady()
            eventually { f.batches.any { b -> b.entries.any { it.message == "fresh" } } }
            assertTrue(stale.pipe.readThreads.isEmpty())
            assertEquals(0, fresh.destroys.get())
        }
    }

    @Test
    fun restartReplacesHistoryInsteadOfAppendingItAgain() = runBlocking {
        Fixture().use { f ->
            val first = FakeProcess().apply { pipe.send("history\n") }
            f.enqueue(first)
            f.start()
            eventually { f.batches.any { b -> b.entries.any { it.message == "history" } } }
            val second = FakeProcess().apply { pipe.send("history\nnew\n") }
            f.enqueue(second)
            first.finish()
            eventually { f.batches.any { b -> b.entries.any { it.message == "new" } } }
            val rows = mutableListOf<BboxLogcat.Entry>()
            for (batch in f.batches) {
                if (batch.reset) rows.clear() else rows.removeAll { it.id < batch.firstId }
                val lastId = rows.lastOrNull()?.id ?: -1L
                rows += batch.entries.filter { it.id > lastId }
            }
            assertEquals(listOf("history", "new"), rows.map { it.message })
        }
    }

    @Test
    fun burstBeforeFirstFlushBoundsBothPendingAndRetention() = runBlocking {
        Fixture(capacity = 8).use { f ->
            val mainBlocked = CountDownLatch(1)
            val releaseMain = CountDownLatch(1)
            val process = FakeProcess()
            f.enqueue(process)
            f.start()
            process.pipe.reading.awaitReady()
            val blocker = async(f.main) { mainBlocked.countDown(); releaseMain.awaitReady() }
            mainBlocked.awaitReady()
            process.pipe.send((1..100).joinToString("\n", postfix = "\n") { "line $it" })
            try {
                eventually { process.pipe.readThreads.size >= 2 }
            } finally {
                releaseMain.countDown()
            }
            blocker.await()
            eventually { f.batches.any { b -> b.entries.any { it.message == "line 100" } } }
            val burst = f.batches.last { it.entries.isNotEmpty() }
            assertEquals((93..100).map { "line $it" }, burst.entries.map { it.message })
            assertEquals(burst.entries.first().id, burst.firstId)
            assertTrue(f.batches.all { it.entries.size <= 8 })
            process.pipe.send("line 101\n")
            eventually { f.batches.any { b -> b.entries.any { it.message == "line 101" } } }
            val next = f.batches.last { it.entries.isNotEmpty() }
            assertEquals(burst.firstId + 1, next.firstId)
        }
    }

    @Test
    fun queuedFlushCannotEmitAfterStopOrClear() = runBlocking {
        for (clear in listOf(false, true)) {
            Fixture().use { f ->
                val mainBlocked = CountDownLatch(1)
                val releaseMain = CountDownLatch(1)
                val process = FakeProcess()
                f.enqueue(process)
                f.start()
                process.pipe.reading.awaitReady()
                val blocker = async(f.main) { mainBlocked.countDown(); releaseMain.awaitReady() }
                mainBlocked.awaitReady()
                val before = f.batches.size
                process.pipe.send("must not be emitted\n")
                try {
                    eventually { process.pipe.readThreads.size >= 2 }
                    // Let the flusher's delay enqueue its continuation behind the blocked Main.
                    delay(30)
                    if (clear) f.reader.clear() else f.reader.stop()
                } finally {
                    releaseMain.countDown()
                }
                blocker.await()
                withContext(f.main) { }
                if (clear) eventually { f.batches.drop(before).any { it.reset } }
                assertTrue(f.batches.drop(before).all { it.entries.isEmpty() })
            }
        }
    }

    @Test
    fun startingWithCancelledScopeDisposesPreviousSessionAndNeverSpawns() = runBlocking {
        Fixture().use { f ->
            val old = FakeProcess()
            f.enqueue(old)
            f.start()
            old.pipe.reading.awaitReady()
            val cancelled = SupervisorJob().apply { cancel() }
            val attempts = f.attempts.get()
            f.reader.start(CoroutineScope(cancelled + f.main)) { error("Cancelled session emitted") }
            old.destroyed.awaitReady()
            withContext(f.main) { }
            assertEquals(attempts, f.attempts.get())
            // Completion of that cancelled launch must not erase a subsequent live session.
            val fresh = FakeProcess().apply { pipe.send("live\n") }
            f.enqueue(fresh)
            f.start()
            eventually { f.batches.any { b -> b.entries.any { it.message == "live" } } }
            assertEquals(0, fresh.destroys.get())
        }
    }

    @Test
    fun clearTimesOutDestroysItsCommandAndAllowsSubsequentReads() = runBlocking {
        Fixture(clearTimeoutMs = 100).use { f ->
            val command = FakeProcess()
            f.clearCommand = { command }
            try {
                withTimeout(2000) { f.reader.clear() }
                error("Expected clear timeout")
            } catch (_: TimeoutCancellationException) {
                command.destroyed.awaitReady()
            }
            val fresh = FakeProcess().apply { pipe.send("after timeout\n") }
            f.enqueue(fresh)
            f.start()
            eventually { f.batches.any { b -> b.entries.any { it.message == "after timeout" } } }
        }
    }

    @Test
    fun cancelledClearCleansCommandAndDoesNotSwallowCancellation() = runBlocking {
        Fixture().use { f ->
            val spawned = CountDownLatch(1)
            val command = FakeProcess()
            f.clearCommand = { spawned.countDown(); command }
            val clearing = async { f.reader.clear() }
            eventually { spawned.count == 0L }
            clearing.cancel()
            try {
                clearing.await()
                error("Cancellation was swallowed")
            } catch (_: CancellationException) {
                command.destroyed.awaitReady()
            }
            f.clearCommand = { FakeProcess().apply { finish() } }
            withTimeout(1000) { f.reader.clear() }
        }
    }

    @Test
    fun startDuringClearWaitsForCommandAndOldClearCannotDestroyIt() = runBlocking {
        Fixture().use { f ->
            val spawned = CountDownLatch(1)
            val command = FakeProcess()
            f.clearCommand = { spawned.countDown(); command }
            val clearing = async { f.reader.clear() }
            eventually { spawned.count == 0L }
            val fresh = FakeProcess().apply { pipe.send("after clear\n") }
            f.enqueue(fresh)
            f.start()
            withContext(f.main) { }
            delay(30)
            assertEquals(0, f.attempts.get())
            command.finish()
            clearing.await()
            eventually { f.batches.any { b -> b.entries.any { it.message == "after clear" } } }
            assertEquals(0, fresh.destroys.get())
        }
    }

    @Test
    fun ioFailureReopensWithResetAndDoesNotLeakTheFailedProcess() = runBlocking {
        Fixture().use { f ->
            val first = FakeProcess()
            first.pipe.beforeReadReturn = { throw IOException("pipe failed") }
            first.pipe.send("discarded\n")
            val second = FakeProcess().apply { pipe.send("recovered\n") }
            f.enqueue(first)
            f.enqueue(second)
            f.start()
            eventually { f.batches.any { b -> b.entries.any { it.message == "recovered" } } }
            first.destroyed.awaitReady()
            assertFalse(f.batches.flatMap { it.entries }.any { it.message == "discarded" })
            assertEquals(0, second.destroys.get())
        }
    }

    @Test
    fun concurrentClearCommandsAreSerialized() = runBlocking {
        Fixture().use { f ->
            val commands = CopyOnWriteArrayList<FakeProcess>()
            f.clearCommand = { FakeProcess().also { commands += it } }
            val first = async { f.reader.clear() }
            eventually { commands.size == 1 }
            val second = async { f.reader.clear() }
            delay(30)
            assertEquals(1, commands.size)
            commands[0].finish()
            first.await()
            eventually { commands.size == 2 }
            commands[1].finish()
            second.await()
            commands.forEach { it.destroyed.awaitReady() }
        }
    }

    @Test
    fun failedClearReportsExitStatusAndReleasesGate() = runBlocking {
        Fixture().use { f ->
            val command = FakeProcess().apply { finish(1) }
            f.clearCommand = { command }
            try {
                f.reader.clear()
                error("Expected nonzero exit to fail")
            } catch (error: IOException) {
                assertEquals("logcat -c exited with 1", error.message)
            }
            command.destroyed.awaitReady()
            f.clearCommand = { FakeProcess().apply { finish() } }
            f.reader.clear()
        }
    }
}
