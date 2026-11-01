package io.throne.bbox.bridge

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.throne.bbox.model.Traffic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns the service's speed callbacks into the dashboard's figures and the two sparklines.
 *
 * The service pushes [SpeedDisplayData] about once a second with the proxy-only rates and the session totals. B-box
 * keeps a 30 point history for each direction and scales the chart against the window's peak, so the meter keeps the
 * same window here; the UI only reads it.
 */
class BboxTrafficMeter(private val capacity: Int = 30) {

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private val download = AtomicReference(FloatArray(0))
    private val upload = AtomicReference(FloatArray(0))

    /** The last [capacity] download rates, oldest first, in bytes per second. */
    val downloadHistory: FloatArray get() = download.get()

    /** The last [capacity] upload rates, oldest first, in bytes per second. */
    val uploadHistory: FloatArray get() = upload.get()

    @Synchronized
    fun onSpeed(stats: SpeedDisplayData) {
        _traffic.value = Traffic(
            uploadRate = stats.txRateProxy,
            downloadRate = stats.rxRateProxy,
            uploadTotal = stats.txTotal,
            downloadTotal = stats.rxTotal,
        )
        download.set(push(download.get(), stats.rxRateProxy.toFloat()))
        upload.set(push(upload.get(), stats.txRateProxy.toFloat()))
    }

    @Synchronized
    fun reset() {
        _traffic.value = Traffic()
        download.set(FloatArray(0))
        upload.set(FloatArray(0))
    }

    private fun push(window: FloatArray, value: Float): FloatArray {
        val size = minOf(window.size + 1, capacity)
        val next = FloatArray(size)
        val skip = window.size + 1 - size
        if (skip > 0) window.copyInto(next, 0, skip, window.size) else window.copyInto(next, 0, 0, window.size)
        next[size - 1] = value.coerceAtLeast(0f)
        return next
    }
}
