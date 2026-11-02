package io.throne.bbox.bridge

import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Owns the calls of one IP lookup so a closed session cannot start another request. */
internal class BboxProbeSession(private val client: OkHttpClient) {
    private var closed = false
    private val calls = mutableSetOf<Call>()

    fun get(url: String, timeoutMillis: Long): Map<String, Any?> {
        val request = Request.Builder().url(url).build()
        val call = client.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build().newCall(request)
        return execute(call) { response ->
            mapOf("status" to response.code, "body" to response.body?.string().orEmpty())
        }
    }

    internal fun <T> execute(call: Call, read: (Response) -> T): T {
        synchronized(this) {
            if (closed) throw CancellationException("IP probe session closed")
            calls.add(call)
        }
        try {
            val result = call.execute().use(read)
            synchronized(this) {
                if (closed) throw CancellationException("IP probe session closed")
            }
            return result
        } catch (e: Exception) {
            if (synchronized(this) { closed }) throw CancellationException("IP probe session closed")
            throw e
        } finally {
            synchronized(this) { calls.remove(call) }
        }
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            calls.forEach { it.cancel() }
            calls.clear()
        }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
