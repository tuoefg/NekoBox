package io.throne.bbox.bridge

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SendHwid
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.SubHttpVersion
import io.nekohasekai.sagernet.database.SubTlsVersion
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorRuntime
import io.nekohasekai.sagernet.group.RequestIdentity
import io.nekohasekai.sagernet.group.SubscriptionNameFilter
import io.nekohasekai.sagernet.group.SubscriptionQueue
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.common.MUX_CUSTOM
import io.nekohasekai.sagernet.outbound.common.Multiplex
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.common.XRAY_MUX_MAX_COUNT
import io.nekohasekai.sagernet.outbound.common.XrayMultiplex
import io.nekohasekai.sagernet.outbound.common.xrayXudpProxyUDP443Modes
import io.nekohasekai.sagernet.outbound.import.ProfileImport
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.ui.settings.SettingChecks
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // ------------------------------------------------------------------------------------------------ group settings

    /** The fetcher reads content:// URLs (a picked file) too, and those carry no host — the native form's rule. */
    private fun subscriptionUrlError(text: String): String {
        if (text.startsWith("content://", ignoreCase = true)) return ""
        return Validation.url(text, SettingChecks.HTTP, Empty.Required)
    }

    /**
     * Everything the group settings form renders, in one answer: the row itself, the choices the
     * menus offer (front/landing candidates, routing profiles), and the global defaults the
     * "Keep default" overrides resolve to.
     */
    suspend fun groupSettings(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val group = GroupRepo.get(id) ?: error("No such group")
        // dialog_edit_group.cpp:42-49: a front/landing profile that no longer exists is reset and saved on open.
        var changed = false
        if (group.frontProxyId > 0 && ProfileManager.getProfile(group.frontProxyId) == null) {
            group.frontProxyId = -1L; changed = true
        }
        if (group.landingProxyId > 0 && ProfileManager.getProfile(group.landingProxyId) == null) {
            group.landingProxyId = -1L; changed = true
        }
        if (changed) GroupRepo.save(group)

        val identity = RequestIdentity.resolve(null)
        val device = identity.device
        val options = group.subOptions
        // "Default (<global profile>)", then every routing profile Android can use, this subscription's own marked.
        val routeChoices = arrayListOf(
            listOf("-1", app.getString(R.string.grp_route_profile_default, RouteManager.current().name))
        )
        for (route in RouteManager.usable()) {
            val name = route.name.ifBlank { "#" + route.id }
            val label = if (id > 0 && route.source_gid == id) {
                app.getString(R.string.grp_route_profile_from_subscription, name)
            } else name
            routeChoices.add(listOf(route.id.toString(), label))
        }
        val initialRoute = group.routeId.toString().takeIf { picked -> routeChoices.any { it[0] == picked } } ?: "-1"

        // The front/landing picker's menu: every profile the fixed slots accept, auto selectors refused
        // (dialog_edit_group.cpp:63 — they move server on their own).
        val candidates = GroupRepo.all().flatMap { candidateGroup ->
            ProfileManager.members(candidateGroup.id)
                .filter { it.type != AutoSelectorProfiles.TYPE }
                .map { listOf(it.id.toString(), "[" + candidateGroup.displayName() + "] " + it.displayName()) }
        }

        mapOf(
            "id" to group.id,
            "type" to if (group.isSubscription) "subscription" else "basic",
            "name" to group.name,
            "url" to group.url,
            "skipAutoUpdate" to group.skipAutoUpdate,
            "autoClearUnavailable" to group.autoClearUnavailable,
            "frontProxyId" to group.frontProxyId,
            "landingProxyId" to group.landingProxyId,
            "candidates" to candidates,
            "routeId" to initialRoute,
            "routeChoices" to routeChoices,
            "importSubRoute" to group.importSubRoute,
            "sortMethod" to group.sortMethod,
            "sortDescending" to group.sortDescending,
            "testSortBy" to group.testSortBy,
            "trafficSortBy" to group.trafficSortBy,
            "testItemsToShow" to group.testItemsToShow,
            "displayOrder" to group.displayOrder,
            "options" to mapOf(
                "userAgent" to options.userAgent,
                "tlsVersion" to options.tlsVersion?.value,
                "httpVersion" to options.httpVersion?.value,
                "sendHwid" to options.sendHwid.value,
                "hwid" to options.hwid,
                "hwidOs" to options.hwidOs,
                "hwidOsVersion" to options.hwidOsVersion,
                "hwidModel" to options.hwidModel,
                "updateInterval" to options.updateInterval,
                // 0 keeps the global setting, 1 uses what the server said, 2 ignores it.
                "respectServerInterval" to when (options.respectServerInterval) {
                    null -> 0; true -> 1; false -> 2
                },
                "keepWorking" to options.keepWorking,
                "removeDuplicates" to options.removeDuplicates,
                "removeInsecure" to options.removeInsecure,
                "removeInvalid" to options.removeInvalid,
                "urlTest" to options.urlTest,
                "removeUnavailable" to options.removeUnavailable,
                "sortByLatency" to options.sortByLatency,
                "nameInclude" to options.nameInclude,
                "nameExclude" to options.nameExclude,
                "showInfoCard" to options.showInfoCard,
            ),
            "defaults" to mapOf(
                "userAgent" to identity.userAgent,
                "hwid" to device.hwid, "hwidOs" to device.os,
                "hwidOsVersion" to device.osVersion, "hwidModel" to device.model,
                "sendHwid" to identity.sendHwid,
                "tlsVersion" to identity.tlsVersion.value,
                "httpVersion" to identity.httpVersion.value,
                "autoUpdate" to DataStore.subAutoUpdate,
                "respectServerInterval" to DataStore.subRespectServerInterval,
            ),
            "serverIntervalHours" to group.subInfo.serverInterval,
        )
    }

    /**
     * Saves the group settings form, with the native editor's gates and consequences: the same
     * validation (subscription URL, name-filter patterns, update interval, header values), a URL
     * change drops what the old server said, a basic group keeps no URL, a changed routing profile
     * of the running group reloads the service.
     */
    suspend fun saveGroupSettings(values: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val id = (values["id"] as? Number)?.toLong() ?: 0L
        val group = GroupRepo.get(id) ?: error("No such group")
        @Suppress("UNCHECKED_CAST")
        val sent = values["options"] as? Map<String, Any?> ?: emptyMap()

        val type = values["type"] as? String ?: if (group.isSubscription) "subscription" else "basic"
        // The form always sends url; an absent key keeps the stored one rather than clearing it.
        val url = if (values.containsKey("url")) (values["url"] as? String)?.trim().orEmpty() else group.url
        val newUrl = if (type == "subscription") url else ""
        subscriptionUrlError(newUrl).takeIf { it.isNotEmpty() }?.let { error(it) }

        fun text(key: String) = (sent[key] as? String).orEmpty().trim()
        fun flag(key: String) = sent[key] as? Boolean ?: false
        fun number(key: String) = (sent[key] as? Number)?.toInt()

        // The Advanced checks, which the native form runs while collapsed too.
        val identity = RequestIdentity.resolve(null)
        val globalSendHwid = identity.sendHwid
        val mode = SendHwid.of(number("sendHwid") ?: SendHwid.KEEP_DEFAULT.value)
        val hwidSent = mode == SendHwid.ON || (mode == SendHwid.KEEP_DEFAULT && globalSendHwid)
        SettingChecks.headerValue(text("userAgent")).takeIf { it.isNotEmpty() }?.let { error(it) }
        if (hwidSent) for (key in listOf("hwid", "hwidOs", "hwidOsVersion", "hwidModel")) {
            SettingChecks.headerValue(text(key)).takeIf { it.isNotEmpty() }?.let { error(it) }
        }
        SettingChecks.groupUpdateInterval(text("updateInterval")).takeIf { it.isNotEmpty() }?.let { error(it) }
        for (key in listOf("nameInclude", "nameExclude")) {
            SubscriptionNameFilter.error(text(key))?.let { error("Invalid regular expression: $it") }
        }

        // dialog_edit_group.cpp accept: what the old URL's server said no longer applies.
        if (group.url != newUrl) {
            group.subMetadata = SubUserInfo()
            group.subLastUpdate = 0L
            group.info = ""
        }
        (values["name"] as? String)?.let { group.name = it.trim() }
        group.url = newUrl
        group.autoClearUnavailable = values["autoClearUnavailable"] as? Boolean ?: group.autoClearUnavailable
        group.skipAutoUpdate = values["skipAutoUpdate"] as? Boolean ?: group.skipAutoUpdate
        group.subOptions = SubscriptionOptions(
            userAgent = text("userAgent"),
            tlsVersion = number("tlsVersion")?.let(SubTlsVersion::of),
            httpVersion = number("httpVersion")?.let(SubHttpVersion::of),
            sendHwid = mode,
            hwid = text("hwid"),
            hwidOs = text("hwidOs"),
            hwidOsVersion = text("hwidOsVersion"),
            hwidModel = text("hwidModel"),
            updateInterval = (text("updateInterval").toIntOrNull() ?: 0).takeIf { it > 0 } ?: 0,
            respectServerInterval = when (number("respectServerInterval")) { 1 -> true; 2 -> false; else -> null },
            keepWorking = flag("keepWorking"),
            removeDuplicates = flag("removeDuplicates"),
            removeInsecure = flag("removeInsecure"),
            removeInvalid = flag("removeInvalid"),
            urlTest = flag("urlTest"),
            removeUnavailable = flag("removeUnavailable"),
            sortByLatency = flag("sortByLatency"),
            nameInclude = text("nameInclude"),
            nameExclude = text("nameExclude"),
            showInfoCard = sent["showInfoCard"] as? Boolean ?: true,
        )
        group.frontProxyId = (values["frontProxyId"] as? Number)?.toLong() ?: group.frontProxyId
        group.landingProxyId = (values["landingProxyId"] as? Number)?.toLong() ?: group.landingProxyId
        // A routing profile the menus did not offer (a dangling one) is kept while the choice stands.
        val routeChoices = groupSettingsRouteChoices(group.id)
        val initialRoute = group.routeId.toString().takeIf { picked -> routeChoices.any { it[0] == picked } } ?: "-1"
        val pickedRoute = values["routeId"] as? String ?: initialRoute
        if (pickedRoute != initialRoute) group.routeId = pickedRoute.toLongOrNull()?.takeIf { it > 0 } ?: -1L
        group.importSubRoute = newUrl.isNotEmpty() && flag("importSubRoute")
        (values["sortMethod"] as? Number)?.let { group.sortMethod = it.toInt() }
        (values["sortDescending"] as? Boolean)?.let { group.sortDescending = it }
        (values["testSortBy"] as? Number)?.let { group.testSortBy = it.toInt() }
        (values["trafficSortBy"] as? Number)?.let { group.trafficSortBy = it.toInt() }
        (values["testItemsToShow"] as? Number)?.let { group.testItemsToShow = it.toInt() }

        // dialog_edit_group.cpp: the running profile of this group now resolves to another routing profile.
        val running = RouteManager.runningRouteId()
        val restart = running > 0L &&
            ProfileManager.getProfile(ProfileManager.runningProfileId())?.groupId == group.id &&
            RouteManager.resolveRouteId(group.routeId) != running
        GroupRepo.save(group)
        SubscriptionScheduler.schedule()
        if (restart && DataStore.serviceState.canStop) SagerNet.reloadService()
        mapOf("restartNeeded" to restart)
    }

    /** The routing-profile choices of [saveGroupSettings], recomputed the way [groupSettings] built them. */
    private fun groupSettingsRouteChoices(id: Long): List<List<String>> {
        val choices = arrayListOf(
            listOf("-1", app.getString(R.string.grp_route_profile_default, RouteManager.current().name))
        )
        for (route in RouteManager.usable()) {
            choices.add(listOf(route.id.toString(), route.name.ifBlank { "#" + route.id }))
        }
        return choices
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

    /** The typed form's seed: the node's canonical sing-box JSON (ProfileJson), which the form reads and writes. */
    suspend fun nodeConfig(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val profile = ProfileManager.getProfile(id) ?: error("No proxy with id $id")
        mapOf(
            "id" to profile.id,
            "name" to profile.displayName(),
            "json" to io.nekohasekai.sagernet.ui.json.ProfileJson.text(profile.outbound),
        )
    }

    /**
     * Renames a node and/or replaces its outbound with one share link or JSON document. The content
     * must describe exactly one node — a whole subscription belongs to a group save, not here. The
     * parsed outbound is validated with the model's own checks (the native editor's save gate).
     */
    suspend fun updateNode(id: Long, name: String?, content: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        val profile = ProfileManager.getProfile(id) ?: error("No proxy with id $id")
        val text = content?.trim().orEmpty()
        if (text.isNotEmpty() && text != "{}") {
            val inbound = parseOutbounds(text)
            if (inbound.size != 1) error("The content must describe exactly one node, got ${inbound.size}")
            val outbound = inbound.single()
            val issues = ArrayList<io.nekohasekai.sagernet.validation.Issue>()
            outbound.validate(issues, SettingsMapper.buildContext())
            issues.filter { it.severity == io.nekohasekai.sagernet.validation.Severity.Error }
                .firstOrNull()?.let { issue ->
                    error(listOf(issue.field, issue.message).filter { it.isNotBlank() }.joinToString(": "))
                }
            profile.putOutbound(outbound)
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

    /** The running selector's status (AutoSelectorStatus.toJson); the phase is "idle" when nothing runs. */
    suspend fun autoSelectorStatus(): String = withContext(Dispatchers.IO) {
        AutoSelectorRuntime.statusJson(withMembers = true)
    }

    /**
     * Pins the selector to one member profile ([memberId] < 0 returns to automatic). The choice is
     * stored first so the next build carries it, then handed to the core when one is running.
     */
    suspend fun autoSelectorPin(id: Long, memberId: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val selector = AutoSelectorProfiles.load(id) ?: error("No auto selector with id $id")
        AutoSelectorProfiles.writes.withLock {
            if (selector.pinnedID != memberId) {
                selector.pinnedID = memberId
                AutoSelectorProfiles.save(id, selector)
            }
        }
        AutoSelectorRuntime.select(memberId)
        autoSelectorInfo(id)
    }

    /** Re-probes every member of the running selector now. */
    suspend fun autoSelectorRecheck(): Unit = withContext(Dispatchers.IO) {
        AutoSelectorRuntime.recheck()
    }

    // ---------------------------------------------------------------------------------- node tuning

    private fun intOf(values: Map<String, Any?>, key: String): Int? =
        (values[key] as? Number)?.toInt() ?: (values[key] as? String)?.toIntOrNull()

    /**
     * The node's own mux, fragment and TLS-tricks overrides. Mux state is the Keep Default (0) /
     * On (1) / Off (2) / Custom (3) menu; the fragment and trick states are 0 default, 1 on, 2 off.
     * A protocol without the field reports false in `hasMux`/`hasTls`.
     */
    suspend fun nodeTuningInfo(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val profile = ProfileManager.getProfile(id) ?: error("No proxy with id $id")
        val outbound = profile.outbound
        val muxField = outbound.javaClass.fields.firstOrNull { it.name == "multiplex" }
        val tlsField = outbound.javaClass.fields.firstOrNull { it.name == "tls" }
        val hasMux = muxField?.type == Multiplex::class.java
        val hasXrayMux = muxField?.type == XrayMultiplex::class.java
        val hasTls = tlsField?.type == Tls::class.java
        val mux = outbound.getMux()
        val xmux = outbound.getXrayMultiplex()
        val tls = outbound.getTls()
        mapOf(
            "id" to id,
            "hasMux" to hasMux,
            "hasXrayMux" to hasXrayMux,
            "hasTls" to hasTls,
            "muxState" to mux.getMuxState(),
            "muxProtocol" to mux.protocol,
            "muxMaxStreams" to mux.max_streams,
            "muxMaxConnections" to mux.max_connections,
            "muxMinStreams" to mux.min_streams,
            "muxPadding" to mux.padding,
            "muxBrutal" to mux.brutal.enabled,
            "muxBrutalUp" to mux.brutal.up_mbps,
            "muxBrutalDown" to mux.brutal.down_mbps,
            "xrayMuxState" to xmux.getMuxState(),
            "xrayMuxConcurrency" to xmux.concurrency,
            "xrayMuxMaxConnections" to xmux.maxConnections,
            "xrayMuxXudpConcurrency" to xmux.xudpConcurrency,
            "xrayMuxXudpProxyUDP443" to xmux.xudpProxyUDP443,
            "fragmentState" to when {
                tls.fragment_unspecified -> 0
                tls.fragment -> 1
                else -> 2
            },
            "fragmentFallbackDelay" to tls.fragment_fallback_delay,
            "recordFragment" to tls.record_fragment,
            "tlsTricksState" to when {
                tls.tls_tricks_unspecified -> 0
                tls.tls_tricks -> 1
                else -> 2
            },
        )
    }

    /** Writes the edited overrides back onto the outbound; absent keys keep their values. */
    suspend fun nodeTuningSave(id: Long, values: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val profile = ProfileManager.getProfile(id) ?: error("No proxy with id $id")
        val outbound = profile.outbound
        val muxField = outbound.javaClass.fields.firstOrNull { it.name == "multiplex" }
        val mux = outbound.getMux()
        val xmux = outbound.getXrayMultiplex()
        val tls = outbound.getTls()
        intOf(values, "muxState")?.let { state ->
            mux.saveMuxState(state)
            if (state == MUX_CUSTOM) {
                (values["muxProtocol"] as? String)?.let { mux.protocol = it.trim() }
                intOf(values, "muxMaxStreams")?.let { mux.max_streams = it.coerceAtLeast(0) }
                intOf(values, "muxMaxConnections")?.let { mux.max_connections = it.coerceAtLeast(0) }
                intOf(values, "muxMinStreams")?.let { mux.min_streams = it.coerceAtLeast(0) }
                (values["muxPadding"] as? Boolean)?.let { mux.padding = it }
                (values["muxBrutal"] as? Boolean)?.let { mux.brutal.enabled = it }
                intOf(values, "muxBrutalUp")?.let { mux.brutal.up_mbps = it.coerceAtLeast(0) }
                intOf(values, "muxBrutalDown")?.let { mux.brutal.down_mbps = it.coerceAtLeast(0) }
            }
        }
        if (muxField?.type == XrayMultiplex::class.java) intOf(values, "xrayMuxState")?.let { state ->
            xmux.saveMuxState(state)
            if (state == MUX_CUSTOM) {
                intOf(values, "xrayMuxConcurrency")?.let { xmux.concurrency = it.coerceIn(-1, XRAY_MUX_MAX_COUNT) }
                intOf(values, "xrayMuxMaxConnections")?.let { xmux.maxConnections = it.coerceIn(-1, XRAY_MUX_MAX_COUNT) }
                intOf(values, "xrayMuxXudpConcurrency")?.let { xmux.xudpConcurrency = it.coerceIn(-1, XRAY_MUX_MAX_COUNT) }
                (values["xrayMuxXudpProxyUDP443"] as? String)?.let { value ->
                    val mode = value.trim()
                    if (mode.isEmpty() || mode in xrayXudpProxyUDP443Modes) xmux.xudpProxyUDP443 = mode
                }
            }
        }
        intOf(values, "fragmentState")?.let { state ->
            tls.saveFragmentState(state)
            (values["fragmentFallbackDelay"] as? String)?.let { tls.fragment_fallback_delay = it.trim() }
            (values["recordFragment"] as? Boolean)?.let { tls.record_fragment = it }
        }
        intOf(values, "tlsTricksState")?.let { state ->
            tls.tls_tricks = state == 1
            tls.tls_tricks_unspecified = state == 0
        }
        profile.putOutbound(outbound)
        ProfileManager.updateProfile(profile)
        BboxBridge.scheduleRefresh(immediate = true)
        nodeTuningInfo(id)
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
        val allowed = setOf("name", "url", "autoUpdate", "updateIntervalMinutes", "nodes")
        for (key in document.keys()) {
            if (key !in allowed) {
                error("\"$key\" is not part of the configuration; DNS, routing rules and log settings are app settings")
            }
        }
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
