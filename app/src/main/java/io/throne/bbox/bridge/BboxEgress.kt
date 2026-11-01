package io.throne.bbox.bridge

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The outbound identity the status card shows, read through the running tunnel.
 *
 * B-box asks libbox for the routed egress; Throne exposes the same thing as the local mixed inbound, so the probe
 * sends an HTTP request through `mixedPort` and reads what the far end sees. Two public endpoints are tried in turn
 * because either can rate-limit a shared exit.
 */
object BboxEgress {

    data class Info(
        val ip: String? = null,
        val country: String? = null,
        val organization: String? = null,
        val asn: String? = null,
        val type: String? = null,
    ) {
        val isEmpty: Boolean get() = ip.isNullOrBlank()
    }

    private val endpoints = listOf(
        "https://api.ip.sb/geoip" to ::parseIpSb,
        "https://ipinfo.io/json" to ::parseIpInfo,
    )

    @Volatile
    private var cached: Info = Info()

    @Volatile
    private var cachedAt = 0L

    private const val TTL_MS = 30_000L

    fun cached(): Info = cached

    /**
     * Probes the egress. Returns the cached answer while it is fresh unless [force]; a failure keeps the previous
     * answer and reports the error through [onError].
     */
    suspend fun probe(force: Boolean = false, onError: (String) -> Unit = {}): Info {
        val now = System.currentTimeMillis()
        if (!force && now - cachedAt < TTL_MS && !cached.isEmpty) return cached
        val port = DataStore.inboundSocksPort
        val host = DataStore.inboundAddress.ifBlank { SettingsRegistry.LOOPBACK_ADDRESS }
        if (port <= 0) return cached
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        var lastError: String? = null
        for ((url, parse) in endpoints) {
            try {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    val info = parse(JSONObject(body))
                    if (!info.isEmpty) {
                        cached = info
                        cachedAt = System.currentTimeMillis()
                        return info
                    }
                    lastError = "Empty answer"
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        onError(lastError ?: "Unreachable")
        return cached
    }

    fun invalidate() {
        cachedAt = 0L
    }

    private fun parseIpSb(json: JSONObject): Info = Info(
        ip = json.optString("ip").takeIf { it.isNotBlank() },
        country = json.optString("country").takeIf { it.isNotBlank() },
        organization = json.optString("organization").takeIf { it.isNotBlank() },
        asn = json.optString("asn").takeIf { it.isNotBlank() },
        type = json.optString("type").takeIf { it.isNotBlank() } ?: "IPv4",
    )

    private fun parseIpInfo(json: JSONObject): Info {
        val org = json.optString("org").takeIf { it.isNotBlank() }
        val asn = org?.substringBefore(' ')?.takeIf { it.startsWith("AS") }
        return Info(
            ip = json.optString("ip").takeIf { it.isNotBlank() },
            country = json.optString("country").takeIf { it.isNotBlank() },
            organization = org?.substringAfter(' ', org),
            asn = asn,
            type = if (json.optString("ip").contains(':')) "IPv6" else "IPv4",
        )
    }
}
