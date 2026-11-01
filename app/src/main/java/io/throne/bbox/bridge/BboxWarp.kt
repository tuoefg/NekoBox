package io.throne.bbox.bridge

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.warp.WarpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WARP registration: the native "Generate" row (ui/warp/WarpSettingsScreen) behind one channel.
 *
 * Throne registers the identity in the core process and never stores the device id, token or
 * license, so the only thing a registration leaves behind is the key material of the mode it was
 * made for — exactly what [fill] writes. The amnezia mode additionally needs its I1–I5 packets,
 * which upstream generates right after the identity; a failure there is reported but still leaves
 * the keys in place, as the native flow does.
 */
object BboxWarp {

    /**
     * Registers a [tunnelType] identity and saves the fields of the mode it belongs to.
     *
     * The answer carries the identity as the UI shows it plus a `reload` flag — true when WARP is
     * enabled and the service is running, which is the UI's cue to call `service.reload`, since
     * this write takes effect in the configuration the core already built.
     */
    suspend fun register(tunnelType: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val masque = tunnelType == WarpClient.TUNNEL_MASQUE
        val identity = WarpClient.register(tunnelType)
        // Only the amnezia mode carries packets, and they are regenerated with each identity;
        // a WireGuard registration keeps whatever the mode had.
        var packetsError: String? = null
        if (DataStore.warpMode == WarpClient.MODE_AMNEZIA) {
            WarpClient.generateCamouflage()
                .onSuccess { DataStore.warpAmneziaPackets = it }
                .onFailure { packetsError = it.readableMessage }
        }
        fill(identity, masque)
        mapOf(
            "tunnelType" to tunnelType,
            "mode" to DataStore.warpMode,
            "endpoint" to identity.endpoint,
            "addresses" to identity.addresses,
            "reserved" to identity.reserved,
            "packets" to DataStore.warpAmneziaPackets,
            "packetsError" to packetsError,
            "reload" to (DataStore.enableWarp && DataStore.serviceState.canStop),
        )
    }

    /** The fields of the generated mode (dialog_manage_routes.cpp:480-495), saved at once. */
    private fun fill(identity: WarpClient.Identity, masque: Boolean) {
        if (masque) {
            DataStore.warpMasquePrivateKey = identity.privateKey
            DataStore.warpMasquePeerPublicKey = identity.peerPublicKey
            DataStore.warpMasqueEp = identity.endpoint
            DataStore.warpMasqueIfcAddrs = identity.addresses
        } else {
            DataStore.warpPrivateKey = identity.privateKey
            DataStore.warpPublicKey = identity.peerPublicKey
            DataStore.warpEp = identity.endpoint
            DataStore.warpIfcAddrs = identity.addresses
            DataStore.warpReserved = identity.reserved
        }
    }
}
