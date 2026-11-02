package io.throne.bbox.bridge

import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BboxProbeSessionTest {
    private class FakeCall(private val executeAction: (FakeCall) -> Response) : Call {
        private val originalRequest = Request.Builder().url("https://probe.invalid/").build()
        val executions = AtomicInteger()
        val cancellations = AtomicInteger()
        val cancelled = CountDownLatch(1)

        override fun request(): Request = originalRequest
        override fun execute(): Response {
            executions.incrementAndGet()
            return executeAction(this)
        }
        override fun enqueue(responseCallback: Callback): Unit = error("Synchronous probes must not enqueue")
        override fun cancel() {
            cancellations.incrementAndGet()
            cancelled.countDown()
        }
        override fun isExecuted(): Boolean = executions.get() != 0
        override fun isCanceled(): Boolean = cancelled.count == 0L
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(executeAction)
    }

    /** Uses Okio's real buffered reading/closing, with a barrier at the underlying body read. */
    private class TrackingBody(beforeRead: () -> Unit = {}) : ResponseBody() {
        val closed = AtomicBoolean()
        private val input = object : ForwardingSource(Buffer().writeUtf8("probe body")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                beforeRead()
                return super.read(sink, byteCount)
            }
            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = input
    }

    private class Fixture : AutoCloseable {
        val client = OkHttpClient()
        val session = BboxProbeSession(client)
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "probe-session-test").apply { isDaemon = true }
        }

        fun execute(call: Call): Future<Result<String>> = worker.submit<Result<String>> {
            runCatching { session.execute(call) { it.body!!.string() } }
        }

        override fun close() {
            session.close()
            worker.shutdownNow()
            assertTrue("Probe test worker did not terminate", worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for probe barrier", latch.await(5, TimeUnit.SECONDS))
    }

    private fun response(call: Call, body: ResponseBody): Response = Response.Builder()
        .request(call.request())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()

    private fun assertCancelled(result: Result<*>) {
        val failure = result.exceptionOrNull()
        assertTrue("Expected CancellationException, got $result", failure is CancellationException)
        assertEquals("IP probe session closed", failure!!.message)
    }

    @Test
    fun closeDuringExecuteCancelsRegisteredCall() {
        Fixture().use { f ->
            val executing = CountDownLatch(1)
            val call = FakeCall {
                executing.countDown()
                await(it.cancelled)
                throw IOException("cancelled during execute")
            }
            val result = f.execute(call)
            await(executing)
            f.session.close()
            assertCancelled(result.get(5, TimeUnit.SECONDS))
            assertEquals(1, call.executions.get())
            assertEquals(1, call.cancellations.get())
        }
    }

    @Test
    fun closeDuringResponseReadCancelsCallAndClosesBody() {
        Fixture().use { f ->
            val reading = CountDownLatch(1)
            lateinit var call: FakeCall
            val body = TrackingBody {
                reading.countDown()
                await(call.cancelled)
                throw IOException("cancelled during body read")
            }
            call = FakeCall { response(it, body) }
            val result = f.execute(call)
            await(reading)
            f.session.close()
            assertCancelled(result.get(5, TimeUnit.SECONDS))
            assertEquals(1, call.cancellations.get())
            assertTrue(body.closed.get())
        }
    }

    @Test
    fun executeAfterCloseNeverRunsCallOrReadCallback() {
        Fixture().use { f ->
            val call = FakeCall { error("Closed session executed a call") }
            var read = false
            f.session.close()
            val result = runCatching {
                f.session.execute(call) {
                    read = true
                    "unexpected"
                }
            }
            assertCancelled(result)
            assertEquals(0, call.executions.get())
            assertEquals(0, call.cancellations.get())
            assertFalse(read)
        }
    }

    @Test
    fun successfulExecuteArrivingAfterCloseBecomesCancellation() {
        Fixture().use { f ->
            val executing = CountDownLatch(1)
            val body = TrackingBody()
            val call = FakeCall {
                executing.countDown()
                await(it.cancelled)
                // Model a transport that returns a successful response despite cancel().
                response(it, body)
            }
            val result = f.execute(call)
            await(executing)
            f.session.close()
            assertCancelled(result.get(5, TimeUnit.SECONDS))
            assertEquals(1, call.cancellations.get())
            assertTrue(body.closed.get())
        }
    }

    @Test
    fun successfulReadArrivingAfterCloseBecomesCancellation() {
        Fixture().use { f ->
            val reading = CountDownLatch(1)
            lateinit var call: FakeCall
            val body = TrackingBody {
                reading.countDown()
                // Buffered bytes may still be readable after the underlying call was cancelled.
                await(call.cancelled)
            }
            call = FakeCall { response(it, body) }
            val result = f.execute(call)
            await(reading)
            f.session.close()
            assertCancelled(result.get(5, TimeUnit.SECONDS))
            assertTrue(body.closed.get())
            assertEquals(1, call.cancellations.get())
        }
    }

    @Test
    fun executeIOExceptionIsPreservedAndCallIsUnregistered() {
        Fixture().use { f ->
            val failure = IOException("connection refused")
            val call = FakeCall { throw failure }
            val result = f.execute(call).get(5, TimeUnit.SECONDS)
            assertSame(failure, result.exceptionOrNull())
            f.session.close()
            assertEquals("Completed failed call must not remain registered", 0, call.cancellations.get())
        }
    }

    @Test
    fun readIOExceptionIsPreservedAndCallAndResponseAreReleased() {
        Fixture().use { f ->
            val failure = IOException("body read failed")
            val body = TrackingBody { throw failure }
            val call = FakeCall { response(it, body) }
            val result = f.execute(call).get(5, TimeUnit.SECONDS)
            assertSame(failure, result.exceptionOrNull())
            assertTrue(body.closed.get())
            f.session.close()
            assertEquals("Failed body read must unregister its call", 0, call.cancellations.get())
        }
    }

    @Test
    fun normalSuccessReturnsBodyAndReleasesCall() {
        Fixture().use { f ->
            val body = TrackingBody()
            val call = FakeCall { response(it, body) }
            assertEquals("probe body", f.execute(call).get(5, TimeUnit.SECONDS).getOrThrow())
            assertTrue(body.closed.get())
            f.session.close()
            assertEquals(0, call.cancellations.get())
        }
    }

    @Test
    fun closeIsIdempotentWhileRegisteredCallIsStillUnwinding() {
        Fixture().use { f ->
            val executing = CountDownLatch(1)
            val finishExecute = CountDownLatch(1)
            val call = FakeCall {
                executing.countDown()
                await(finishExecute)
                throw IOException("unwinding after close")
            }
            val result = f.execute(call)
            await(executing)
            try {
                f.session.close()
                f.session.close()
                assertEquals(1, call.cancellations.get())
                assertTrue(f.client.dispatcher.executorService.isShutdown)
            } finally {
                finishExecute.countDown()
            }
            assertCancelled(result.get(5, TimeUnit.SECONDS))
            f.session.close()
            assertEquals(1, call.cancellations.get())
        }
    }
}
