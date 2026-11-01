package io.throne.bbox.bridge

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.config.RoutingInput
import io.nekohasekai.sagernet.route.OutboundIds

/**
 * B-box's "Outbound mode" switch, backed by Throne's routing profile instead of sing-box's per-box mode.
 *
 * Throne has no `route.final` toggle of its own: the route profile's default outbound and its rule list decide where
 * traffic goes. The three modes B-box shows map onto that model without touching stored user data:
 *
 * - [Mode.Rule] uses the route profile exactly as the user saved it.
 * - [Mode.Global] drops the profile's rules and sends everything to the started profile (`proxy`).
 * - [Mode.Direct] drops the profile's rules and sends everything to `direct`.
 *
 * The DNS hijack rule survives every mode: without it the core's own DNS requests would leave unhandled and the
 * tunnel would come up without name resolution.
 */
object BboxMode {

    enum class Mode(val id: String) {
        Rule("rule"),
        Global("global"),
        Direct("direct");

        companion object {
            fun of(id: String?): Mode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Rule
        }
    }

    private const val KEY = "bbox_outbound_mode"

    /** Every mode the switch offers, in the order B-box draws them. */
    val modes: List<String> get() = Mode.entries.map { it.id }

    fun get(): Mode = Mode.of(DataStore.configurationStore.getString(KEY))

    /** Stores the mode; the caller reloads the core so the new routing takes effect. */
    fun set(mode: Mode) {
        DataStore.configurationStore.putString(KEY, mode.id)
    }

    /**
     * Rewrites [input]'s route profile for the stored mode. Rule mode returns the input untouched, so the common path
     * allocates nothing.
     */
    fun applyTo(input: RoutingInput): RoutingInput {
        val mode = get()
        if (mode == Mode.Rule) return input
        val profile = input.profile ?: return input
        val routed = profile.copy().apply {
            default_outbound_id = if (mode == Mode.Global) OutboundIds.PROXY else OutboundIds.DIRECT
            rules = profile.rules.filter { it.action == "hijack-dns" }.mapTo(ArrayList()) { it.copy() }
        }
        return RoutingInput(routed, input.catalog, input.selectors, input.memberCheck)
    }

    /** A one-line summary for the dashboard's mode card. */
    fun label(mode: String): String = when (Mode.of(mode)) {
        Mode.Rule -> "Rule"
        Mode.Global -> "Global"
        Mode.Direct -> "Direct"
    }

    /** The mode the running config was built with, for the status card. */
    fun currentProfileName(): String {
        val mode = get()
        return if (mode == Mode.Rule) "Route" else label(mode.id)
    }

    internal fun resetForTest() {
        DataStore.configurationStore.remove(KEY)
    }
}
