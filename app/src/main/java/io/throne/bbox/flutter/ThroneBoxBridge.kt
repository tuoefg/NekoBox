package io.throne.bbox.flutter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import android.os.Build
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.CrashStore
import io.nekohasekai.sagernet.database.DnsServerHistory
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.streamUri
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.ui.json.engine.JsonTree
import io.nekohasekai.sagernet.ui.json.engine.JsonType
import io.nekohasekai.sagernet.ui.json.engine.JsonFormatter
import io.nekohasekai.sagernet.update.UpdateChecker
import moe.matsuri.nb4a.utils.LogRedactor
import io.throne.bbox.bridge.BboxApi
import io.throne.bbox.bridge.BboxApps
import io.throne.bbox.bridge.BboxBackup
import io.throne.bbox.bridge.BboxBridge
import io.throne.bbox.bridge.BboxCommands
import io.throne.bbox.bridge.BboxEgress
import io.throne.bbox.bridge.BboxLogcat
import io.throne.bbox.bridge.BboxLogs
import io.throne.bbox.bridge.BboxProbeSession
import io.throne.bbox.bridge.BboxProfiles
import io.throne.bbox.bridge.BboxRoutes
import io.throne.bbox.bridge.BboxTestSession
import io.throne.bbox.bridge.BboxWarp
import io.throne.bbox.bridge.Capabilities
import io.throne.bbox.bridge.Apply
// Aliased: this file also uses android.provider.Settings for the battery-optimisation intents.
import io.throne.bbox.bridge.Settings as ThroneSettings
import io.throne.bbox.model.BboxUiState
import io.throne.bbox.model.ProxyGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The host side of B-box's own interface.
 *
 * B-box's Flutter screens talk to Android through exactly two channels — `io.ericlee.bfa/box` for calls and
 * `io.ericlee.bfa/events` for pushes — and know nothing about the app behind them. This class implements that
 * contract over Throne: the state comes from [BboxBridge], the writes go through [BboxCommands], and the payloads
 * are shaped the way the Dart side reads them (`box.dart`, `dashboard.dart`, `proxies.dart`, `profiles.dart`).
 *
 * Methods are grouped by whether the answer can be produced synchronously: anything that has to touch the database,
 * the package manager, the network or a system dialog goes through [respond], which moves it off the channel thread
 * and turns a thrown exception into the platform error the UI shows.
 *
 * A handful of screens ask for things Throne has no equivalent of — a terminal, SSH sessions, Tailscale, Taildrop,
 * USB devices and HTTP request capture. Those answer with an error, which the UI reports as a snackbar rather than
 * rendering something invented.
 */
class ThroneBoxBridge(
    private val activity: FragmentActivity,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler, Closeable {

    private val channel = MethodChannel(messenger, "io.ericlee.bfa/box")
    private val events = EventChannel(messenger, "io.ericlee.bfa/events")

    private val scope = activity.lifecycleScope
    private var sink: EventChannel.EventSink? = null
    private var pump: Job? = null

    /** B-box keeps its interface preferences in a store of its own; so does the port. */
    private val preferences = activity.getSharedPreferences("bbox_ui", Context.MODE_PRIVATE)

    private var lastDashboard: Map<String, Any?>? = null
    private var lastRates: Map<String, Any?>? = null
    private var lastProfiles: List<Map<String, Any?>>? = null
    private var lastGroups: List<Map<String, Any?>>? = null

    /** The connections screen, the memory panel and the log screen each hold one subscription while they are open. */
    private var connectionsJob: Job? = null
    private var memoryJob: Job? = null

    /** The update queue's change reports, for as long as the host lives. */
    private var reportsJob: Job? = null

    /** The batch test panel's state feed, polled only while the panel shows it. */
    private var testSessionJob: Job? = null

    @Volatile
    private var lastTestSession: Map<String, Any?>? = null

    /** QR scans that span several frames: what has arrived so far, per session. */
    private val scans = ConcurrentHashMap<String, StringBuilder>()

    /** Probe sessions: one HTTP client per id, tunneled through the running service. */
    private val probes = ConcurrentHashMap<String, BboxProbeSession>()

    /** The URI the app was opened with, handed to the UI once. */
    private var pendingIntent: String? = null

    /** The intent instance whose payload was already offered to the UI; a repeat take must not re-deliver it. */
    private var deliveredIntent: Intent? = null

    /** Set when an intent arrived while no event sink was attached; raised again in [onListen]. */
    private var intentAvailablePending = false

    @Volatile
    private var lanBusy = false

    /** Set when a write only takes effect on a process restart, cleared once the UI was told. */
    @Volatile
    private var restartPending = false

    fun attach() {
        channel.setMethodCallHandler(this)
        events.setStreamHandler(this)
        // Subscription change reports (SubscriptionReportDialog.observe): popups, errors, status
        // lines and reload hints of the update queue, forwarded while the host is alive. A report
        // that arrives before the UI listens is dropped, as the native window drops them while closed.
        reportsJob = scope.launch {
            SubscriptionClient.reports.collect { report -> emitReport(report) }
        }
    }

    override fun close() {
        pump?.cancel()
        connectionsJob?.cancel()
        memoryJob?.cancel()
        reportsJob?.cancel()
        testSessionJob?.cancel()
        BboxLogs.stop()
        BboxLogcat.stop()
        probes.keys.forEach { closeProbe(it) }
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        sink = null
    }

    // ------------------------------------------------------------------------------------------------ events

    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
        sink = eventSink
        if (intentAvailablePending) {
            intentAvailablePending = false
            emit("intentAvailable", true)
        }
        pump?.cancel()
        pump = scope.launch {
            BboxBridge.state
                .map { it.wireKey() }
                .distinctUntilChanged()
                .collect { pushState() }
        }
    }

    override fun onCancel(arguments: Any?) {
        pump?.cancel()
        pump = null
        sink = null
    }

    private fun emit(type: String, value: Any?) {
        activity.runOnUiThread {
            sink?.success(mapOf("type" to type, "value" to value))
        }
    }

    /** Pushes whatever changed since the last push: the dashboard, the rates and the catalog are separate events. */
    private fun pushState() {
        val state = BboxBridge.state.value
        val dashboard = dashboardWire(state)
        if (dashboard != lastDashboard) {
            lastDashboard = dashboard
            emit("dashboard", dashboard)
        }
        val rates = mapOf(
            "upload" to state.dashboard.traffic.uploadRate,
            "download" to state.dashboard.traffic.downloadRate,
            "memoryBytes" to (state.dashboard.memory?.inUse ?: 0L),
        )
        if (rates != lastRates) {
            lastRates = rates
            emit("rates", rates)
        }
        val profiles = profilesWire(state)
        if (profiles != lastProfiles) {
            lastProfiles = profiles
            emit("profiles", profiles)
        }
        val groups = groupsWire(state)
        if (groups != lastGroups) {
            lastGroups = groups
            emit("groups", groups)
        }
    }

    private fun BboxUiState.wireKey(): String = buildString {
        append(dashboard.status.running).append(dashboard.status.transitioning)
        append(dashboard.status.startedAt).append(dashboard.profile?.id)
        append(dashboard.traffic.uploadTotal).append(dashboard.traffic.downloadTotal)
        append(dashboard.traffic.uploadRate).append(dashboard.traffic.downloadRate)
        append(dashboard.memory?.inUse).append(dashboard.mode).append(dashboard.groups.hashCode()).append(profiles.hashCode())
    }

    // ------------------------------------------------------------------------------------------------ calls

    /**
     * Runs [block] on the scope's dispatcher (the main thread) and answers the call with whatever it
     * produces. A thrown exception becomes the platform error whose message the UI shows, so a
     * failure is never a silent empty screen. A block that does I/O must switch dispatchers itself.
     */
    private fun MethodChannel.Result.respond(block: suspend () -> Any?) {
        if (!scope.isActive) {
            error("cancelled", "Activity closed", null)
            return
        }
        scope.launch {
            try {
                success(block())
            } catch (e: CancellationException) {
                error("cancelled", e.message ?: "Operation cancelled", null)
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                error("failed", e.message ?: e.javaClass.simpleName, null)
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "ready" -> result.success(readyPayload())
                "service.start" -> {
                    if (!DataStore.serviceState.canStop) SagerNet.startService()
                    result.success(null)
                }
                "service.stop" -> {
                    if (DataStore.serviceState.canStop) SagerNet.stopService()
                    result.success(null)
                }
                "service.reload" -> {
                    if (DataStore.serviceState.canStop) SagerNet.reloadService()
                    result.success(null)
                }
                "mode.set" -> {
                    val mode = call.argument<String>("mode") ?: error("Missing mode")
                    BboxCommands.setMode(mode)
                    result.success(null)
                }
                "groups.select" -> {
                    val group = call.argument<String>("group") ?: error("Missing group")
                    val tag = call.argument<String>("tag") ?: error("Missing tag")
                    BboxCommands.selectProxy(group, tag)
                    result.success(null)
                }
                "groups.test" -> {
                    val tag = call.argument<String>("tag") ?: error("Missing tag")
                    testNode(tag)
                    result.success(null)
                }
                // The batch test panel over TestSessionClient: one session at a time, state pushed
                // while the panel is visible (groups.testSession.visible opens the feed).
                "groups.testSession.start" -> {
                    val kind = call.argument<Int>("kind") ?: error("Missing kind")
                    val groupId = call.argument<Int>("groupId")?.toLong() ?: 0L
                    val ids = call.argument<List<Any>>("profileIds").orEmpty().mapNotNull { (it as? Number)?.toLong() }
                    val testCurrent = call.argument<Boolean>("testCurrent") == true
                    // The speed session's direction; -1 keeps the Testing settings' mode.
                    val speedMode = call.argument<Int>("speedMode") ?: -1
                    result.respond {
                        if (groupId > 0) BboxTestSession.startGroup(kind, groupId, speedMode)
                        else BboxTestSession.startProfiles(kind, ids, testCurrent, speedMode)
                    }
                }
                "groups.testSession.stop" -> {
                    BboxTestSession.stop()
                    result.success(null)
                }
                "groups.testSession.dismiss" -> {
                    BboxTestSession.dismiss()
                    result.success(null)
                }
                "groups.testSession.sort" -> result.respond {
                    val sorted = BboxTestSession.sort(call.argument<Int>("groupId")?.toLong() ?: 0L)
                    if (!sorted) error("The group is gone or the sort did not run")
                    null
                }
                "groups.testSession.remove" -> result.respond {
                    val ids = call.argument<List<Any>>("ids").orEmpty().mapNotNull { (it as? Number)?.toLong() }
                    mapOf("removed" to BboxTestSession.remove(ids))
                }
                "groups.testSession.visible" -> {
                    setTestSessionVisible(call.argument<Boolean>("visible") == true)
                    result.success(null)
                }
                "profiles.list" -> result.success(profilesWire(BboxBridge.state.value))
                "profiles.select" -> {
                    BboxCommands.selectProfile(call.argument<Int>("id")?.toLong() ?: -1L)
                    result.success(null)
                }
                "profiles.delete" -> {
                    BboxCommands.deleteProfile(call.argument<Int>("id")?.toLong() ?: -1L)
                    result.success(null)
                }
                "profiles.update" -> {
                    BboxCommands.updateSubscription(call.argument<Int>("id")?.toLong() ?: -1L)
                    result.success(null)
                }
                // The television's previous / next server (TvControls.switchServer): a running
                // service hot-switches, a stopped one moves the selection.
                "profiles.switchRelative" -> {
                    BboxCommands.switchServer(call.argument<Int>("step") ?: 0)
                    result.success(null)
                }
                // The group settings form: every ProxyGroup field the native editor exposes, with the
                // choices and defaults its menus resolve to. The save validates with the native
                // editor's own checks and reloads the service when a running route re-pointed.
                "groupSettings.get" -> result.respond {
                    BboxProfiles.groupSettings(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "groupSettings.set" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = call.argument<Map<String, Any?>>("value") ?: call.arguments as? Map<String, Any?>
                    result.respond { BboxProfiles.saveGroupSettings(values.orEmpty()) }
                }
                "profiles.stats" -> result.respond {
                    BboxProfiles.stats(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "profiles.reorder" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = (call.argument<List<Any>>("ids").orEmpty()).mapNotNull { (it as? Number)?.toLong() }
                    result.respond { BboxProfiles.reorder(ids); null }
                }
                "profiles.save" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = call.argument<Map<String, Any?>>("value") ?: call.arguments as? Map<String, Any?>
                    result.respond { BboxProfiles.save(values.orEmpty()); null }
                }
                "profiles.config" -> result.respond {
                    BboxProfiles.config(call.argument<Int>("id")?.toLong() ?: -1L).wire()
                }
                "profiles.read" -> result.respond {
                    BboxProfiles.read(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "profiles.write" -> result.respond {
                    BboxProfiles.write(
                        call.argument<Int>("id")?.toLong() ?: -1L,
                        call.argument<String>("revision"),
                        call.argument<String>("content").orEmpty(),
                    ).wire()
                }
                "profiles.decodeIntent" -> {
                    val uri = call.argument<String>("uri").orEmpty()
                    result.respond { BboxProfiles.decodeIntent(uri) }
                }
                "files.open" -> {
                    val uri = call.argument<String>("uri").orEmpty()
                    result.respond { BboxProfiles.decodeFile(uri) }
                }
                "profiles.nodeLink" -> {
                    val group = call.argument<String>("group").orEmpty()
                    val tag = call.argument<String>("tag").orEmpty()
                    val jsonLink = call.argument<Boolean>("json") == true
                    result.respond { BboxProfiles.nodeLink(group, tag, jsonLink) }
                }
                "profiles.nodeInfo" -> result.respond {
                    BboxProfiles.nodeInfo(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "profiles.nodeConfig" -> result.respond {
                    BboxProfiles.nodeConfig(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "profiles.nodeUpdate" -> result.respond {
                    BboxProfiles.updateNode(
                        call.argument<Int>("id")?.toLong() ?: -1L,
                        call.argument<String>("name"),
                        call.argument<String>("content"),
                    )
                }
                "profiles.nodeDelete" -> result.respond {
                    BboxProfiles.deleteNode(call.argument<Int>("id")?.toLong() ?: -1L); null
                }
                "routes.list" -> result.respond { BboxRoutes.list() }
                "routes.get" -> result.respond { BboxRoutes.get(call.argument<Int>("id")?.toLong() ?: -1L) }
                "routes.save" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = call.argument<Map<String, Any?>>("value") ?: call.arguments as? Map<String, Any?>
                    result.respond { BboxRoutes.save(values.orEmpty()) }
                }
                "routes.delete" -> result.respond {
                    BboxRoutes.delete(call.argument<Int>("id")?.toLong() ?: -1L); null
                }
                "routes.setCurrent" -> result.respond {
                    BboxRoutes.setCurrent(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "routes.presets" -> result.respond { BboxRoutes.presets() }
                "routes.addPreset" -> result.respond {
                    BboxRoutes.addPreset(
                        call.argument<String>("country").orEmpty(),
                        call.argument<Boolean>("autoUpdate") != false,
                    )
                }
                "routes.rules" -> result.respond {
                    BboxRoutes.rules(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "routes.saveRules" -> {
                    @Suppress("UNCHECKED_CAST")
                    val rules = call.argument<List<Any>>("rules").orEmpty()
                        .mapNotNull { it as? Map<String, Any?> }
                    result.respond { BboxRoutes.saveRules(call.argument<Int>("id")?.toLong() ?: -1L, rules) }
                }
                "routes.batchAddRules" -> result.respond {
                    BboxRoutes.batchAddRules(
                        call.argument<Int>("id")?.toLong() ?: -1L,
                        call.argument<String>("text").orEmpty(),
                    )
                }
                "routes.resetRules" -> result.respond {
                    BboxRoutes.resetRules(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                // The rule-set catalog (the routeprofiles srslist) behind the native rule-set picker.
                "routes.ruleSets" -> result.respond {
                    val query = call.argument<String>("query").orEmpty().trim()
                    val limit = call.argument<Int>("limit") ?: 100
                    val catalog = withContext(Dispatchers.IO) { RouteManager.catalog() }
                    mapOf(
                        "total" to catalog.size,
                        "entries" to catalog.search(query, limit).map { (name, url) ->
                            mapOf("name" to name, "url" to url)
                        },
                    )
                }
                "profiles.autoSelectorCreate" -> result.respond {
                    BboxProfiles.createAutoSelector(call.argument<Int>("groupId")?.toLong())
                }
                "profiles.autoSelectorInfo" -> result.respond {
                    BboxProfiles.autoSelectorInfo(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "profiles.autoSelectorSave" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = call.argument<Map<String, Any?>>("value") ?: call.arguments as? Map<String, Any?>
                    result.respond { BboxProfiles.autoSelectorSave(call.argument<Int>("id")?.toLong() ?: -1L, values.orEmpty()) }
                }
                "profiles.autoSelectorStatus" -> result.respond { BboxProfiles.autoSelectorStatus() }
                "profiles.autoSelectorPin" -> result.respond {
                    BboxProfiles.autoSelectorPin(
                        call.argument<Int>("id")?.toLong() ?: -1L,
                        call.argument<Int>("memberId")?.toLong() ?: -1L,
                    )
                }
                "profiles.autoSelectorRecheck" -> result.respond {
                    BboxProfiles.autoSelectorRecheck(); null
                }
                "profiles.nodeTuningInfo" -> result.respond {
                    BboxProfiles.nodeTuningInfo(call.argument<Int>("id")?.toLong() ?: -1L)
                }
                "profiles.nodeTuningSave" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = call.argument<Map<String, Any?>>("value") ?: call.arguments as? Map<String, Any?>
                    result.respond { BboxProfiles.nodeTuningSave(call.argument<Int>("id")?.toLong() ?: -1L, values.orEmpty()) }
                }
                "profiles.importFile" -> result.respond { importFile() }
                "profiles.scan" -> {
                    val session = call.argument<String>("session").orEmpty()
                    val text = call.argument<String>("text").orEmpty()
                    result.respond { scanChunk(session, text) }
                }
                "profiles.scan.close" -> {
                    scans.remove(call.argument<String>("session").orEmpty())
                    result.success(null)
                }
                "preferences.set" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = call.argument<Map<String, Any?>>("value").orEmpty()
                    result.success(writePreferences(values))
                }
                "settings.get" -> result.success(settingsWire())
                "settings.set" -> {
                    val key = call.argument<String>("key") ?: error("Missing key")
                    applySettings(mapOf(key to call.argument<Any>("value")))
                    if (call.argument<Boolean>("restart") == true && DataStore.serviceState.canStop) {
                        SagerNet.reloadService()
                    }
                    result.success(settingsWire())
                }
                "settings.patch" -> {
                    @Suppress("UNCHECKED_CAST")
                    val values = call.argument<Map<String, Any?>>("value").orEmpty()
                    applySettings(values)
                    result.success(settingsWire())
                }
                "settings.reset" -> {
                    val keys = call.argument<List<Any>>("keys").orEmpty().filterIsInstance<String>()
                    val need = if (keys.isEmpty()) {
                        ThroneSettings.resetAll()
                    } else {
                        keys.mapNotNull { ThroneSettings.reset(it) }.maxByOrNull { it.ordinal }
                    } ?: Apply.None
                    // On demand only: a reset that changed nothing leaves the running core alone,
                    // a Reload rebuilds its config now, and AppRestart raises the badge only a
                    // process restart clears (log level, log buffer).
                    when {
                        need == Apply.AppRestart -> {
                            restartPending = true
                            emit("applyRequired", "Restart")
                        }
                        need == Apply.Reload && DataStore.serviceState.canStop -> SagerNet.reloadService()
                    }
                    result.success(settingsWire())
                }
                // WARP registration: the identity is made in the core process and the keys of the
                // chosen mode are saved by the bridge; the answer tells the UI whether a running
                // service has to reload the configuration it just changed. The keys went straight
                // to storage, so the settings event refreshes what the screen shows.
                "warp.register" -> {
                    val tunnelType = call.argument<String>("tunnelType") ?: error("Missing tunnel type")
                    result.respond {
                        val answer = BboxWarp.register(tunnelType)
                        emit("settings", settingsWire())
                        answer
                    }
                }
                // The DNS server fields offer presets plus the servers typed before (DnsServerHistory::Fill).
                "dns.suggestions" -> result.success(
                    mapOf(
                        "remote" to DnsServerHistory.remoteSuggestions(),
                        "direct" to DnsServerHistory.directSuggestions(),
                    )
                )
                // What Wi-Fi rules need (location, background, services): the native walk-through
                // runs its dialogs and answers whether everything was granted. The caller saves the
                // rule either way — a refusal leaves the rules inactive, as in the native editor.
                "wifiPermissions.ensure" -> result.respond {
                    (activity as? BboxFlutterActivity)?.ensureWifiPermissions() ?: false
                }
                "url.open" -> {
                    val url = call.argument<String>("url").orEmpty()
                    BboxCommands.openUrl(url)
                    result.success(null)
                }
                "intent.take" -> result.success(takeIntent())

                // --- live views, each owned by the screen that opened it
                "connections.visible" -> {
                    setConnectionsVisible(call.argument<Boolean>("visible") == true)
                    result.success(null)
                }
                // OkHttp is synchronous; the channel thread is the main thread, where it throws
                // NetworkOnMainThreadException. Same for memoryWire below.
                "connections.close" -> result.respond {
                    withContext(Dispatchers.IO) {
                        BboxApi.close(call.argument<String>("id").orEmpty())
                    }
                    null
                }
                "connections.closeAll" -> result.respond {
                    withContext(Dispatchers.IO) { BboxApi.closeAll() }
                    null
                }
                "logs.visible" -> {
                    setLogsVisible(call.argument<Boolean>("visible") == true)
                    result.success(null)
                }
                "logs.clear" -> result.respond {
                    val batch = withContext(Dispatchers.IO) {
                        BboxLogs.clear()
                        BboxLogs.Batch(reset = true, defaultLogLevel = BboxLogs.level())
                    }
                    emit("logs", BboxLogs.wire(batch))
                    null
                }
                // The system logcat tab: same shape as the core log's events, a different source.
                // The durable crash records: core.log rotates, these files survive until pruned.
                "crashes.list" -> result.respond {
                    withContext(Dispatchers.IO) {
                        // Prune on read too: records older than the window otherwise
                        // survive until the next crash happens to fire.
                        CrashStore.prune()
                        CrashStore.list().map { file ->
                            mapOf("name" to file.name, "size" to file.length(), "time" to file.lastModified())
                        }
                    }
                }
                "crashes.read" -> result.respond {
                    val name = call.argument<String>("name").orEmpty()
                    // The name comes back from crashes.list; anything path-like is rejected outright.
                    require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains("..")) {
                        "bad crash log name"
                    }
                    withContext(Dispatchers.IO) { mapOf("name" to name, "content" to CrashStore.read(name)) }
                }
                "logs.logcat.visible" -> {
                    if (call.argument<Boolean>("visible") == true) {
                        BboxLogcat.start(scope) { emit("logcat", BboxLogcat.wire(it)) }
                    } else {
                        BboxLogcat.stop()
                    }
                    result.success(null)
                }
                "logs.logcat.clear" -> result.respond {
                    withContext(Dispatchers.IO) { BboxLogcat.clear() }
                    null
                }
                "dashboard.memory.visible" -> {
                    setMemoryVisible(call.argument<Boolean>("visible") == true)
                    result.success(null)
                }
                "dashboard.releaseMemory" -> {
                    System.gc()
                    result.success(null)
                }

                // --- diagnostics and the status card's probes
                "dashboard.egress" -> result.respond { egressWire() }
                "dashboard.probe.open" -> {
                    probes.computeIfAbsent(call.argument<String>("id").orEmpty()) { BboxProbeSession(probeClient()) }
                    result.success(null)
                }
                "dashboard.probe.get" -> result.respond { probeGet(call) }
                "dashboard.probe.close" -> {
                    closeProbe(call.argument<String>("id").orEmpty())
                    result.success(null)
                }
                "dashboard.qualityCache.get" -> result.success(qualityCache(call.argument<String>("ip").orEmpty()))
                "dashboard.qualityCache.put" -> {
                    val ip = call.argument<String>("ip").orEmpty()
                    @Suppress("UNCHECKED_CAST")
                    val value = call.argument<Any>("value")
                    writeQualityCache(ip, value)
                    result.success(null)
                }

                // --- LAN access to the running proxy
                "dashboard.lan.refresh" -> result.respond { lanWire() }
                "dashboard.lan.set" -> {
                    val enabled = call.argument<Boolean>("enabled") == true
                    val port = call.argument<Int>("port")
                    result.respond { lanSet(enabled, port) }
                }

                // --- per-app access control
                "apps.list" -> {
                    val refresh = call.argument<Boolean>("refresh") == true
                    val system = call.argument<Boolean>("system") == true
                    val nonInternet = call.argument<Boolean>("nonInternet") == true
                    // Package-manager scans belong off the main thread (the channel thread is it).
                    result.respond {
                        withContext(Dispatchers.IO) { BboxApps.list(refresh, system, nonInternet) }
                            .map(BboxApps.App::wire)
                    }
                }
                "apps.icon" -> result.respond {
                    withContext(Dispatchers.IO) { BboxApps.icon(call.argument<String>("package").orEmpty()) }
                }
                "apps.rescan" -> result.respond { rescanManagedApps() }
                "apps.permission" -> result.error(
                    "unsupported",
                    "Throne reads the app list through the package manager, so it needs no Shizuku grant",
                    null,
                )

                // --- backup, restore and WebDAV, all of which Throne already does natively
                "backup.create" -> result.respond { BboxBackup.create(call.argument<String>("scope") ?: "all") }
                "backup.read" -> result.respond { BboxBackup.read() }
                "backup.restore" -> {
                    @Suppress("UNCHECKED_CAST")
                    val payload = call.argument<Map<String, Any?>>("backup")
                    val scope = call.argument<String>("scope") ?: "profiles"
                    val strategy = call.argument<String>("strategy") ?: "Compatible"
                    result.respond {
                        val warnings = BboxBackup.restore(payload, scope, strategy)
                        if (warnings.isNotEmpty()) emit("error", warnings.joinToString("\n"))
                        null
                    }
                }
                "webdav.get" -> result.success(BboxBackup.webdav())
                "webdav.set" -> {
                    @Suppress("UNCHECKED_CAST")
                    result.success(BboxBackup.setWebdav(call.argument<Map<String, Any?>>("value")))
                }
                "webdav.check" -> result.respond { BboxBackup.checkWebdav() }
                "webdav.upload" -> result.respond { BboxBackup.uploadWebdav("all") }
                "webdav.read" -> result.respond { BboxBackup.readWebdav() }

                // --- documents and the share sheet
                "document.save" -> {
                    val name = call.argument<String>("name").orEmpty()
                    val content = call.argument<String>("content").orEmpty()
                    // Log exports carry credentials (URLs, keys); the native export always redacts
                    // (LogExport.build with redact = true), and so must anything that asks for it here.
                    val redact = call.argument<Boolean>("redact") == true
                    result.respond {
                        val text = withContext(Dispatchers.IO) {
                            if (!redact) content else {
                                val redactor = LogRedactor(hideDestinations = false)
                                content.lineSequence().joinToString("\n", transform = redactor::redact)
                            }
                        }
                        saveDocument(name, text)
                    }
                }
                "share.text" -> {
                    val content = call.argument<String>("content").orEmpty()
                    result.respond { shareText(content) }
                }
                "config.check" -> result.respond { checkConfig(call.argument<String>("content").orEmpty()) }
                "config.format" -> result.respond { formatConfig(call.argument<String>("content").orEmpty()) }
                "updates.check" -> result.respond { updatesWire() }
                "battery.open" -> {
                    openBatterySettings()
                    result.success(settingsWire())
                }
                "systemProxy.set" -> {
                    // Upstream's switch: the VPN service writes its mixed inbound into the system
                    // settings, which API 29 allows and the proxy-only mode cannot do at all.
                    val enabled = call.argument<Boolean>("enabled") == true
                    if (!systemProxyAvailable()) {
                        result.error("unsupported", "The system HTTP proxy needs VPN mode and Android 10 or newer", null)
                    } else {
                        DataStore.appendHttpProxy = enabled
                        if (DataStore.serviceState.canStop) SagerNet.reloadService()
                        result.success(mapOf("available" to true, "enabled" to DataStore.appendHttpProxy))
                    }
                }
                else -> result.error(
                    "unsupported",
                    "This screen is not wired to Throne yet: ${call.method}",
                    null,
                )
            }
        } catch (e: Exception) {
            Logs.w(e)
            result.error("failed", e.message ?: e.javaClass.simpleName, null)
        }
    }

    /** Tests the group the node belongs to, since Throne measures latency per profile. */
    private fun testNode(tag: String) {
        val group = BboxBridge.state.value.dashboard.groups.firstOrNull { it.tag == tag }
        if (group != null) {
            BboxCommands.testGroup(group.tag)
            return
        }
        val owner = BboxBridge.state.value.dashboard.groups.firstOrNull { candidate ->
            candidate.nodes.any { it.tag == tag }
        } ?: return
        BboxCommands.testProxy(owner.tag, tag)
    }

    // -------------------------------------------------------------------------------------------- live views

    /**
     * Starts or stops the connections feed.
     *
     * Throne's core only exposes live connections through its own control API, which its settings keep switched off by
     * default. Opening the screen flips that setting the way the core settings screen does and asks for the reload that
     * makes the port answer; the first poll after that is the first one that can return anything.
     */
    private fun setConnectionsVisible(visible: Boolean) {
        connectionsJob?.cancel()
        connectionsJob = null
        if (!visible) return
        if (BboxApi.enable()) {
            if (DataStore.serviceState.canStop) SagerNet.reloadService()
            emit("applyRequired", "Restart")
        }
        connectionsJob = scope.launch {
            while (isActive) {
                val rows = withContext(Dispatchers.IO) {
                    runCatching { BboxApi.connections() }.onFailure { Logs.w(it) }.getOrDefault(emptyList())
                }
                if (rows.isNotEmpty() || lastConnections != null) {
                    lastConnections = rows
                    emit("connections", rows)
                }
                delay(1500)
            }
        }
    }

    @Volatile
    private var lastConnections: List<Map<String, Any?>>? = null

    private fun setLogsVisible(visible: Boolean) {
        if (!visible) {
            BboxLogs.stop()
            return
        }
        BboxLogs.start(scope) { emit("logs", BboxLogs.wire(it)) }
    }

    /**
     * The client publishes its model every 100 ms; the panel's feed relays at 400 ms and only what
     * changed, which is what the native panel's diffing achieved on a slower path.
     */
    private fun setTestSessionVisible(visible: Boolean) {
        testSessionJob?.cancel()
        testSessionJob = null
        if (!visible) {
            lastTestSession = null
            return
        }
        testSessionJob = scope.launch {
            while (isActive) {
                val wire = withContext(Dispatchers.IO) { BboxTestSession.wire() }
                if (wire != lastTestSession) {
                    lastTestSession = wire
                    emit("testSession", wire)
                }
                delay(400)
            }
        }
    }

    /**
     * One queue report, split the way the native window splits it: a reload hint raises the apply
     * badge, an error or an untitled status line is a snackbar, a popup opens the change window.
     * The group's name rides along so the UI never has to ask.
     */
    private suspend fun emitReport(report: SubscriptionClient.Report) {
        val groupName = withContext(Dispatchers.IO) {
            runCatching { GroupRepo.get(report.gid)?.displayName() }.getOrNull()
        }
        when {
            report.reload -> emit("applyRequired", "Reload")

            report.error -> emit("subscriptionReport", mapOf(
                "kind" to "error",
                "groupName" to groupName,
                "text" to report.text.ifBlank { report.title },
            ))

            report.popup -> emit("subscriptionReport", mapOf(
                "kind" to "popup",
                "groupName" to groupName,
                "title" to report.title.ifBlank {
                    app.getString(io.nekohasekai.sagernet.R.string.grp_change_of, groupName.orEmpty())
                },
                "text" to report.text,
            ))

            report.title.isEmpty() && report.text.isNotBlank() -> emit("subscriptionReport", mapOf(
                "kind" to "status",
                "text" to report.text,
            ))
        }
    }

    /** Memory is cheap to read but not free on a television, so it is sampled only while a panel shows it. */
    private fun setMemoryVisible(visible: Boolean) {
        memoryJob?.cancel()
        memoryJob = null
        if (!visible) return
        memoryJob = scope.launch {
            while (isActive) {
                // The core's HTTP call inside runs off the main thread, or it throws there and the
                // runCatching in memoryWire keeps the core figure at zero forever.
                emit("memory", withContext(Dispatchers.IO) { memoryWire() })
                delay(2000)
            }
        }
    }

    private fun memoryWire(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val core = runCatching { BboxApi.memory() }.getOrNull()
        return mapOf(
            "total" to runtime.maxMemory(),
            "app" to (runtime.totalMemory() - runtime.freeMemory()),
            "core" to (core ?: 0L),
            "rss" to (core ?: 0L),
            "heapInuse" to (runtime.totalMemory() - runtime.freeMemory()),
            "heapIdle" to (runtime.maxMemory() - runtime.totalMemory()),
            "stack" to 0L,
            "runtimeOther" to 0L,
            "goroutines" to 0,
        )
    }

    // -------------------------------------------------------------------------------------------- probes and egress

    private fun probeClient(): OkHttpClient {
        val host = DataStore.inboundAddress.ifBlank { SettingsRegistry.LOOPBACK_ADDRESS }
        val port = DataStore.inboundSocksPort
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(16, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private suspend fun probeGet(call: MethodCall): Map<String, Any?> {
        // Capture the session before dispatching; a late request must never reopen a closed one.
        val session = probes[call.argument<String>("id").orEmpty()]
            ?: throw CancellationException("IP probe session closed")
        val url = call.argument<String>("url").orEmpty()
        val timeout = call.argument<Int>("timeoutMillis") ?: 8000
        return withContext(Dispatchers.IO) { session.get(url, timeout.toLong()) }
    }

    private fun closeProbe(id: String) {
        probes.remove(id)?.close()
    }

    private suspend fun egressWire(): Map<String, Any?> = withContext(Dispatchers.IO) {
        // The probe reaches the internet through the mixed inbound, which nothing answers until the core is
        // up: with the service stopped it only logged a connection refusal against the dashboard's own check.
        if (!DataStore.serviceState.connected) return@withContext emptyMap()
        var failure: String? = null
        val info = BboxEgress.probe(force = false) { failure = it }
        if (info.isEmpty && failure != null) error(failure!!)
        mapOf(
            "ip" to info.ip,
            "country" to info.country,
            "organization" to info.organization,
            "asn" to info.asn,
            "type" to info.type,
            "ipSource" to "ip.sb",
            "checkedAt" to System.currentTimeMillis(),
        )
    }

    private fun qualityCache(ip: String): Map<String, Any?>? {
        if (ip.isBlank()) return null
        val stored = readJsonMap(QUALITY_KEY)
        @Suppress("UNCHECKED_CAST")
        return stored[ip] as? Map<String, Any?>
    }

    private fun writeQualityCache(ip: String, value: Any?) {
        if (ip.isBlank()) return
        val merged = readJsonMap(QUALITY_KEY).toMutableMap()
        if (value == null) merged.remove(ip) else merged[ip] = value
        // Bounded: an entry per IP the user has ever connected through would grow without limit.
        if (merged.size > 512) {
            val oldest = merged.keys.take(merged.size - 512)
            oldest.forEach { merged.remove(it) }
        }
        writeJsonMap(QUALITY_KEY, merged)
    }

    // ------------------------------------------------------------------------------------------------ LAN access

    /**
     * Whether the running service answers on the LAN.
     *
     * Throne's mixed inbound binds to a configured address; loopback keeps the proxy private to the device and a
     * wildcard address shares it with everything on the same network. That choice *is* the LAN switch, so this reads
     * and writes the same setting the core configuration is built from.
     */
    private fun lanWire(): Map<String, Any?> {
        val shared = isSharedInbound()
        val port = DataStore.inboundSocksPort
        val addresses = lanAddresses()
        val endpoints = if (shared && DataStore.serviceState.connected) {
            addresses.map { "$it:$port" }
        } else {
            emptyList()
        }
        return mapOf(
            "addresses" to addresses,
            "canToggle" to true,
            "running" to (shared && DataStore.serviceState.connected),
            "enabled" to shared,
            "profileId" to (BboxBridge.catalog.selectedGroupId.takeIf { it > 0 } ?: -1),
            "revision" to "$port-${DataStore.inboundAddress}",
            "stamp" to "$port-${DataStore.inboundAddress}",
            "error" to null,
            "bindings" to listOf(
                mapOf(
                    "tag" to "mixed",
                    "type" to "mixed",
                    "tls" to false,
                    "authentication" to false,
                    "port" to port,
                    "endpoints" to endpoints,
                    "lan" to shared,
                )
            ),
            "activeBindings" to if (endpoints.isEmpty()) emptyList<Map<String, Any?>>() else listOf(
                mapOf("tag" to "mixed", "type" to "mixed", "port" to port, "endpoints" to endpoints, "lan" to true)
            ),
        )
    }

    private suspend fun lanSet(enabled: Boolean, port: Int?): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (lanBusy) error("Already switching the LAN listener")
        lanBusy = true
        try {
            if (port != null) DataStore.inboundSocksPort = port
            val address = if (enabled) "0.0.0.0" else SettingsRegistry.LOOPBACK_ADDRESS
            if (address != DataStore.inboundAddress) {
                DataStore.inboundAddress = address
                if (DataStore.serviceState.canStop) SagerNet.reloadService()
            }
            lanWire().also { emit("lan", it) }
        } finally {
            lanBusy = false
        }
    }

    private fun isSharedInbound(): Boolean {
        val address = DataStore.inboundAddress
        return address.isNotBlank() && address != SettingsRegistry.LOOPBACK_ADDRESS && address != "127.0.0.1" && address != "::1"
    }

    private fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------------------------------------ documents

    private suspend fun saveDocument(name: String, content: String): Boolean? {
        val host = activity as? BboxFlutterActivity ?: return false
        val mime = when {
            name.endsWith(".json", ignoreCase = true) -> "application/json"
            name.endsWith(".log", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }
        val uri = host.pickSaveTarget(name, mime) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // A null stream means nothing was written; reporting success here would let the
                // UI confirm an export that did not happen.
                val output = host.contentResolver.openOutputStream(uri) ?: error("cannot open $uri")
                output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                true
            }.onFailure { Logs.w(it) }.getOrDefault(false)
        }
    }

    private suspend fun shareText(content: String): Boolean {
        if (content.isBlank()) return false
        return runCatching {
            activity.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, content),
                    null,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.onFailure { Logs.w(it) }.getOrDefault(false)
    }

    /** A picked file becomes the same draft a pasted link becomes, so both open the same editor. */
    private suspend fun importFile(): Map<String, Any?>? {
        val host = activity as? BboxFlutterActivity ?: return null
        val uri = host.pickDocument() ?: return null
        val text = withContext(Dispatchers.IO) {
            runCatching {
                host.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }.orEmpty()
        if (text.isBlank()) return null
        return BboxProfiles.decodeIntent(text.trim())
    }

    /**
     * Multi-frame QR payloads: the frames are concatenated until the result is something worth opening, and the
     * progress is reported so the screen can show a bar.
     */
    private suspend fun scanChunk(session: String, text: String): Map<String, Any?> {
        val buffer = scans.getOrPut(session) { StringBuilder() }
        if (text.isNotBlank()) buffer.append(text)
        val assembled = buffer.toString().trim()
        val firstLine = assembled.lineSequence().firstOrNull().orEmpty()
        // Besides the subscription/JSON payloads, any node link the importer knows is complete on its own.
        val complete = assembled.startsWith("http://", true) ||
            assembled.startsWith("https://", true) ||
            assembled.startsWith("sing-box://", true) ||
            assembled.startsWith("{") ||
            firstLine.startsWith("throne://add/", true) ||
            firstLine.startsWith("json://", true) ||
            OutboundFactory.typeForScheme(firstLine) != null
        return if (complete) {
            scans.remove(session)
            mapOf(
                "profile" to BboxProfiles.decodeIntent(assembled),
                "text" to assembled,
                "decoded" to 1,
                "total" to 1,
            )
        } else {
            mapOf("text" to assembled, "decoded" to 0, "total" to 1)
        }
    }

    // ------------------------------------------------------------------------------------------------ JSON editing

    /** The document keys a group's configuration may carry; everything else is an app setting. */
    private val CONFIG_KEYS = setOf("name", "url", "autoUpdate", "updateIntervalMinutes", "nodes")

    /** "DNS, routing rules and log settings are app settings": the Throne split the document follows. */
    private fun refuseForeignConfigKey(key: String): Nothing =
        error("\"$key\" is not part of the configuration; DNS, routing rules and log settings are app settings")

    /**
     * The configuration is the group's manifest — name, url, autoUpdate, updateIntervalMinutes and its nodes.
     * DNS and routing live in the app settings as in Throne, so those keys are refused here instead of being
     * dropped by a save that only reads the manifest.
     */
    private suspend fun checkConfig(content: String): Any? = withContext(Dispatchers.IO) {
        val parsed = JsonTree.parse(content)
        val root = parsed.root ?: error(parsed.error.ifBlank { "Not valid JSON" })
        if (root.type != JsonType.Object) error("The configuration must be a JSON object")
        for (key in root.keys) if (key !in CONFIG_KEYS) refuseForeignConfigKey(key)
        root.member("name")?.takeIf { it.type != JsonType.String }?.let { error("\"name\" must be a string") }
        root.member("url")?.takeIf { it.type != JsonType.String }?.let { error("\"url\" must be a string") }
        root.member("autoUpdate")?.takeIf { it.type != JsonType.Bool }?.let { error("\"autoUpdate\" must be true or false") }
        root.member("updateIntervalMinutes")?.takeIf { it.type != JsonType.Number }?.let { error("\"updateIntervalMinutes\" must be a number") }
        root.member("nodes")?.takeIf { it.type != JsonType.Array }?.let { error("\"nodes\" must be an array") }
        null
    }

    private fun formatConfig(content: String): String {
        val parsed = JsonTree.parse(content)
        val root = parsed.root ?: error(parsed.error.ifBlank { "Not valid JSON" })
        return JsonFormatter.format(content, root) ?: content
    }

    // ------------------------------------------------------------------------------------------------ updates

    private suspend fun updatesWire(): Map<String, Any?> = withContext(Dispatchers.IO) {
        when (val result = UpdateChecker.check(app, withNotes = true)) {
            is UpdateChecker.Result.UpToDate -> mapOf(
                "available" to false,
                "version" to BuildConfig.VERSION_NAME,
                "hasMetadata" to true,
            )
            is UpdateChecker.Result.Available -> {
                val offer = result.offer
                mapOf(
                    "available" to true,
                    "version" to offer.versionName,
                    "notes" to offer.notes,
                    "hasMetadata" to true,
                    "downloadUrl" to offer.apk?.url,
                    "url" to offer.release.htmlUrl,
                )
            }
            is UpdateChecker.Result.Failed -> mapOf(
                "available" to false,
                "version" to BuildConfig.VERSION_NAME,
                "hasMetadata" to false,
            )
        }
    }

    private fun openBatterySettings() {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Logs.w(it) }
        }
    }

    /** The system HTTP proxy is written by the VPN service, so proxy-only mode cannot offer it. */
    private fun systemProxyAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.serviceMode == Key.MODE_VPN

    private fun batteryExempt(): Boolean = runCatching {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(app.packageName)
    }.getOrDefault(false)

    /**
     * The share / open-with payload, delivered once per intent: the first `intent.take` after an
     * intent arrives consumes it (per the ready payload's contract), and a later intent starts the
     * cycle again — [notifyIntentAvailable] tells the UI to ask again. Two intents before either is
     * taken keep only the latest, as `setIntent` replaced the first.
     */
    private fun takeIntent(): String? {
        if (pendingIntent == null) {
            val intent = activity.intent
            if (intent != null && intent !== deliveredIntent) {
                // A file arrives in the data slot when it was opened (VIEW) and in the stream slot when it
                // was shared (SEND); the text slot is the QR scanner's and the share sheet's.
                pendingIntent = intent.takeIf { it.action != Intent.ACTION_MAIN }?.dataString
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.streamUri?.toString()
                deliveredIntent = intent
            }
        }
        return pendingIntent.also { pendingIntent = null }
    }

    /**
     * A new intent has replaced the old one; the UI picks it up with `intent.take`. When the UI is
     * not attached the intent still waits for the next `ready`, so nothing is lost — only the ping
     * is, and the pending flag re-raises it in [onListen].
     */
    fun notifyIntentAvailable() {
        if (sink == null) {
            intentAvailablePending = true
            return
        }
        emit("intentAvailable", true)
    }

    /** Recomputes the managed list against what is actually installed; uninstalled packages cannot stay in it. */
    private suspend fun rescanManagedApps(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val known = BboxApps.list(refresh = true, system = false, nonInternet = false)
            .associateBy { it.packageName }
        val kept = DataStore.individual.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it in known }
            .distinct()
            .joinToString("\n")
        DataStore.individual = kept
        settingsWire()
    }

    // ------------------------------------------------------------------------------------------------ payloads

    private fun readyPayload(): Map<String, Any?> = mapOf(
        "version" to BuildConfig.VERSION_NAME,
        "coreVersion" to "sing-box · Xray",
        "systemAccentColor" to 0xFF6750A4.toInt(),
        "dashboard" to dashboardWire(BboxBridge.state.value),
        "profiles" to profilesWire(BboxBridge.state.value),
        "groups" to groupsWire(BboxBridge.state.value),
        "settings" to settingsWire(),
        "capabilities" to Capabilities.map(),
        "preferences" to readPreferences(),
        "lan" to lanWire(),
        "tools" to emptyMap<String, Any?>(),
        "diagnostics" to mapOf(
            "stun" to emptyMap<String, Any?>(),
            "quality" to emptyMap<String, Any?>(),
        ),
        "intent" to takeIntent(),
        // The badge an AppRestart-level write or reset raised; it clears only when the restart
        // gives the process a fresh [restartPending].
        "applyRequired" to (if (restartPending) "Restart" else null),
    )

    private fun dashboardWire(state: BboxUiState): Map<String, Any?> {
        val dashboard = state.dashboard
        val status = when {
            dashboard.status.transitioning && dashboard.status.statusText == "Stopping" -> "Stopping"
            dashboard.status.transitioning -> "Starting"
            dashboard.status.running -> "Started"
            else -> "Stopped"
        }
        return mapOf(
            "status" to status,
            "selectedProfileId" to (BboxBridge.catalog.selectedGroupId.takeIf { it > 0 } ?: -1),
            "selectedProfileName" to (dashboard.profile?.name ?: ""),
            "modes" to dashboard.modes,
            "mode" to dashboard.mode,
            "startedAt" to dashboard.status.startedAt,
            "uploadTotal" to dashboard.traffic.uploadTotal,
            "downloadTotal" to dashboard.traffic.downloadTotal,
            "connections" to 0,
            "memory" to (dashboard.memory?.inUse ?: 0L),
            "goroutines" to 0,
            // Only the VPN service can hand its mixed inbound to apps as the system HTTP proxy.
            "systemProxyAvailable" to systemProxyAvailable(),
            "systemProxy" to DataStore.appendHttpProxy,
            "deprecated" to emptyList<Map<String, Any?>>(),
        )
    }

    private fun profilesWire(state: BboxUiState): List<Map<String, Any?>> =
        state.profiles.map { profile ->
            mapOf(
                "id" to profile.id.toInt(),
                "name" to profile.name,
                "type" to profile.type,
                "remote" to (profile.type == "subscription"),
                "url" to profile.url,
                "traffic" to profile.traffic,
                "expiry" to profile.expiry,
                "selected" to profile.selected,
                "updatedAt" to profile.updatedAt,
                "autoUpdate" to profile.autoUpdate,
                "interval" to profile.interval,
            )
        }

    private fun groupsWire(state: BboxUiState): List<Map<String, Any?>> =
        state.dashboard.groups.map { group: ProxyGroup ->
            mapOf(
                "id" to group.id.toInt(),
                "tag" to group.tag,
                "type" to group.type,
                "selected" to group.selected,
                "selectable" to group.selectable,
                "items" to group.nodes.map { node ->
                    mapOf(
                        "id" to node.id.toInt(),
                        "tag" to node.tag,
                        "type" to node.type,
                        "delay" to node.delay,
                        "testedAt" to node.testedAt,
                        "isSelector" to node.isSelector,
                    )
                },
            )
        }

    /**
     * The settings map the UI reads, in three layers: keys only this shell has (appearance, sorting,
     * card layout) live in a local JSON document, then real Throne storage from [Settings] wins over
     * anything stale in that document, then the derived and read-only keys settle last.
     */
    private fun settingsWire(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result.putAll(readJsonMap(SETTINGS_KEY))
        result.putAll(ThroneSettings.read())
        result["fontSize"] = (result["fontSize"] as? Number)?.toInt() ?: 0
        result["perAppManaged"] = false
        result["perAppManagedList"] = emptyList<String>()
        result["queryMode"] = "ROOT"
        result["batteryExempt"] = batteryExempt()
        return result
    }

    /**
     * Writes go to real Throne storage when [ThroneSettings] knows the key, and to the shell's own document
     * otherwise. A key the host recognises but only applies on a process restart is remembered in
     * [restartPending] so `ready` can tell the UI to show the badge.
     */
    private fun applySettings(values: Map<String, Any?>) {
        val overlay = readJsonMap(SETTINGS_KEY).toMutableMap()
        values.forEach { (key, value) ->
            if (ThroneSettings.has(key)) {
                if (ThroneSettings.set(key, value) == Apply.AppRestart) restartPending = true
                // A typed DNS server joins its suggestion list (RouteProfileActivity's save does the same).
                when (key) {
                    "remoteDns" -> (value as? String)?.let(DnsServerHistory::rememberRemote)
                    "directDns" -> (value as? String)?.let(DnsServerHistory::rememberDirect)
                }
            } else {
                if (value == null) overlay.remove(key) else overlay[key] = value
            }
        }
        writeJsonMap(SETTINGS_KEY, overlay)
        emit("settings", settingsWire())
    }

    // ------------------------------------------------------------------------------------------------ JSON stores

    /** Preferences are one JSON document so bool, number, list and nested map types survive the round trip. */
    private fun writePreferences(values: Map<String, Any?>): Map<String, Any?> {
        val merged = readPreferences().toMutableMap()
        values.forEach { (key, value) ->
            if (value == null) merged.remove(key) else merged[key] = value
        }
        writeJsonMap(PREFERENCES_KEY, merged)
        return merged
    }

    private fun readPreferences(): Map<String, Any?> = readJsonMap(PREFERENCES_KEY)

    private fun readJsonMap(key: String): Map<String, Any?> {
        val stored = preferences.getString(key, null) ?: return emptyMap()
        return runCatching {
            when (val parsed = jsonToWire(JSONObject(stored))) {
                is Map<*, *> -> parsed.entries.associate { it.key.toString() to it.value }
                else -> emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeJsonMap(key: String, values: Map<String, Any?>) {
        val json = JSONObject()
        values.forEach { (name, value) -> json.put(name, wireToJson(value)) }
        preferences.edit().putString(key, json.toString()).apply()
    }

    private fun jsonToWire(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { jsonToWire(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonToWire(value.get(it)) }
        else -> value
    }

    private fun wireToJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        JSONObject.NULL, is JSONObject, is JSONArray -> value
        is Boolean, is Number, is String -> value
        is Map<*, *> -> JSONObject(value.entries.associate { it.key.toString() to wireToJson(it.value) })
        is Collection<*> -> JSONArray(value.map(::wireToJson))
        is Array<*> -> JSONArray(value.map(::wireToJson))
        else -> value.toString()
    }

    private companion object {
        const val PREFERENCES_KEY = "value"
        const val SETTINGS_KEY = "settings"
        const val QUALITY_KEY = "ipQuality"
    }
}
