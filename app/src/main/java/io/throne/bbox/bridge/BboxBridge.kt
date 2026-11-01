package io.throne.bbox.bridge

import android.content.Context
import android.os.RemoteException
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import io.throne.bbox.model.BboxStatus
import io.throne.bbox.model.BboxUiState
import io.throne.bbox.model.DashboardState
import io.throne.bbox.model.EgressInfo
import io.throne.bbox.model.MemoryInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bridge between Throne's backend and the ported B-box UI.
 *
 * Throne spreads its state over four places: the bound service (running / connecting, speed callbacks), the
 * preference store (`selectedProxy`, the service state mirror), the profile and group repositories, and the routing
 * profile. The old UI read each of them where it needed them, from a fragment that also owned the service
 * connection. This object replaces that: it owns the one service connection, mirrors every source into a single
 * immutable [BboxUiState], and hands the UI a snapshot to render.
 *
 * Writes go the other way through [BboxCommands], which keeps the UI free of `SagerNet` and database calls.
 */
object BboxBridge : SagerConnection.Callback, GroupRepo.Listener, ProfileManager.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(BboxUiState())
    val state: StateFlow<BboxUiState> = _state.asStateFlow()

    val meter = BboxTrafficMeter()

    /** The catalog as last read; the Flutter host needs the selected group, which the UI state does not carry. */
    @Volatile
    var catalog: BboxCatalog.Catalog = BboxCatalog.Catalog()
        private set

    private var connection: SagerConnection? = null
    private var appContext: Context? = null
    private var ticker: Job? = null
    private var refreshJob: Job? = null
    private var egressJob: Job? = null

    /** When the running service reached [BaseService.State.Connected], for the dashboard's run time. */
    private var connectedAt: Long? = null

    private var lastProfileName: String = ""

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        GroupRepo.addListener(this)
        ProfileManager.addListener(this)
        val conn = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
        connection = conn
        runCatching { conn.connect(context.applicationContext, this) }
            .onFailure { Logs.w(it) }
        ticker = scope.launch {
            while (isActive) {
                publishRuntime()
                delay(1000)
            }
        }
        scheduleRefresh(immediate = true)
    }

    fun shutdown() {
        if (!installed) return
        installed = false
        GroupRepo.removeListener(this)
        ProfileManager.removeListener(this)
        ticker?.cancel()
        ticker = null
        refreshJob?.cancel()
        refreshJob = null
        egressJob?.cancel()
        egressJob = null
        connection?.let { conn ->
            appContext?.let { runCatching { conn.disconnect(it) } }
        }
        connection = null
        appContext = null
    }

    // ------------------------------------------------------------------------------------------------ service callbacks

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        DataStore.serviceState = state
        lastProfileName = profileName.orEmpty()
        when (state) {
            BaseService.State.Connected -> if (connectedAt == null) connectedAt = System.currentTimeMillis()
            BaseService.State.Stopped, BaseService.State.Idle -> {
                connectedAt = null
                meter.reset()
                BboxEgress.invalidate()
            }
            else -> Unit
        }
        scheduleRefresh(immediate = true)
        if (state == BaseService.State.Connected) requestEgress()
    }

    override fun onServiceConnected(service: ISagerNetService) {
        lastProfileName = runCatching { service.profileName }.getOrElse { "" }
        val state = runCatching { BaseService.State.values()[service.state] }
            .getOrElse { BaseService.State.Idle }
        DataStore.serviceState = state
        if (state == BaseService.State.Connected && connectedAt == null) {
            connectedAt = System.currentTimeMillis()
        }
        scheduleRefresh(immediate = true)
        requestEgress()
    }

    override fun onServiceDisconnected() {
        DataStore.serviceState = BaseService.State.Idle
        connectedAt = null
        scheduleRefresh(immediate = true)
    }

    override fun onBinderDied() {
        onServiceDisconnected()
        connection?.let { conn ->
            appContext?.let { ctx ->
                runCatching { conn.disconnect(ctx) }
                runCatching { conn.connect(ctx, this) }
            }
        }
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        meter.onSpeed(stats)
        publishRuntime()
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        // The service persists these itself; the rows only need a reload, which the listeners already schedule.
    }

    override fun cbSelectorUpdate(id: Long) {
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        scheduleRefresh()
    }

    override fun cbAutoSelectorUpdate(json: String) {
        scheduleRefresh()
    }

    // ------------------------------------------------------------------------------------------------ repository callbacks

    override suspend fun groupAdd(group: ProxyGroup) = scheduleRefresh()

    override suspend fun groupUpdated(group: ProxyGroup) = scheduleRefresh()

    override suspend fun groupUpdated(groupId: Long) = scheduleRefresh()

    override suspend fun groupRemoved(groupId: Long) = scheduleRefresh()

    override suspend fun groupsReordered() = scheduleRefresh()

    override suspend fun onAdd(profile: ProxyEntity) = scheduleRefresh()

    override suspend fun onUpdated(data: List<TrafficData>) = Unit

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = scheduleRefresh()

    override suspend fun onRemoved(groupId: Long, profileId: Long) = scheduleRefresh()

    // ------------------------------------------------------------------------------------------------ state assembly

    /** Debounces bursts of change notifications into one catalog read. */
    fun scheduleRefresh(immediate: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            if (!immediate) delay(150)
            refreshNow()
        }
    }

    private suspend fun refreshNow() {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { BboxCatalog.load() }.getOrElse {
                Logs.w(it)
                BboxCatalog.Catalog()
            }
        }
        catalog = loaded
        _state.update { current ->
            current.copy(
                profiles = loaded.profiles,
                totalNodes = loaded.totalNodes,
                dashboard = current.dashboard.copy(
                    groups = loaded.groups,
                    profile = loaded.current,
                    modes = BboxMode.modes,
                    mode = BboxMode.get().id,
                ),
            )
        }
        publishRuntime()
    }

    /** Recomputes the cheap parts every second: status, speed, run time and memory. */
    private fun publishRuntime() {
        val state = DataStore.serviceState
        val traffic = meter.traffic.value
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val startedAt = connectedAt
        catalog = catalog
        _state.update { current ->
            current.copy(
                dashboard = current.dashboard.copy(
                    status = BboxStatus(
                        running = state.connected,
                        transitioning = state == BaseService.State.Connecting || state == BaseService.State.Stopping,
                        statusText = when (state) {
                            BaseService.State.Connected -> "Running"
                            BaseService.State.Connecting -> "Connecting"
                            BaseService.State.Stopping -> "Stopping"
                            else -> "Not running"
                        },
                        startedAt = startedAt,
                        remote = false,
                    ),
                    traffic = traffic,
                    memory = MemoryInfo(inUse = used, total = runtime.maxMemory()),
                    downloadHistory = meter.downloadHistory.toList(),
                    uploadHistory = meter.uploadHistory.toList(),
                    egress = current.dashboard.egress,
                ),
            )
        }
    }

    /** Probes the outbound identity in the background, then folds the answer into the state. */
    fun requestEgress(force: Boolean = false) {
        if (egressJob?.isActive == true) return
        if (!DataStore.serviceState.connected) {
            _state.update { it.copy(dashboard = it.dashboard.copy(egress = EgressInfo())) }
            return
        }
        egressJob = scope.launch {
            _state.update { it.copy(dashboard = it.dashboard.copy(egress = it.dashboard.egress.copy(checking = true, error = null))) }
            var failure: String? = null
            val info = withContext(Dispatchers.IO) {
                BboxEgress.probe(force) { failure = it }
            }
            _state.update {
                it.copy(
                    dashboard = it.dashboard.copy(
                        egress = EgressInfo(
                            ip = info.ip,
                            country = info.country,
                            organization = info.organization,
                            asn = info.asn,
                            type = info.type,
                            quality = null,
                            checking = false,
                            error = if (info.isEmpty) failure ?: "Unreachable" else null,
                        ),
                    ),
                )
            }
        }
    }

    /** The name the service reports for the running profile, for the status card's subtitle. */
    fun runningProfileName(): String = lastProfileName

    /** Only used by the widget and the quick tile, which have no UI state of their own. */
    fun isRunning(): Boolean = DataStore.serviceState.connected

    internal fun serviceBinder(): ISagerNetService? = try {
        connection?.service
    } catch (_: RemoteException) {
        null
    }
}
