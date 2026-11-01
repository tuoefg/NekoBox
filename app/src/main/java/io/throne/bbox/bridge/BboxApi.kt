package io.throne.bbox.bridge

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The core's own control API, which the ported UI's connections screen reads.
 *
 * Throne's core bundles sing-box's `experimental.clash_api` server — Thron exposes its port as one sign-encoded
 * setting (`core_box_clash_api`, negated while off) with Thron's own settings screen toggling it. Nothing in the app
 * consumed it before, because the old interface had no connections screen, so this object plays two roles: it is the
 * client, and it is the switch. A connections list cannot exist without the API, and rather than leave the screen
 * empty we flip that setting the same way the core settings screen does and ask for the reload its own code asks for.
 */
object BboxApi {

    /** Thron's default magnitude for `core_box_clash_api`, used when the value has been zeroed. */
    private const val DEFAULT_PORT = 9090

    private const val TIMEOUT_SECONDS = 5L

    data class Server(val host: String, val port: Int, val secret: String)

    @Volatile
    private var client: OkHttpClient? = null

    /** The API as Thron is configured to serve it, whether or not it is currently switched on. */
    fun configured(): Server? {
        val stored = DataStore.coreBoxClashApi
        if (stored == 0) return null
        val address = DataStore.coreBoxClashListenAddr.ifBlank { SettingsRegistry.LOOPBACK_ADDRESS }
        // A wildcard bind is still reachable locally; asking the router for our own LAN name never is.
        val host = if (address == "0.0.0.0" || address == "::") SettingsRegistry.LOOPBACK_ADDRESS else address
        return Server(host, kotlin.math.abs(stored), DataStore.coreBoxClashApiSecret)
    }

    fun enabled(): Boolean = DataStore.coreBoxClashApi > 0

    /**
     * Switches the API on the way the core settings screen does: the port's magnitude survives the off state, so
     * turning it back on restores the port the user chose. True when this call changed anything, which means the
     * service has to reload before the port answers.
     */
    fun enable(): Boolean {
        if (enabled()) return false
        val stored = DataStore.coreBoxClashApi
        DataStore.coreBoxClashApi = if (stored < 0) -stored else DEFAULT_PORT
        return true
    }

    private fun http(): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build().also { client = it }
    }

    private fun call(server: Server, path: String, method: String = "GET"): String {
        val url = "http://${server.host}:${server.port}$path"
        val request = Request.Builder().url(url).apply {
            if (server.secret.isNotBlank()) header("Authorization", "Bearer ${server.secret}")
            when (method) {
                "DELETE" -> delete()
                else -> get()
            }
        }.build()
        http().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}${' '}${body.take(120)}")
            return body
        }
    }

    /** True when the port answered at all, which is also the answer to "is the service actually up". */
    fun reachable(): Boolean {
        val server = configured() ?: return false
        if (!enabled()) return false
        return runCatching { call(server, "/version"); true }.getOrDefault(false)
    }

    /** The live connections, each one already in the shape `pages/connections.dart` renders. */
    fun connections(): List<Map<String, Any?>> {
        val server = configured() ?: return emptyList()
        val body = call(server, "/connections")
        val array = JSONObject(body).optJSONArray("connections") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val metadata = item.optJSONObject("metadata")
            val sourceIp = metadata?.optString("sourceIP").orEmpty()
            val destinationIp = metadata?.optString("destinationIP").orEmpty()
            val sourcePort = metadata?.optString("sourcePort").orEmpty()
            val destinationPort = metadata?.optString("destinationPort").orEmpty()
            val host = metadata?.optString("host").orEmpty()
            val chains = item.optJSONArray("chains")?.let { names ->
                (0 until names.length()).map { names.optString(it) }
            }.orEmpty()
            val source = if (sourceIp.isBlank()) "" else if (sourcePort.isBlank()) sourceIp else "$sourceIp:$sourcePort"
            val destination =
                if (destinationIp.isBlank()) "" else if (destinationPort.isBlank()) destinationIp else "$destinationIp:$destinationPort"
            val upload = item.optLong("upload")
            val download = item.optLong("download")
            val processPath = metadata?.optString("processPath").orEmpty()
            mapOf(
                "id" to item.optString("id"),
                "createdAt" to parseStamp(item.optString("start")),
                "closedAt" to 0L,
                "network" to metadata?.optString("network").orEmpty(),
                "protocol" to metadata?.optString("type").orEmpty(),
                "inbound" to item.optString("inbound").ifBlank { null },
                "outbound" to (chains.lastOrNull() ?: ""),
                "chain" to chains,
                "rule" to item.optString("rule"),
                "source" to source,
                "destination" to destination,
                "domain" to host,
                "displayDestination" to (host.ifBlank { destination }),
                "process" to processPath.takeIf { it.isNotBlank() }?.let { mapOf("path" to it) },
                "upload" to upload,
                "download" to download,
                "uploadTotal" to upload,
                "downloadTotal" to download,
            )
        }
    }

    fun close(id: String): Boolean {
        val server = configured() ?: return false
        return runCatching { call(server, "/connections/$id", "DELETE"); true }.getOrDefault(false)
    }

    fun closeAll(): Boolean {
        val server = configured() ?: return false
        return runCatching { call(server, "/connections", "DELETE"); true }.getOrDefault(false)
    }

    /**
     * The core's own memory use in bytes, for the dashboard card. Null unless the API is on and answered, so the card
     * shows zero rather than a stale figure. `inuse` is what sing-box reports; `rss` is the fallback for a build that
     * names it differently.
     */
    fun memory(): Long? {
        val server = configured() ?: return null
        if (!enabled()) return null
        val body = runCatching { call(server, "/memory") }.getOrNull() ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optLong("inuse").takeIf { it > 0 } ?: json.optLong("rss").takeIf { it > 0 }
    }

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** The core stamps connections as RFC 3339 UTC, sometimes with a fraction we have no use for. */
    private fun parseStamp(value: String): Long {
        if (value.isBlank()) return 0L
        val bare = value.substringBefore('.').trimEnd('Z')
        return runCatching { synchronized(stampFormat) { stampFormat.parse(bare)?.time } }.getOrNull() ?: 0L
    }
}
