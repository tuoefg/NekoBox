package io.throne.bbox.bridge

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.group.SubscriptionQueue
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.import.ProfileImport
import io.nekohasekai.sagernet.outbound.types.AutoSelector
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
     *
     * A new local group may also carry `content`: one share link, a base64 subscription, or a whole JSON/YAML
     * document. It is parsed with Throne's own importer *before* the group is created, so an invalid import leaves
     * nothing behind, and its outbounds become the group's nodes.
     */
    suspend fun save(values: Map<String, Any?>): Long = withContext(Dispatchers.IO) {
        val id = (values["id"] as? Number)?.toLong() ?: 0L
        val name = values["name"] as? String
        val url = values["url"] as? String
        val autoUpdate = values["autoUpdate"] as? Boolean
        val interval = (values["interval"] as? Number)?.toInt()
        val content = values["content"] as? String

        val group = (if (id > 0L) GroupRepo.get(id) else null) ?: ProxyGroup()
        if (name != null) group.name = name.trim()
        if (url != null) group.url = url.trim()
        if (autoUpdate != null) group.skipAutoUpdate = !autoUpdate
        if (interval != null) group.subOptions.updateInterval = interval.coerceAtLeast(0)

        // `{}` is the editor's own "new empty group" seed, not an import to parse.
        val import = if (id <= 0L && !group.isSubscription) {
            val text = content?.trim().orEmpty()
            if (text.isEmpty() || text == "{}") emptyList() else parseOutbounds(text)
        } else {
            emptyList()
        }
        if (group.name.isNullOrBlank() && import.size == 1) group.name = import.single().displayName()

        val saved = if (group.id > 0L) {
            GroupRepo.save(group)
            group.id
        } else {
            GroupRepo.add(group).id
        }
        if (import.isNotEmpty()) {
            val added = ProfileManager.addProfileBatch(import, saved)
            GroupRepo.setCurrent(saved)
            // A single imported node is what the user asked to use; a whole subscription stays unselected until chosen.
            added.singleOrNull()?.let { BboxCommands.select(it.id) }
        }
        if (group.isSubscription) {
            runCatching { SubscriptionQueue.refreshGroup(saved, showDiff = false) }
        }
        BboxBridge.scheduleRefresh(immediate = true)
        saved
    }

    /**
     * Turns imported text into something the editor can open. Subscriptions arrive either as a plain link or wrapped
     * by the core's own `sing-box://import-remote-profile`; anything else — a share link, a base64 subscription, a
     * JSON or YAML document — goes through Throne's importer, so a single `ss://`/`vmess://`/`vless://` node opens
     * with its own name instead of failing the sing-box JSON check.
     */
    suspend fun decodeIntent(uri: String): Map<String, Any?> = withContext(Dispatchers.IO) {
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
        if (remote) {
            val name = runCatching { java.net.URI(target).host }.getOrNull().orEmpty()
            return@withContext mapOf(
                "name" to name,
                "url" to target,
                "remote" to true,
                "autoUpdate" to true,
                "interval" to 0,
                "content" to "",
            )
        }
        val outbounds = parseOutbounds(target)
        mapOf(
            "name" to outbounds.first().displayName(),
            "url" to "",
            "remote" to false,
            "autoUpdate" to false,
            "interval" to 0,
            "content" to target,
            "nodeCount" to outbounds.size,
        )
    }

    /** The share link of one node, falling back to Throne's loss-less JSON link when the protocol has no standard URI. */
    suspend fun nodeLink(groupTag: String, nodeTag: String, jsonLink: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val group = GroupRepo.all().firstOrNull { it.displayName() == groupTag }
                ?: error("No proxy group named $groupTag")
            val profile = ProfileManager.members(group.id).firstOrNull { it.displayName() == nodeTag }
                ?: error("No proxy named $nodeTag in ${group.displayName()}")
            val link = if (jsonLink) {
                profile.exportJsonLink()
            } else {
                profile.exportLink().ifBlank { profile.exportJsonLink() }
            }
            if (link.isBlank()) error("${profile.displayName()} cannot be shared as a link")
            link
        }

    /** The editor's seed for one node: its name, type and the share link it can be edited from. */
    suspend fun nodeInfo(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val profile = ProfileManager.getProfile(id) ?: error("No proxy with id $id")
        mapOf(
            "id" to profile.id,
            "name" to profile.displayName(),
            "type" to profile.displayType(),
            "content" to profile.exportLink().ifBlank { profile.exportJsonLink() },
        )
    }

    /**
     * Renames a node and/or replaces its outbound with one share link or JSON document. The content
     * must describe exactly one node — a whole subscription belongs to a group save, not here.
     */
    suspend fun updateNode(id: Long, name: String?, content: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        val profile = ProfileManager.getProfile(id) ?: error("No proxy with id $id")
        val text = content?.trim().orEmpty()
        if (text.isNotEmpty() && text != "{}") {
            val inbound = parseOutbounds(text)
            if (inbound.size != 1) error("The content must describe exactly one node, got ${inbound.size}")
            profile.putOutbound(inbound.single())
        }
        val renamed = name?.trim().orEmpty()
        if (renamed.isNotEmpty() && renamed != profile.displayName()) {
            val outbound = profile.outbound
            outbound.name = renamed
            profile.putOutbound(outbound)
        }
        ProfileManager.updateProfile(profile)
        BboxBridge.scheduleRefresh(immediate = true)
        nodeInfo(profile.id)
    }

    /** Deletes one node from its group; the group itself stays. */
    suspend fun deleteNode(id: Long): Unit = withContext(Dispatchers.IO) {
        val profile = ProfileManager.getProfile(id) ?: error("No proxy with id $id")
        ProfileManager.deleteProfile(profile.groupId, id)
        BboxBridge.scheduleRefresh(immediate = true)
    }

    // ------------------------------------------------------------------------------------- auto selector

    /** Creates a fresh auto-selector node in [groupId], or the first group when it is omitted. */
    suspend fun createAutoSelector(groupId: Long?): Map<String, Any?> = withContext(Dispatchers.IO) {
        val gid = groupId?.takeIf { it > 0L } ?: GroupRepo.all().firstOrNull()?.id ?: error("No proxy group")
        val entity = ProfileManager.addProfile(AutoSelector(), gid)
        BboxBridge.scheduleRefresh(immediate = true)
        autoSelectorInfo(entity.id)
    }

    /** The auto-selector editor's seed: its fields plus the groups the picker can tick. */
    suspend fun autoSelectorInfo(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val selector = AutoSelectorProfiles.load(id) ?: error("No auto selector with id $id")
        mapOf(
            "id" to id,
            "name" to selector.name,
            "nameFilter" to selector.nameFilter,
            "countryFilter" to selector.countryFilter,
            "excludeUnavailable" to selector.excludeUnavailable,
            "poolCap" to selector.poolCap,
            "buildLimit" to selector.buildLimit,
            "resultValidityMins" to selector.resultValidityMins,
            "testURL" to selector.testURL,
            "connectivityURL" to selector.connectivityURL,
            "intervalSec" to selector.intervalSec,
            "benchIntervalSec" to selector.benchIntervalSec,
            "watchIntervalSec" to selector.watchIntervalSec,
            "activeSize" to selector.activeSize,
            "sampling" to selector.sampling,
            "toleranceMs" to selector.toleranceMs,
            "maxRTTms" to selector.maxRTTms,
            "expected" to selector.expected,
            "dialRetries" to selector.dialRetries,
            "interruptOnSwitch" to selector.interruptOnSwitch,
            "balance" to selector.balance,
            "balanceMode" to selector.balanceMode,
            "balanceIntervalSec" to selector.balanceIntervalSec,
            "gids" to selector.gids.map(Long::toInt),
            "pinnedId" to selector.pinnedID,
            "groups" to GroupRepo.all().map { mapOf("id" to it.id.toInt(), "name" to it.displayName()) },
        )
    }

    /** Saves the edited fields onto the stored selector; absent keys keep their values. */
    suspend fun autoSelectorSave(id: Long, values: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val selector = AutoSelectorProfiles.load(id) ?: error("No auto selector with id $id")
        (values["name"] as? String)?.let { selector.name = it.trim() }
        (values["nameFilter"] as? String)?.let { selector.nameFilter = it.trim() }
        (values["countryFilter"] as? String)?.let { selector.countryFilter = it.trim() }
        (values["testURL"] as? String)?.let { selector.testURL = it.trim() }
        (values["connectivityURL"] as? String)?.let { selector.connectivityURL = it.trim() }
        (values["balanceMode"] as? String)?.let { selector.balanceMode = it.trim() }
        (values["excludeUnavailable"] as? Boolean)?.let { selector.excludeUnavailable = it }
        (values["interruptOnSwitch"] as? Boolean)?.let { selector.interruptOnSwitch = it }
        (values["balance"] as? Boolean)?.let { selector.balance = it }
        fun intOf(key: String) = (values[key] as? Number)?.toInt() ?: (values[key] as? String)?.toIntOrNull()
        intOf("poolCap")?.let { selector.poolCap = it.coerceAtLeast(0) }
        intOf("buildLimit")?.let { selector.buildLimit = it.coerceAtLeast(0) }
        intOf("resultValidityMins")?.let { selector.resultValidityMins = it.coerceAtLeast(0) }
        intOf("intervalSec")?.let { selector.intervalSec = it.coerceAtLeast(1) }
        intOf("benchIntervalSec")?.let { selector.benchIntervalSec = it.coerceAtLeast(1) }
        intOf("watchIntervalSec")?.let { selector.watchIntervalSec = it.coerceAtLeast(1) }
        intOf("activeSize")?.let { selector.activeSize = it.coerceAtLeast(1) }
        intOf("sampling")?.let { selector.sampling = it.coerceAtLeast(1) }
        intOf("toleranceMs")?.let { selector.toleranceMs = it.coerceAtLeast(0) }
        intOf("maxRTTms")?.let { selector.maxRTTms = it.coerceAtLeast(0) }
        intOf("expected")?.let { selector.expected = it.coerceAtLeast(1) }
        intOf("dialRetries")?.let { selector.dialRetries = it.coerceAtLeast(0) }
        intOf("balanceIntervalSec")?.let { selector.balanceIntervalSec = it.coerceAtLeast(1) }
        (values["gids"] as? List<*>)?.let { list ->
            selector.gids = list.filterIsInstance<Number>().mapTo(ArrayList()) { it.toLong() }
        }
        AutoSelectorProfiles.save(id, selector)
        BboxBridge.scheduleRefresh(immediate = true)
        autoSelectorInfo(id)
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

    /** Throne's own clipboard/file/QR parser, with the same global settings the native importer reads. */
    private fun parseOutbounds(text: String): List<Outbound> {
        val parsed = ProfileImport.parse(
            text,
            SettingsMapper.xrayVlessPreference(),
            SettingsMapper.buildContext(),
        )
        if (parsed.outbounds.isEmpty()) {
            error(parsed.messages.joinToString("\n").ifBlank { "No supported proxy node found" })
        }
        return parsed.outbounds
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
