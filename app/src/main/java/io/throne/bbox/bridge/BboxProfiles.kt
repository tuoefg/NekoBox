package io.throne.bbox.bridge

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.SubscriptionQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The catalog writes the ported UI can make, in Throne's own terms.
 *
 * B-box's "profile" is what Throne calls a group — a tab that is either a subscription or a hand-filled list — so a
 * create here is a group create and a reorder here is Throne's display order, not a list the UI keeps. The one place
 * that needs care is the configuration editor: the UI wants one JSON document per profile, but the node list it would
 * let you edit lives in the database and not in a document anywhere. So the document describes the group — its
 * subscription options and the nodes it currently has — and writing it back accepts changes to the options while
 * refusing to invent or drop nodes, which have their own screens.
 */
object BboxProfiles {

    data class Config(val content: String, val formatted: String, val revision: String, val stats: Map<String, Any?>) {
        /** The four keys `profiles.config` and `profiles.write` must answer with; `config.dart` reads all of them. */
        fun wire(): Map<String, Any?> = mapOf(
            "content" to content,
            "formatted" to formatted,
            "revision" to revision,
            "stats" to stats,
        )
    }

    /** Profile counts for the dashboard card: the group's own membership, and the routing rules in force. */
    suspend fun stats(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val members = ProfileManager.memberIds(id)
        mapOf(
            "groups" to 1,
            "outbounds" to members.size,
            "rules" to runCatching { SagerDatabase.routeDao.rulesOf(DataStore.currentRouteId).size }.getOrDefault(0),
        )
    }

    suspend fun reorder(ids: List<Long>): Unit = withContext(Dispatchers.IO) {
        GroupRepo.setDisplayOrder(ids)
        BboxBridge.scheduleRefresh(immediate = true)
    }

    /**
     * Creates or renames a group. [url] empty means a local list; otherwise it is a subscription, which immediately
     * fetches so the card is not empty in front of the user.
     */
    suspend fun save(values: Map<String, Any?>): Long = withContext(Dispatchers.IO) {
        val id = (values["id"] as? Number)?.toLong() ?: 0L
        val name = values["name"] as? String
        val url = values["url"] as? String
        val autoUpdate = values["autoUpdate"] as? Boolean
        val interval = (values["interval"] as? Number)?.toInt()

        val group = (if (id > 0L) GroupRepo.get(id) else null) ?: ProxyGroup()
        if (name != null) group.name = name
        if (url != null) group.url = url.trim()
        if (autoUpdate != null) group.skipAutoUpdate = !autoUpdate
        if (interval != null) group.subOptions.updateInterval = interval.coerceAtLeast(0)

        val saved = if (group.id > 0L) {
            GroupRepo.save(group)
            group.id
        } else {
            GroupRepo.add(group).id
        }
        if (group.isSubscription) {
            runCatching { SubscriptionQueue.refreshGroup(saved, showDiff = false) }
        }
        BboxBridge.scheduleRefresh(immediate = true)
        saved
    }

    /**
     * Turns a URI into something the editor can open. Subscriptions arrive either as a plain link or wrapped by the
     * core's own `sing-box://import-remote-profile`, and a QR payload can also be a whole configuration, which becomes
     * a local profile rather than a subscription.
     */
    fun decodeIntent(uri: String): Map<String, Any?> {
        val raw = uri.trim()
        val wrapped = if (raw.startsWith("sing-box://import-remote-profile", ignoreCase = true)) {
            val query = raw.substringAfter('?')
            query.split('&').firstNotNullOfOrNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size == 2 && pieces[0].equals("url", ignoreCase = true)) pieces[1] else null
            }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        } else null
        val target = wrapped ?: raw
        val remote = target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)
        val name = if (remote) runCatching { java.net.URI(target).host }.getOrNull().orEmpty() else ""
        return mapOf(
            "name" to name,
            "url" to if (remote) target else "",
            "remote" to remote,
            "autoUpdate" to remote,
            "interval" to 0,
            "content" to if (remote) "" else target,
        )
    }

    /** The group as the configuration editor's document. */
    suspend fun config(id: Long): Config = withContext(Dispatchers.IO) {
        val group = GroupRepo.get(id) ?: error("No configuration with id $id")
        val content = document(group)
        Config(
            content = content,
            formatted = runCatching { JSONObject(content).toString(2) }.getOrDefault(content),
            revision = group.subLastUpdate.toString(),
            stats = stats(id),
        )
    }

    suspend fun read(id: Long): String = config(id).content

    /**
     * Applies an edited document. Node membership is deliberately rejected rather than silently dropped: the count is
     * compared because comparing every node would let a rename masquerade as a membership change.
     */
    suspend fun write(id: Long, revision: String?, content: String): Config = withContext(Dispatchers.IO) {
        val group = GroupRepo.get(id) ?: error("No configuration with id $id")
        val document = JSONObject(content)
        val nodes = document.optJSONArray("nodes")
        val current = ProfileManager.memberIds(id)
        if (nodes != null && nodes.length() != current.size) {
            error("Node membership is edited on the Proxies screen, not here")
        }
        document.optString("name").takeIf { it.isNotBlank() }?.let { group.name = it }
        if (document.has("url")) {
            val url = document.optString("url").trim()
            if (url != group.url) group.url = url
        }
        if (document.has("autoUpdate")) group.skipAutoUpdate = !document.optBoolean("autoUpdate")
        if (document.has("updateIntervalMinutes")) {
            group.subOptions.updateInterval = document.optInt("updateIntervalMinutes").coerceAtLeast(0)
        }
        GroupRepo.save(group)
        BboxBridge.scheduleRefresh(immediate = true)
        config(id)
    }

    private suspend fun document(group: ProxyGroup): String {
        val members = withContext(Dispatchers.IO) { runCatching { ProfileManager.members(group.id) }.getOrDefault(emptyList()) }
        val nodes = JSONArray()
        members.forEach { member ->
            nodes.put(
                JSONObject().apply {
                    put("tag", member.displayName())
                    put("type", member.displayType())
                    put("delay", member.latency)
                }
            )
        }
        return JSONObject().apply {
            put("name", group.displayName())
            put("url", group.url)
            put("autoUpdate", !group.skipAutoUpdate)
            put("updateIntervalMinutes", group.subOptions.updateInterval)
            put("nodes", nodes)
        }.toString(2)
    }
}
