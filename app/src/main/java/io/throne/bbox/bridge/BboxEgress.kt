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
 * The outbound identity the status card shows.
 *
 * B-box asks libbox for the routed egress; Throne exposes the same thing as the local mixed inbound, so the probe
 * sends an HTTP request through `mixedPort` and reads what the far end sees. With the service stopped nothing answers
 * that port, so the same request goes out over the phone's own connection instead and reports the direct address. Two
 * public endpoints are tried in turn because either can rate-limit a shared exit.
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

    // The tunnel answer and the phone's own answer are different addresses; sharing one slot would show the exit
    // of a connection that is no longer in use right after a start or stop.
    @Volatile
    private var cachedDirect: Info = Info()

    @Volatile
    private var cachedDirectAt = 0L

    private const val TTL_MS = 30_000L

    fun cached(direct: Boolean = false): Info = if (direct) cachedDirect else cached

    /**
     * Probes the egress, through the mixed inbound unless [direct]. Returns the cached answer while it is fresh unless
     * [force]; a failure keeps the previous answer and reports the error through [onError].
     */
    suspend fun probe(force: Boolean = false, direct: Boolean = false, onError: (String) -> Unit = {}): Info {
        val now = System.currentTimeMillis()
        val fresh = if (direct) {
            now - cachedDirectAt < TTL_MS && !cachedDirect.isEmpty
        } else {
            now - cachedAt < TTL_MS && !cached.isEmpty
        }
        if (!force && fresh) return cached(direct)
        val http = clientFor(direct) ?: return cached(direct)
        var lastError: String? = null
        for ((url, parse) in endpoints) {
            try {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    val info = parse(JSONObject(body))
                    if (!info.isEmpty) {
                        if (direct) {
                            cachedDirect = info
                            cachedDirectAt = System.currentTimeMillis()
                        } else {
                            cached = info
                            cachedAt = System.currentTimeMillis()
                        }
                        return info
                    }
                    lastError = "Empty answer"
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        onError(lastError ?: "Unreachable")
        return cached(direct)
    }

    /** The client for one route; null when the tunnel is asked for but no inbound is configured yet. */
    private fun clientFor(direct: Boolean): OkHttpClient? {
        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
        if (direct) return builder.build()
        val port = DataStore.inboundSocksPort
        if (port <= 0) return null
        val host = DataStore.inboundAddress.ifBlank { SettingsRegistry.LOOPBACK_ADDRESS }
        return builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))).build()
    }

    fun invalidate() {
        cachedAt = 0L
        cachedDirectAt = 0L
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
