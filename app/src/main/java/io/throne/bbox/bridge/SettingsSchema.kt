package io.throne.bbox.bridge

import android.content.Context
import android.os.PowerManager
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.database.DataStore
import kotlin.math.absoluteValue

/**
 * What has to happen after a write.
 *
 * Unlike the native Throne screens this runs head-less, so the three native levels are split here:
 * None          — takes effect immediately.
 * Reload        — the running core rebuilds its config (`Action.RELOAD`).
 * AppRestart    — only the process restart picks it up (log level, log buffer).
 */
enum class Apply { None, Reload, AppRestart }

class Entry(
    val bridgeKey: String,
    val apply: Apply = Apply.None,
    /** Restartable while running through `settings.set {"restart":true}`, like the three automatic ones upstream. */
    val autoRestartable: Boolean = false,
    val get: () -> Any?,
    val set: (Any?) -> Unit,
)

// Each helper comes in the shapes the entry list actually uses: with and without an apply level.
// One function with defaults would not do, because Kotlin fills positional arguments in order and
// never skips an optional one in the middle — `boolEntry(key, Apply.Reload, { get }, { set })` would
// hand the getter to `autoRestartable`.

private fun boolEntry(bridgeKey: String, get: () -> Boolean, set: (Boolean) -> Unit) =
    Entry(bridgeKey, Apply.None, false, { get() }, { set(it == true) })

private fun boolEntry(bridgeKey: String, apply: Apply, get: () -> Boolean, set: (Boolean) -> Unit) =
    Entry(bridgeKey, apply, false, { get() }, { set(it == true) })

private fun boolEntry(
    bridgeKey: String,
    apply: Apply,
    autoRestartable: Boolean,
    get: () -> Boolean,
    set: (Boolean) -> Unit,
) = Entry(bridgeKey, apply, autoRestartable, { get() }, { set(it == true) })

private fun intEntry(bridgeKey: String, get: () -> Int, set: (Int) -> Unit) =
    intEntry(bridgeKey, Apply.None, get, set)

private fun intEntry(
    bridgeKey: String,
    apply: Apply,
    get: () -> Int,
    set: (Int) -> Unit,
) = Entry(bridgeKey, apply, false, { get() }, {
    val value = when (it) {
        is Int -> it
        is Long -> it.toInt()
        is Double -> it.toInt()
        is String -> it.toIntOrNull() ?: return@Entry
        else -> return@Entry
    }
    set(value)
})

private fun textEntry(bridgeKey: String, get: () -> String, set: (String) -> Unit) =
    textEntry(bridgeKey, Apply.None, get, set)

private fun textEntry(
    bridgeKey: String,
    apply: Apply,
    get: () -> String,
    set: (String) -> Unit,
) = Entry(bridgeKey, apply, false, { get() }, { set(it?.toString() ?: "") })

/** A list stored as newline-separated text upstream; the UI edits it as a list of lines. */
private fun listEntry(bridgeKey: String, apply: Apply, get: () -> List<String>, set: (List<String>) -> Unit) =
    Entry(bridgeKey, apply, false, { get() }, {
        val lines = when (it) {
            is List<*> -> it.filterIsInstance<String>()
            is String -> it.lineSequence().toList()
            else -> return@Entry
        }
        set(lines.map(String::trim).filter(String::isNotEmpty))
    })

/** signed-coded minutes: negative = off. Exposed as an enabled flag plus a positive minutes value. */
private val Int.hoursPair get() = Pair(this > 0, this.absoluteValue)

object Settings {

    /** LAN / loopback are the two states of `inbound_address`; there is no separate boolean upstream. */
    private const val LAN_ADDRESS = "::"
    private const val LOOPBACK_ADDRESS = "127.0.0.1"

    val entries: List<Entry> = listOf(

        // --- General / service -------------------------------------------------------------------
        Entry(
            bridgeKey = "serviceMode",
            apply = Apply.Reload,
            autoRestartable = true,
            get = { DataStore.serviceMode },
            set = { DataStore.serviceMode = it?.toString() ?: Key.MODE_VPN },
        ),
        boolEntry("rememberEnable", get = { DataStore.rememberEnable }, set = { DataStore.rememberEnable = it }),
        boolEntry("hideFromRecentApps", get = { DataStore.hideFromRecentApps }, set = { DataStore.hideFromRecentApps = it }),
        boolEntry("meteredNetwork", Apply.Reload, true, { DataStore.meteredNetwork }, { DataStore.meteredNetwork = it }),
        boolEntry(
            "networkChangeResetConnections",
            get = { DataStore.networkChangeResetConnections },
            set = { DataStore.networkChangeResetConnections = it },
        ),
        boolEntry("wakeResetConnections", get = { DataStore.wakeResetConnections }, set = { DataStore.wakeResetConnections = it }),
        boolEntry("skipDeleteConfirmation", get = { DataStore.skipDeleteConfirmation }, set = { DataStore.skipDeleteConfirmation = it }),
        boolEntry("alwaysShowAddress", get = { DataStore.alwaysShowAddress }, set = { DataStore.alwaysShowAddress = it }),
        boolEntry("updateCheckAuto", get = { DataStore.updateCheckAuto }, set = { DataStore.updateCheckAuto = it }),
        // Starts the core even when a rule-set download failed, instead of refusing to start.
        // Upstream stores this one as a long, so it is surfaced as a plain switch here.
        Entry(
            bridgeKey = "startWithoutRuleSets",
            get = { DataStore.startWithoutRuleSets != 0L },
            set = { DataStore.startWithoutRuleSets = if (it == true) 1L else 0L },
        ),
        boolEntry("showDirectSpeed", get = { DataStore.showDirectSpeed }, set = { DataStore.showDirectSpeed = it }),
        boolEntry(
            "showGroupInNotification",
            get = { DataStore.showGroupInNotification },
            set = { DataStore.showGroupInNotification = it },
        ),
        boolEntry("allowBetaUpdate", get = { DataStore.allowBetaUpdate }, set = { DataStore.allowBetaUpdate = it }),

        // --- Inbound ------------------------------------------------------------------------------
        intEntry("inboundSocksPort", Apply.Reload, { DataStore.inboundSocksPort }, { DataStore.inboundSocksPort = it }),
        boolEntry("disableMixedInbound", Apply.Reload, true, { DataStore.disableMixedInbound }, { DataStore.disableMixedInbound = it }),
        boolEntry("randomInboundPort", Apply.Reload, { DataStore.randomInboundPort }, { DataStore.randomInboundPort = it }),
        boolEntry("inboundAuth", Apply.Reload, true, { DataStore.inboundAuth }, { DataStore.inboundAuth = it }),
        textEntry("inboundUser", Apply.Reload, { DataStore.inboundUser }, { DataStore.inboundUser = it }),
        textEntry("inboundPass", Apply.Reload, { DataStore.inboundPass }, { DataStore.inboundPass = it }),
        intEntry("coreDnsInPort", Apply.Reload, { DataStore.coreDnsInPort }, { DataStore.coreDnsInPort = it }),
        Entry(
            bridgeKey = "allowLan",
            apply = Apply.Reload,
            autoRestartable = true,
            get = { DataStore.allowLanAccess },
            set = { DataStore.inboundAddress = if (it == true) LAN_ADDRESS else LOOPBACK_ADDRESS },
        ),
        // The system HTTP proxy Throne hands to apps: only the VPN service can do this, and only
        // from API 29, which is why the UI gates the switch on `systemProxy`.
        boolEntry("appendHttpProxy", Apply.Reload, true, { DataStore.appendHttpProxy }, { DataStore.appendHttpProxy = it }),
        // Stored upstream as one newline-separated string, but edited here as the list of lines it is.
        listEntry(
            "httpProxyBypass",
            Apply.Reload,
            { DataStore.httpProxyBypass.lineSequence().filter(String::isNotBlank).toList() },
            { DataStore.httpProxyBypass = it.joinToString("\n") },
        ),
        textEntry("customInbound", Apply.Reload, { DataStore.customInbound }, { DataStore.customInbound = it }),

        // --- TUN ----------------------------------------------------------------------------------
        intEntry("vpnMtu", Apply.Reload, { DataStore.vpnMtu }, { DataStore.vpnMtu = it }),
        boolEntry("vpnIpv6", Apply.Reload, true, { DataStore.vpnIpv6 }, { DataStore.vpnIpv6 = it }),
        textEntry("vpnTunIpv4Cidr", Apply.Reload, { DataStore.vpnTunIpv4Cidr }, { DataStore.vpnTunIpv4Cidr = it }),
        textEntry("vpnTunIpv6Cidr", Apply.Reload, { DataStore.vpnTunIpv6Cidr }, { DataStore.vpnTunIpv6Cidr = it }),
        boolEntry(
            "disablePrivateRangeBypass",
            Apply.Reload,
            true,
            { DataStore.disablePrivateRangeBypass },
            { DataStore.disablePrivateRangeBypass = it },
        ),
        boolEntry("enableTunRouting", Apply.Reload, { DataStore.enableTunRouting }, { DataStore.enableTunRouting = it }),
        listEntry("vpnPrivateRanges", Apply.Reload, { DataStore.vpnPrivateRanges }, { DataStore.vpnPrivateRanges = it }),

        // --- Per-app proxy ------------------------------------------------------------------------

        // --- Routing ------------------------------------------------------------------------------
        textEntry(
            "outboundDomainStrategy",
            Apply.Reload,
            { DataStore.outboundDomainStrategy },
            { DataStore.outboundDomainStrategy = it },
        ),
        textEntry("domainStrategy", Apply.Reload, { DataStore.domainStrategy }, { DataStore.domainStrategy = it }),
        intEntry("rulesetMirror", Apply.Reload, { DataStore.rulesetMirror }, { DataStore.rulesetMirror = it }),
        boolEntry("adblockEnable", Apply.Reload, true, { DataStore.adblockEnable }, { DataStore.adblockEnable = it }),
        boolEntry("enableWarp", Apply.Reload, { DataStore.enableWarp }, { DataStore.enableWarp = it }),

        // --- WARP ---------------------------------------------------------------------------------
        // WireGuard and MASQUE keep separate key material; `warpMode` decides which set is used.
        textEntry("warpMode", Apply.Reload, { DataStore.warpMode }, { DataStore.warpMode = it }),
        textEntry("warpEndpoint", Apply.Reload, { DataStore.warpEp }, { DataStore.warpEp = it }),
        textEntry("warpPrivateKey", Apply.Reload, { DataStore.warpPrivateKey }, { DataStore.warpPrivateKey = it }),
        textEntry("warpPublicKey", Apply.Reload, { DataStore.warpPublicKey }, { DataStore.warpPublicKey = it }),
        listEntry("warpIfcAddrs", Apply.Reload, { DataStore.warpIfcAddrs }, { DataStore.warpIfcAddrs = it }),
        listEntry("warpReserved", Apply.Reload, { DataStore.warpReserved }, { DataStore.warpReserved = it }),
        textEntry("warpMasqueEndpoint", Apply.Reload, { DataStore.warpMasqueEp }, { DataStore.warpMasqueEp = it }),
        textEntry(
            "warpMasquePrivateKey",
            Apply.Reload,
            { DataStore.warpMasquePrivateKey },
            { DataStore.warpMasquePrivateKey = it },
        ),
        textEntry(
            "warpMasquePeerPublicKey",
            Apply.Reload,
            { DataStore.warpMasquePeerPublicKey },
            { DataStore.warpMasquePeerPublicKey = it },
        ),
        textEntry("warpMasqueSni", Apply.Reload, { DataStore.warpMasqueSni }, { DataStore.warpMasqueSni = it }),
        intEntry(
            "warpMasqueHttpMode",
            Apply.Reload,
            { DataStore.warpMasqueHttpMode },
            { DataStore.warpMasqueHttpMode = it },
        ),
        listEntry("warpApiHosts", Apply.Reload, { DataStore.warpApiHosts }, { DataStore.warpApiHosts = it }),
        boolEntry("warpTosAccepted", { DataStore.warpTosAccepted }, { DataStore.warpTosAccepted = it }),

        // --- DNS ----------------------------------------------------------------------------------
        textEntry("remoteDns", Apply.Reload, { DataStore.remoteDns }, { DataStore.remoteDns = it }),
        boolEntry("remoteDnsDisableIpv6", Apply.Reload, { DataStore.remoteDnsDisableIpv6 }, { DataStore.remoteDnsDisableIpv6 = it }),
        textEntry("directDns", Apply.Reload, { DataStore.directDns }, { DataStore.directDns = it }),
        boolEntry("directDnsDisableIpv6", Apply.Reload, { DataStore.directDnsDisableIpv6 }, { DataStore.directDnsDisableIpv6 = it }),
        textEntry("dnsFinalOut", Apply.Reload, { DataStore.dnsFinalOut }, { DataStore.dnsFinalOut = it }),
        boolEntry("enableDnsRouting", Apply.Reload, { DataStore.enableDnsRouting }, { DataStore.enableDnsRouting = it }),
        boolEntry("fakeDns", Apply.Reload, true, { DataStore.fakedns }, { DataStore.fakedns = it }),
        boolEntry("fakeipDisableIpv6", Apply.Reload, { DataStore.fakeipDisableIpv6 }, { DataStore.fakeipDisableIpv6 = it }),
        boolEntry("dnsUseHosts", Apply.Reload, { DataStore.dnsUseHosts }, { DataStore.dnsUseHosts = it }),
        boolEntry("dnsPredefinedEnable", Apply.Reload, { DataStore.dnsPredefinedEnable }, { DataStore.dnsPredefinedEnable = it }),
        listEntry("dnsPredefinedRules", Apply.Reload, { DataStore.dnsPredefinedRules }, { DataStore.dnsPredefinedRules = it }),
        boolEntry("dnsPersistCache", Apply.Reload, { DataStore.dnsPersistCache }, { DataStore.dnsPersistCache = it }),
        boolEntry("dnsReverseMapping", Apply.Reload, { DataStore.dnsReverseMapping }, { DataStore.dnsReverseMapping = it }),
        textEntry("dnsQueryTimeout", Apply.Reload, { DataStore.dnsQueryTimeout }, { DataStore.dnsQueryTimeout = it }),
        boolEntry("dnsOptimistic", Apply.Reload, { DataStore.dnsOptimistic }, { DataStore.dnsOptimistic = it }),
        textEntry(
            "dnsOptimisticTimeout",
            Apply.Reload,
            { DataStore.dnsOptimisticTimeout },
            { DataStore.dnsOptimisticTimeout = it },
        ),
        textEntry(
            "coreBoxUnderlyingDns",
            Apply.Reload,
            { DataStore.coreBoxUnderlyingDns },
            { DataStore.coreBoxUnderlyingDns = it },
        ),
        boolEntry("dnsDisableCache", Apply.Reload, { DataStore.dnsDisableCache }, { DataStore.dnsDisableCache = it }),
        boolEntry("dnsDisableExpire", Apply.Reload, { DataStore.dnsDisableExpire }, { DataStore.dnsDisableExpire = it }),
        intEntry("dnsCacheCapacity", Apply.Reload, { DataStore.dnsCacheCapacity }, { DataStore.dnsCacheCapacity = it }),

        // --- Core ---------------------------------------------------------------------------------
        textEntry("logLevel", Apply.AppRestart, { DataStore.logLevel }, { DataStore.logLevel = it }),
        boolEntry("enableStats", Apply.Reload, true, { DataStore.enableStats }, { DataStore.enableStats = it }),
        boolEntry("disableTrafficStats", Apply.Reload, true, { DataStore.disableTrafficStats }, { DataStore.disableTrafficStats = it }),
        intEntry("logBufSize", Apply.AppRestart, { DataStore.logBufSize }, { DataStore.logBufSize = it }),
        boolEntry("skipCert", Apply.Reload, { DataStore.skipCert }, { DataStore.skipCert = it }),
        boolEntry("enableNtp", Apply.Reload, true, { DataStore.enableNtp }, { DataStore.enableNtp = it }),
        textEntry("ntpServerAddress", Apply.Reload, { DataStore.ntpServerAddress }, { DataStore.ntpServerAddress = it }),
        intEntry("ntpServerPort", Apply.Reload, { DataStore.ntpServerPort }, { DataStore.ntpServerPort = it }),
        textEntry("ntpInterval", Apply.Reload, { DataStore.ntpInterval }, { DataStore.ntpInterval = it }),
        textEntry("ntpOutbound", Apply.Reload, { DataStore.ntpOutbound }, { DataStore.ntpOutbound = it }),
        textEntry("xrayLogLevel", Apply.Reload, { DataStore.xrayLogLevel }, { DataStore.xrayLogLevel = it }),
        boolEntry("useMozillaCerts", Apply.Reload, { DataStore.useMozillaCerts }, { DataStore.useMozillaCerts = it }),
        boolEntry("xrayUseSystemCa", Apply.Reload, { DataStore.xrayUseSystemCa }, { DataStore.xrayUseSystemCa = it }),
        textEntry("boxApiSecret", Apply.Reload, { DataStore.coreBoxApiSecret }, { DataStore.coreBoxApiSecret = it }),

        // Clash API / sing-box API: one signed port each, split into a flag and a port for the UI.
        Entry(
            bridgeKey = "clashApiEnabled",
            apply = Apply.Reload,
            get = { DataStore.clashApiEnabled },
            set = {
                val port = DataStore.coreBoxClashApi.absoluteValue.takeIf { value -> value > 0 } ?: 9090
                DataStore.coreBoxClashApi = if (it == true) port else -port
            },
        ),
        intEntry("clashApiPort", Apply.Reload, {
            DataStore.coreBoxClashApi.absoluteValue.takeIf { value -> value > 0 } ?: 9090
        }, { port -> DataStore.coreBoxClashApi = if (DataStore.clashApiEnabled) port else -port }),
        textEntry(
            "clashApiListenAddr",
            Apply.Reload,
            { DataStore.coreBoxClashListenAddr },
            { DataStore.coreBoxClashListenAddr = it },
        ),
        textEntry(
            "clashApiSecret",
            Apply.Reload,
            { DataStore.coreBoxClashApiSecret },
            { DataStore.coreBoxClashApiSecret = it },
        ),
        Entry(
            bridgeKey = "boxApiEnabled",
            apply = Apply.Reload,
            get = { DataStore.apiDashboardEnabled },
            set = {
                val port = DataStore.coreBoxApiPort.absoluteValue.takeIf { value -> value > 0 } ?: 9091
                DataStore.coreBoxApiPort = if (it == true) port else -port
            },
        ),
        intEntry("boxApiPort", Apply.Reload, {
            DataStore.coreBoxApiPort.absoluteValue.takeIf { value -> value > 0 } ?: 9091
        }, { port -> DataStore.coreBoxApiPort = if (DataStore.apiDashboardEnabled) port else -port }),

        // --- Testing ------------------------------------------------------------------------------
        textEntry("testUrl", { DataStore.testUrl }, { DataStore.testUrl = it }),
        intEntry("urlTestTimeoutMs", { DataStore.urlTestTimeoutMs }, { DataStore.urlTestTimeoutMs = it }),
        intEntry("testConcurrent", { DataStore.testConcurrent }, { DataStore.testConcurrent = it }),
        textEntry("directTestUrl", { DataStore.directTestUrl }, { DataStore.directTestUrl = it }),
        boolEntry("exitCountryLookup", { DataStore.exitCountryLookup }, { DataStore.exitCountryLookup = it }),
        textEntry("exitLookupProvider", { DataStore.exitLookupProvider }, { DataStore.exitLookupProvider = it }),
        intEntry("speedTestMode", { DataStore.speedTestMode }, { DataStore.speedTestMode = it }),
        intEntry("speedTestTimeoutMs", { DataStore.speedTestTimeoutMs }, { DataStore.speedTestTimeoutMs = it }),
        textEntry("simpleDlUrl", { DataStore.simpleDlUrl }, { DataStore.simpleDlUrl = it }),

        // --- Node presets -------------------------------------------------------------------------
        // Defaults the importer stamps on a node it builds; a running config is never rebuilt from
        // these, so the entries apply to the next import only.
        textEntry("muxProtocol", { DataStore.muxProtocol }, { DataStore.muxProtocol = it }),
        textEntry("muxMode", { DataStore.muxMode }, { DataStore.muxMode = it }),
        intEntry("muxConcurrency", { DataStore.muxConcurrency }, { DataStore.muxConcurrency = it }),
        intEntry("muxMaxConnections", { DataStore.muxMaxConnections }, { DataStore.muxMaxConnections = it }),
        intEntry("muxMinStreams", { DataStore.muxMinStreams }, { DataStore.muxMinStreams = it }),
        boolEntry("muxPadding", { DataStore.muxPadding }, { DataStore.muxPadding = it }),
        boolEntry("muxBrutal", { DataStore.muxBrutal }, { DataStore.muxBrutal = it }),
        intEntry("muxBrutalUpMbps", { DataStore.muxBrutalUpMbps }, { DataStore.muxBrutalUpMbps = it }),
        intEntry("muxBrutalDownMbps", { DataStore.muxBrutalDownMbps }, { DataStore.muxBrutalDownMbps = it }),
        boolEntry("muxDefaultOn", { DataStore.muxDefaultOn }, { DataStore.muxDefaultOn = it }),
        intEntry("xrayMuxConcurrency", { DataStore.xrayMuxConcurrency }, { DataStore.xrayMuxConcurrency = it }),
        intEntry("xrayMuxMaxConnections", { DataStore.xrayMuxMaxConnections }, { DataStore.xrayMuxMaxConnections = it }),
        intEntry("xrayMuxXudpConcurrency", { DataStore.xrayMuxXudpConcurrency }, { DataStore.xrayMuxXudpConcurrency = it }),
        textEntry("xrayMuxXudpProxyUdp443", { DataStore.xrayMuxXudpProxyUdp443 }, { DataStore.xrayMuxXudpProxyUdp443 = it }),
        boolEntry("xrayMuxDefaultOn", { DataStore.xrayMuxDefaultOn }, { DataStore.xrayMuxDefaultOn = it }),
        boolEntry("fragmentDefaultOn", { DataStore.fragmentDefaultOn }, { DataStore.fragmentDefaultOn = it }),
        textEntry("fragmentImplementation", { DataStore.fragmentImplementation }, { DataStore.fragmentImplementation = it }),
        textEntry("fragmentSize", { DataStore.fragmentSize }, { DataStore.fragmentSize = it }),
        textEntry("fragmentSleep", { DataStore.fragmentSleep }, { DataStore.fragmentSleep = it }),
        boolEntry("tlsTricksDefaultOn", { DataStore.tlsTricksDefaultOn }, { DataStore.tlsTricksDefaultOn = it }),
        textEntry("utlsFingerprint", { DataStore.utlsFingerprint }, { DataStore.utlsFingerprint = it }),
        textEntry("tlsSpoof", { DataStore.tlsSpoof }, { DataStore.tlsSpoof = it }),
        textEntry("tlsSpoofMethod", { DataStore.tlsSpoofMethod }, { DataStore.tlsSpoofMethod = it }),
        textEntry("h2IdleTimeout", { DataStore.h2IdleTimeout }, { DataStore.h2IdleTimeout = it }),
        textEntry("h2KeepAlivePeriod", { DataStore.h2KeepAlivePeriod }, { DataStore.h2KeepAlivePeriod = it }),
        textEntry("h2StreamReceiveWindow", { DataStore.h2StreamReceiveWindow }, { DataStore.h2StreamReceiveWindow = it }),
        textEntry("h2ConnectionReceiveWindow", { DataStore.h2ConnectionReceiveWindow }, { DataStore.h2ConnectionReceiveWindow = it }),
        intEntry("h2MaxConcurrentStreams", { DataStore.h2MaxConcurrentStreams }, { DataStore.h2MaxConcurrentStreams = it }),
        intEntry("quicInitialPacketSize", { DataStore.quicInitialPacketSize }, { DataStore.quicInitialPacketSize = it }),
        boolEntry("quicDisablePathMtuDiscovery", { DataStore.quicDisablePathMtuDiscovery }, { DataStore.quicDisablePathMtuDiscovery = it }),

        // --- Subscriptions ------------------------------------------------------------------------
        textEntry("userAgent", { DataStore.userAgent2 }, { DataStore.userAgent2 = it }),
        textEntry("subCustomHwidParams", { DataStore.subCustomHwidParams }, { DataStore.subCustomHwidParams = it }),
        boolEntry("netUseProxy", { DataStore.netUseProxy }, { DataStore.netUseProxy = it }),
        boolEntry("netInsecure", { DataStore.netInsecure }, { DataStore.netInsecure = it }),
        boolEntry("subClear", { DataStore.subClear }, { DataStore.subClear = it }),
        boolEntry("subShowChangePopup", { DataStore.subShowChangePopup }, { DataStore.subShowChangePopup = it }),
        boolEntry("subSendHwid", { DataStore.subSendHwid }, { DataStore.subSendHwid = it }),
        intEntry("subTlsVersion", { DataStore.subTlsVersion }, { DataStore.subTlsVersion = it }),
        intEntry("subHttpVersion", { DataStore.subHttpVersion }, { DataStore.subHttpVersion = it }),
        boolEntry("subRespectServerInterval", { DataStore.subRespectServerInterval }, { DataStore.subRespectServerInterval = it }),
        boolEntry("allowStoppingActiveProfile", { DataStore.allowStoppingActiveProfile }, { DataStore.allowStoppingActiveProfile = it }),
        boolEntry("subAutoUpdateEnabled", get = { DataStore.subAutoUpdate.hoursPair.first }, set = { enabled ->
            DataStore.subAutoUpdate = if (enabled) DataStore.subAutoUpdate.hoursPair.second.coerceAtLeast(30) else -1
        }),
        intEntry("subAutoUpdateMinutes", get = { DataStore.subAutoUpdate.hoursPair.second.takeIf { it >= 30 } ?: 30 }, set = { minutes ->
            DataStore.subAutoUpdate = if (DataStore.subAutoUpdate > 0) minutes.coerceIn(30, 525600) else -minutes.coerceIn(30, 525600)
        }),
        boolEntry("routeAutoUpdateEnabled", get = { DataStore.routeAutoUpdate.hoursPair.first }, set = { enabled ->
            DataStore.routeAutoUpdate = if (enabled) DataStore.routeAutoUpdate.hoursPair.second.coerceAtLeast(30) else -1
        }),
        intEntry("routeAutoUpdateMinutes", get = { DataStore.routeAutoUpdate.hoursPair.second.takeIf { it >= 30 } ?: 1440 }, set = { minutes ->
            DataStore.routeAutoUpdate = if (DataStore.routeAutoUpdate > 0) minutes.coerceIn(30, 525600) else -minutes.coerceIn(30, 525600)
        }),

        // --- Legacy B-box keys, kept so the existing screens keep working without duplication ----
        boolEntry("perAppEnabled", Apply.Reload, true, { DataStore.proxyApps }, { DataStore.proxyApps = it }),
        boolEntry("perAppManaged", get = { false }, set = { /* Throne has no managed list */ }),
        Entry(bridgeKey = "perAppManagedList", get = { emptyList<String>() }, set = { /* read only */ }),
        Entry(
            bridgeKey = "perAppMode",
            apply = Apply.Reload,
            get = { if (DataStore.bypass) 1 else 2 },
            set = { DataStore.bypass = (it as? Int ?: it?.toString()?.toIntOrNull() ?: 1) == 1 },
        ),
        Entry(
            bridgeKey = "perAppList",
            apply = Apply.Reload,
            get = { DataStore.individual.split("\n").filter { line -> line.isNotBlank() } },
            set = {
                val packages = (it as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                DataStore.individual = packages.joinToString("\n")
            },
        ),
        // The upstream switch is inverted: Throne ships "bypass private ranges" on by default.
        boolEntry(
            "allowBypass",
            Apply.Reload,
            true,
            { !DataStore.disablePrivateRangeBypass },
            { DataStore.disablePrivateRangeBypass = !it },
        ),
        Entry(bridgeKey = "queryMode", get = { "ROOT" }, set = { /* PackageManager query only */ }),
        Entry(bridgeKey = "batteryExempt", get = { batteryUnrestricted() }, set = { /* read only */ }),
    )

    private val index: Map<String, Entry> by lazy(LazyThreadSafetyMode.NONE) {
        entries.associateBy { entry -> entry.bridgeKey }
    }

    fun has(key: String) = index.containsKey(key)

    fun get(key: String) = index[key]?.get?.invoke()

    /** Returns the follow-up action; null when the key is unknown. */
    fun set(key: String, value: Any?): Apply? {
        val entry = index[key] ?: return null
        entry.set(value)
        return entry.apply
    }

    fun read(): Map<String, Any?> = entries.associate { entry -> entry.bridgeKey to entry.get() }

    /**
     * The rows a bridge key lives under when its own name is not one: the split views (a flag and a
     * port sharing one signed row) and the keys the Android side names differently from the bridge.
     */
    private val RESET_ROW_ALIASES = mapOf(
        "allowBypass" to "disable_private_range_bypass",
        "userAgent" to "user_agent2",
        "fakeDns" to "fakedns",
        "warpEndpoint" to "warp_ep",
        "warpMasqueEndpoint" to "warp_masque_ep",
        "perAppEnabled" to "proxyApps",
        "perAppMode" to "bypassMode",
        "perAppList" to "individual",
        "routeAutoUpdateEnabled" to "route_auto_update",
        "routeAutoUpdateMinutes" to "route_auto_update",
        "subAutoUpdateEnabled" to "sub_auto_update",
        "subAutoUpdateMinutes" to "sub_auto_update",
        "clashApiEnabled" to "core_box_clash_api",
        "clashApiPort" to "core_box_clash_api",
        "clashApiListenAddr" to "core_box_clash_listen_addr",
        "clashApiSecret" to "core_box_clash_api_secret",
        "boxApiEnabled" to "core_box_api_port",
        "boxApiPort" to "core_box_api_port",
        "boxApiSecret" to "core_box_api_secret",
    )

    private fun camelToSnake(key: String) = buildString(key.length + 4) {
        for (c in key) {
            if (c.isUpperCase()) { append('_'); append(c.lowercaseChar()) } else append(c)
        }
    }

    /**
     * Drops the key's rows so every read falls back to the Throne default, and returns the entry's
     * follow-up action; null when the key is unknown. [Apply.None] also answers for a reset that
     * changed nothing — nothing was stored, or the row held the default — so the caller can leave
     * the running core alone. A row that is not there is simply not removed.
     */
    fun reset(key: String): Apply? {
        val entry = index[key] ?: return null
        val before = entry.get()
        val store = DataStore.configurationStore
        val rows = LinkedHashSet<String>()
        rows.add(camelToSnake(key))
        rows.add(key)
        RESET_ROW_ALIASES[key]?.let { rows.add(it) }
        rows.forEach { store.remove(it) }
        return if (before == entry.get()) Apply.None else entry.apply
    }

    /** [reset] over every key the schema knows; returns the heaviest follow-up action. */
    fun resetAll(): Apply? = entries.mapNotNull { reset(it.bridgeKey) }.maxByOrNull { it.ordinal }
}

/** Whether the app is exempt from battery optimisation; the read-only `batteryExempt` key. */
fun batteryUnrestricted(): Boolean = runCatching {
    val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    power.isIgnoringBatteryOptimizations(app.packageName)
}.getOrDefault(false)
