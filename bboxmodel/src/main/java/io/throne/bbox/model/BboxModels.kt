package io.throne.bbox.model

/**
 * The state the ported UI renders. It is deliberately backend-agnostic: the app
 * module maps Throne's `ProfileManager` / `GroupManager` / service state onto
 * these types, and the preview module maps mock data onto them, so the same
 * screens run against either.
 */
data class BboxStatus(
    val running: Boolean = false,
    val transitioning: Boolean = false,
    val statusText: String = "",
    val startedAt: Long? = null,
    val remote: Boolean = false,
)

data class Traffic(
    val uploadRate: Long = 0,
    val downloadRate: Long = 0,
    val uploadTotal: Long = 0,
    val downloadTotal: Long = 0,
)

data class EgressInfo(
    val ip: String? = null,
    val country: String? = null,
    val organization: String? = null,
    val asn: String? = null,
    val type: String? = null,
    val quality: String? = null,
    val checking: Boolean = false,
    val error: String? = null,
)

data class ProxyNode(
    val id: Long = 0L,
    val tag: String,
    val type: String = "",
    val delay: Int = 0,
    val testedAt: Long = 0L,
    /** A `type=autoselector` node, which has its own editor instead of the share-link one. */
    val isSelector: Boolean = false,
)

data class ProxyGroup(
    val id: Long = 0L,
    val tag: String,
    val type: String = "",
    val selected: String = "",
    val selectable: Boolean = true,
    val nodes: List<ProxyNode> = emptyList(),
)

data class ProfileInfo(
    val id: Long,
    val name: String,
    val type: String = "",
    val subtitle: String? = null,
    val traffic: String? = null,
    val expiry: String? = null,
    val selected: Boolean = false,
    val url: String = "",
    /** Epoch milliseconds of the group's last subscription fetch; 0 when it was never fetched. */
    val updatedAt: Long = 0L,
    /** False when the group has "skip auto update" set, which is Throne's per-subscription switch. */
    val autoUpdate: Boolean = false,
    /** The subscription's own refresh interval in minutes; 0 leaves it to the global default. */
    val interval: Int = 0,
)

data class MemoryInfo(val inUse: Long = 0, val total: Long = 0)

data class DashboardState(
    val status: BboxStatus = BboxStatus(),
    val traffic: Traffic = Traffic(),
    val egress: EgressInfo = EgressInfo(),
    val modes: List<String> = listOf("rule", "global", "direct"),
    val mode: String = "rule",
    val profile: ProfileInfo? = null,
    val groups: List<ProxyGroup> = emptyList(),
    val memory: MemoryInfo? = null,
    val intranet: List<String> = emptyList(),
    val downloadHistory: List<Float> = emptyList(),
    val uploadHistory: List<Float> = emptyList(),
)

data class ConnectionInfo(
    val id: String,
    val network: String,
    val host: String,
    val rule: String,
    val chain: String,
    val upload: Long,
    val download: Long,
)

/** Everything the ported screens render, in one snapshot. */
data class BboxUiState(
    val dashboard: DashboardState = DashboardState(),
    val profiles: List<ProfileInfo> = emptyList(),
    val connections: List<ConnectionInfo> = emptyList(),
    val logs: List<String> = emptyList(),
    val totalNodes: Int = 0,
    val coreVersion: String = "",
)

/** What the screens can ask the backend to do. */
interface BboxActions {
    fun toggleService() {}
    fun setMode(mode: String) {}
    fun selectProxy(group: String, tag: String) {}
    fun testGroup(group: String) {}
    fun testProxy(group: String, tag: String) {}
    fun refreshEgress() {}
    fun selectProfile(id: Long) {}
    fun editProfile(id: Long) {}
    fun deleteProfile(id: Long) {}
    fun addProfile() {}
    fun updateSubscription(id: Long) {}
    fun openUrl(url: String) {}
    fun clearLogs() {}
    fun closeConnection(id: String) {}
    fun closeAllConnections() {}
}
