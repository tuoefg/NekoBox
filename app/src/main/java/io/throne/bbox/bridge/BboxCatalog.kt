package io.throne.bbox.bridge

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubUserInfo
import io.throne.bbox.model.ProfileInfo
import io.throne.bbox.model.ProxyGroup as UiGroup
import io.throne.bbox.model.ProxyNode
import java.text.DateFormat
import java.util.Date

/**
 * Reads Throne's catalog and maps it onto the model B-box's screens render.
 *
 * The two data models are close but not the same shape. Throne's [ProxyGroup] is a tab that holds profiles, and its
 * subscription fields live on that tab; B-box's *profile* is the subscription and its *group* is the selector inside
 * it. So the mapping is:
 *
 * - a Throne group becomes a B-box group, its members become the group's nodes, and the selected profile is the
 *   group's selection;
 * - a subscription group also becomes a B-box profile card, carrying the quota and expiry of its last fetch.
 *
 * Nothing here writes: the catalog is a snapshot the bridge rebuilds after every change notification.
 */
object BboxCatalog {

    data class Catalog(
        val groups: List<UiGroup> = emptyList(),
        val profiles: List<ProfileInfo> = emptyList(),
        val current: ProfileInfo? = null,
        val totalNodes: Int = 0,
        /** The group holding the selected profile, which is what B-box calls the selected profile. */
        val selectedGroupId: Long = 0L,
    )

    /** Blocking; call from an IO dispatcher. */
    fun load(): Catalog {
        val selected = DataStore.selectedProxy
        val entities = ArrayList<Pair<ProxyGroup, List<ProxyEntity>>>()
        val groups = GroupRepo.all()
        for (group in groups) {
            entities += group to runCatching { ProfileManager.members(group.id) }.getOrDefault(emptyList())
        }
        val uiGroups = entities.map { (group, members) ->
            val selectedName = members.firstOrNull { it.id == selected }?.displayName().orEmpty()
            UiGroup(
                tag = group.displayName(),
                type = if (group.isSubscription) "subscription" else "group",
                selected = selectedName,
                selectable = true,
                nodes = members.map(::node),
            )
        }
        // Every selectable group needs a profile card: B-box derives "can start" from selectedProfile, so a local
        // group that is not also a profile would leave a valid selection unable to start the service.
        val profiles = entities.map { (group, members) ->
            profileOf(group, members, selected)
        }
        val current = entities.asSequence()
            .flatMap { it.second.asSequence() }
            .firstOrNull { it.id == selected }
            ?.let { profile ->
                val group = entities.firstOrNull { it.second.any { member -> member.id == profile.id } }?.first
                ProfileInfo(
                    id = profile.id,
                    name = profile.displayName(),
                    type = profile.displayType(),
                    subtitle = group?.displayName()?.takeIf { it.isNotBlank() },
                    selected = true,
                )
            }
        val selectedGroup = entities.firstOrNull { (_, members) -> members.any { it.id == selected } }?.first
        return Catalog(
            groups = uiGroups,
            profiles = profiles,
            current = current,
            totalNodes = entities.sumOf { it.second.size },
            selectedGroupId = selectedGroup?.id ?: 0L,
        )
    }

    private fun node(profile: ProxyEntity) = ProxyNode(
        tag = profile.displayName(),
        type = profile.displayType(),
        delay = profile.latency,
        testedAt = profile.latencyAt * 1000L,
    )

    private fun profileOf(group: ProxyGroup, members: List<ProxyEntity>, selected: Long): ProfileInfo {
        val info = group.subInfo
        val containsSelected = members.any { it.id == selected }
        return ProfileInfo(
            id = group.id,
            name = group.displayName(),
            type = if (group.isSubscription) "subscription" else "group",
            subtitle = subtitle(group, members),
            traffic = if (group.isSubscription) trafficOf(info) else null,
            expiry = if (group.isSubscription) expiryOf(info) else null,
            selected = containsSelected,
            url = group.url.orEmpty(),
        )
    }

    private fun subtitle(group: ProxyGroup, members: List<ProxyEntity>): String {
        val host = runCatching { java.net.URI(group.url).host }.getOrNull()
        val count = "${members.size} nodes"
        return listOfNotNull(host?.takeIf { it.isNotBlank() }, count).joinToString(" · ")
    }

    /** "12.3 GB / 100 GB" or the used figure when the provider gave no quota. */
    fun trafficOf(info: SubUserInfo): String? {
        if (!info.valid) return null
        val used = formatBytes(info.used)
        return if (info.hasQuota && info.total > 0) "$used / ${formatBytes(info.total)}" else used
    }

    fun expiryOf(info: SubUserInfo): String? {
        if (!info.valid || info.expire <= 0L) return null
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.expire * 1000L))
        return if (info.isExpired()) "Expired $date" else "Until $date"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format("%.1f %s", value, units[unit])
    }

    fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"
}
