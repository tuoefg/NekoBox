package io.throne.bbox.bridge

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Route profiles: the named rule sets a configuration is built with, one of them the global choice.
 *
 * The editor speaks `prefix:value -> outbound` lines for the simple address rules (the same shape
 * [RouteProfile.appendSimpleAddressRule] accepts), one line per rule. Rules of other kinds are left
 * in place untouched — the text is a view of the simple subset, not the profile's whole rules list.
 */
object BboxRoutes {

    private val addressTypes = setOf(
        RuleType.SIMPLE_ADDRESS_PROXY.id,
        RuleType.SIMPLE_ADDRESS_BYPASS.id,
        RuleType.SIMPLE_ADDRESS_BLOCK.id,
        RuleType.SIMPLE_ADDRESS_WARP_BYPASS.id,
    )

    private val addressPrefixes = listOf("domain", "suffix", "keyword", "regex", "ruleset", "ip")

    private fun outboundName(rule: RouteRule) = when (rule.outbound_id) {
        OutboundIds.PROXY -> "proxy"
        OutboundIds.BLOCK -> "block"
        OutboundIds.WARP_BYPASS -> "warp-bypass"
        else -> "direct"
    }

    private fun outboundId(name: String) = when (name.trim().lowercase()) {
        "proxy" -> OutboundIds.PROXY
        "block" -> OutboundIds.BLOCK
        "warp-bypass", "warp_bypass" -> OutboundIds.WARP_BYPASS
        "direct" -> OutboundIds.DIRECT
        else -> error("Unknown outbound \"$name\"; use proxy, direct, block or warp-bypass")
    }

    /** The first address the rule carries as `prefix` to `value`, `ip` to "" for an address-less rule. */
    private fun firstAddress(rule: RouteRule): Pair<String, String> {
        rule.domain.firstOrNull()?.let { return "domain" to it }
        rule.domain_suffix.firstOrNull()?.let { return "suffix" to it }
        rule.domain_keyword.firstOrNull()?.let { return "keyword" to it }
        rule.domain_regex.firstOrNull()?.let { return "regex" to it }
        rule.rule_set.firstOrNull()?.let { return "ruleset" to it }
        rule.ip_cidr.firstOrNull()?.let { return "ip" to it }
        return "ip" to ""
    }

    /** `prefix:value -> outbound`, one line per value the simple address rules hold. */
    private fun simpleRulesText(profile: RouteProfile): String = buildString {
        for (rule in profile.rules) {
            if (rule.type !in addressTypes) continue
            val outbound = outboundName(rule)
            val values = listOf(
                "domain" to rule.domain, "suffix" to rule.domain_suffix,
                "keyword" to rule.domain_keyword, "regex" to rule.domain_regex,
                "ruleset" to rule.rule_set, "ip" to rule.ip_cidr,
            )
            for ((prefix, list) in values) {
                for (value in list) {
                    if (isNotEmpty()) append('\n')
                    append(prefix, ':', value, " -> ", outbound)
                }
            }
        }
    }

    /** Every profile as a list row; the rules themselves arrive only through [get]. */
    suspend fun list(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val current = DataStore.currentRouteId
        val profiles = RouteManager.all().map { p ->
            mapOf(
                "id" to p.id,
                "name" to p.name,
                "current" to (p.id == current),
                "isRemote" to p.is_remote,
                "isRaw" to p.is_raw,
                "ruleCount" to p.rules.size,
                "simpleRuleCount" to p.rules.count { it.type in addressTypes },
            )
        }
        mapOf("currentId" to current, "profiles" to profiles)
    }

    /** The editor's seed: the profile's fields and its simple address rules as text. */
    suspend fun get(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val p = if (id > 0L) RouteManager.get(id) ?: error("No route profile with id $id") else RouteProfile()
        mapOf(
            "id" to p.id,
            "name" to p.name,
            "defaultOutbound" to when (p.default_outbound_id) {
                OutboundIds.PROXY -> "proxy"
                OutboundIds.BLOCK -> "block"
                OutboundIds.WARP_BYPASS -> "warp-bypass"
                else -> "direct"
            },
            "isRemote" to p.is_remote,
            "remoteUrl" to p.remote_url,
            "autoUpdate" to p.auto_update,
            "isRaw" to p.is_raw,
            "rules" to simpleRulesText(p),
            "ruleCount" to p.rules.size,
        )
    }

    /**
     * Creates or updates a profile. `rules` is the simple address subset as text; the profile's
     * other rules survive an edit, and an unparsable line rejects the whole save.
     */
    suspend fun save(values: Map<String, Any?>): Long = withContext(Dispatchers.IO) {
        val id = (values["id"] as? Number)?.toLong() ?: 0L
        val p = (if (id > 0L) RouteManager.get(id) else null) ?: RouteProfile()
        if (p.is_raw) error("This profile is read-only")
        (values["name"] as? String)?.let { p.name = it.trim() }
        (values["defaultOutbound"] as? String)?.let { p.default_outbound_id = outboundId(it) }
        (values["isRemote"] as? Boolean)?.let { p.is_remote = it }
        (values["remoteUrl"] as? String)?.let { p.remote_url = it.trim() }
        (values["autoUpdate"] as? Boolean)?.let { p.auto_update = it }
        val rules = values["rules"] as? String
        if (rules != null) {
            p.rules.removeAll { it.type in addressTypes }
            for (line in rules.lineSequence()) {
                val text = line.trim()
                if (text.isEmpty()) continue
                val arrow = text.lastIndexOf("->")
                val rawLine = (if (arrow > 0) text.substring(0, arrow) else text).trim()
                val target = (if (arrow > 0) text.substring(arrow + 2) else "direct").trim()
                if (!p.appendSimpleAddressRule(rawLine, outboundId(target))) {
                    error("Bad rule line: $text")
                }
            }
        }
        val saved = RouteManager.save(p)
        BboxBridge.scheduleRefresh(immediate = true)
        saved
    }

    /** Refuses the last profile and raw ones; the current profile moves to the first remaining. */
    suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        if (!RouteManager.delete(id)) error("Cannot delete this route profile")
        BboxBridge.scheduleRefresh(immediate = true)
    }

    suspend fun setCurrent(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val p = RouteManager.get(id)?.takeIf { !it.is_raw } ?: error("No usable route profile with id $id")
        DataStore.currentRouteId = p.id
        BboxBridge.scheduleRefresh(immediate = true)
        mapOf("currentId" to p.id)
    }

    // ------------------------------------------------------------------------------------------------ rules

    private fun str(map: Map<String, Any?>, key: String) = map[key]?.toString().orEmpty()

    /** One rule as the editor's map: the match attributes, the action fields and the metadata. */
    private fun ruleToMap(rule: RouteRule): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["name"] = rule.name
        map["type"] = RuleType.values().firstOrNull { it.id == rule.type }?.token ?: RuleType.CUSTOM.token
        map["disabled"] = rule.disabled
        map["action"] = rule.action
        map["outbound_id"] = rule.outbound_id
        for (field in RouteRule.STRING_FIELDS) map[field.name] = field.get(rule)
        for (field in RouteRule.LIST_FIELDS) map[field.name] = field.get(rule).toList()
        for (field in RouteRule.BOOL_FIELDS) map[field.name] = field.get(rule)
        return map
    }

    private fun ruleFromMap(map: Map<String, Any?>): RouteRule {
        val rule = RouteRule()
        rule.name = str(map, "name")
        rule.type = RuleType.values().firstOrNull { it.token == str(map, "type") }?.id ?: RuleType.CUSTOM.id
        rule.disabled = map["disabled"] == true
        rule.action = str(map, "action").ifBlank { "route" }
        rule.outbound_id = (map["outbound_id"] as? Number)?.toLong() ?: OutboundIds.DIRECT
        for (field in RouteRule.STRING_FIELDS) field.set(rule, str(map, field.name))
        for (field in RouteRule.LIST_FIELDS) {
            field.set(rule, ArrayList((map[field.name] as? List<*>)?.filterIsInstance<String>().orEmpty()))
        }
        for (field in RouteRule.BOOL_FIELDS) field.set(rule, map[field.name] == true)
        return rule
    }

    /** Every rule of the profile as an editor map, in list order. */
    suspend fun rules(id: Long): Map<String, Any?> = withContext(Dispatchers.IO) {
        val p = RouteManager.get(id) ?: error("No route profile with id $id")
        mapOf("rules" to p.rules.map(::ruleToMap))
    }

    /**
     * Replaces the profile's whole rules list — [RouteManager.save] stores rules in list order anyway,
     * so the editor sends the list it shows and an empty list is a profile with no rules.
     */
    suspend fun saveRules(id: Long, rules: List<Map<String, Any?>>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val p = RouteManager.get(id) ?: error("No route profile with id $id")
        if (p.is_raw) error("This profile is read-only")
        p.rules = rules.mapTo(ArrayList()) { ruleFromMap(it) }
        RouteManager.save(p)
        BboxBridge.scheduleRefresh(immediate = true)
        mapOf("rules" to p.rules.map(::ruleToMap))
    }
}
