package io.throne.bbox.bridge

import android.content.Intent
import android.net.Uri
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileOrder
import io.nekohasekai.sagernet.group.SubscriptionQueue
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import io.throne.bbox.model.BboxActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The write half of the bridge: every gesture the ported UI can make, expressed in Throne's terms.
 *
 * The UI never touches `SagerNet`, the database or broadcasts itself; it calls these methods, which run the blocking
 * parts on IO and hand the resulting change back through [BboxBridge]'s state flow.
 */
object BboxCommands : BboxActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun toggleService() {
        val state = DataStore.serviceState
        when {
            state.canStop -> SagerNet.stopService()
            DataStore.selectedProxy <= 0L -> Logs.w("bbox: start asked with no profile selected")
            else -> SagerNet.startService()
        }
    }

    override fun setMode(mode: String) {
        BboxMode.set(BboxMode.Mode.of(mode))
        if (DataStore.serviceState.canStop) SagerNet.reloadService()
        BboxBridge.scheduleRefresh(immediate = true)
    }

    override fun selectProxy(group: String, tag: String) {
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                val target = GroupRepo.all().firstOrNull { it.displayName() == group } ?: return@withContext null
                ProfileManager.members(target.id).firstOrNull { it.displayName() == tag }
            } ?: return@launch
            select(profile.id)
        }
    }

    /** Makes [profileId] the selection; a running service switches to it instead of restarting from scratch. */
    fun select(profileId: Long) {
        if (profileId <= 0L) return
        val previous = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        DataStore.currentProfile = profileId
        if (DataStore.serviceState.canStop && previous != profileId) {
            app.sendBroadcast(
                Intent(Action.SWITCH_PROFILE)
                    .setPackage(app.packageName)
                    .putExtra(Action.EXTRA_PROFILE_ID, profileId)
            )
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ProfileManager.getProfile(previous)?.let { ProfileManager.postUpdate(it.id, true) }
                    ProfileManager.postUpdate(profileId, true)
                }
            }
            BboxBridge.scheduleRefresh(immediate = true)
        }
    }

    override fun testGroup(group: String) {
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                val g = GroupRepo.all().firstOrNull { it.displayName() == group } ?: return@withContext null
                g to ProfileManager.memberIds(g.id)
            } ?: return@launch
            if (target.second.isEmpty()) return@launch
            TestSessionClient.start(
                TestSpec(
                    kind = TestSpec.KIND_URL,
                    profileIds = target.second.toLongArray(),
                    scopeLabel = target.first.displayName(),
                ),
                target.first.id,
            )
        }
    }

    override fun testProxy(group: String, tag: String) {
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                val g = GroupRepo.all().firstOrNull { it.displayName() == group } ?: return@withContext null
                ProfileManager.members(g.id).firstOrNull { it.displayName() == tag }
            } ?: return@launch
            TestSessionClient.start(
                TestSpec(kind = TestSpec.KIND_URL, profileIds = longArrayOf(profile.id), scopeLabel = profile.displayName()),
                profile.groupId,
            )
        }
    }

    override fun refreshEgress() {
        BboxBridge.requestEgress(force = true)
    }

    override fun selectProfile(id: Long) {
        scope.launch {
            val member = withContext(Dispatchers.IO) {
                val group = GroupRepo.get(id) ?: return@withContext null
                GroupRepo.setCurrent(group.id)
                // The selection moves even while the service runs (switchProfile hot-switches it):
                // the profile card, the proxies screen and the running config all follow the same
                // selected member. A group that already holds it keeps it; another gets its first.
                val ids = ProfileManager.memberIds(group.id)
                ids.firstOrNull { it == DataStore.selectedProxy } ?: ids.firstOrNull()
            }
            member?.let { select(it) }
            BboxBridge.scheduleRefresh(immediate = true)
        }
    }

    override fun updateSubscription(id: Long) {
        if (id <= 0L) return
        runCatching { SubscriptionQueue.refreshGroup(id, showDiff = true) }
            .onFailure { Logs.w(it) }
    }

    /**
     * Previous / next server, the television's way of changing profiles (TvControls.switchServer):
     * a running service hot-switches through the same broadcast its notification buttons send, a
     * stopped one just moves the selection inside the current group.
     */
    override fun switchServer(step: Int) {
        if (step == 0) return
        if (DataStore.serviceState.canStop) {
            app.sendBroadcast(
                Intent(if (step > 0) Action.SWITCH_NEXT else Action.SWITCH_PREVIOUS)
                    .setPackage(app.packageName)
            )
            return
        }
        scope.launch {
            val moved = withContext(Dispatchers.IO) {
                val old = DataStore.selectedProxy
                val target = ProfileOrder.neighbour(old, step) ?: return@withContext null
                DataStore.selectedProxy = target.id
                old to target.id
            } ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    ProfileManager.postUpdate(moved.first, true)
                    ProfileManager.postUpdate(moved.second, true)
                }
            }
            BboxBridge.scheduleRefresh(immediate = true)
        }
    }

    override fun deleteProfile(id: Long) {
        if (id <= 0L) return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { GroupRepo.delete(id) } }
            BboxBridge.scheduleRefresh(immediate = true)
        }
    }

    override fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Logs.w(it) }
    }
}
