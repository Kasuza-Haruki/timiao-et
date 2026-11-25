package app.timiao.et.auto

import androidx.annotation.StringRes
import app.timiao.et.R

/**
 * Pure rule model and matcher. No Android API here, so JVM unit tests cover it.
 *
 * A rule answers one question: given the current network, should this rule's
 * network be connected or disconnected? A rule with a null [networkId] is the
 * global rule and targets the app's active network at runtime.
 */

enum class NetType { None, Wifi, Cellular, Other }

enum class AutoCondition(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val shortLabelRes: Int,
) {
    Always("always", R.string.auto_cond_always, R.string.auto_cond_always_short),
    WifiOnly("wifi_only", R.string.auto_cond_wifi_only, R.string.auto_cond_wifi_only_short),
    CellularOnly("cellular_only", R.string.auto_cond_cellular_only, R.string.auto_cond_cellular_only_short),
    ExceptWifi("except_wifi", R.string.auto_cond_except_wifi, R.string.auto_cond_except_wifi_short);

    /** True when the condition compares the current SSID against a list. */
    val needsSsid: Boolean get() = this == WifiOnly || this == ExceptWifi

    companion object {
        fun fromKey(key: String?): AutoCondition = entries.firstOrNull { it.key == key } ?: Always
    }
}

data class AutoRule(
    /** Network config id, or null for the global rule. */
    val networkId: String? = null,
    val condition: AutoCondition = AutoCondition.Always,
    val wifiNames: List<String> = emptyList(),
    /** Keep a notification after an automatic disconnect. */
    val keepNotification: Boolean = false,
    val enabled: Boolean = true,
) {
    val isGlobal: Boolean get() = networkId == null
}

/** What the phone currently uses for internet. [ssid] is null when unknown or redacted. */
data class NetworkSnapshot(
    val type: NetType = NetType.None,
    val ssid: String? = null,
    val locationGranted: Boolean = false,
) {
    val connected: Boolean get() = type != NetType.None
}

enum class RuleAction { Connect, Disconnect, Skip }

enum class SkipReason(@StringRes val labelRes: Int) {
    NoNetwork(R.string.auto_skip_no_network),
    SsidUnavailable(R.string.auto_skip_ssid),
    NoRule(R.string.auto_skip_no_rule),
}

sealed interface AutoDecision {
    data class Connect(val networkId: String?, val rule: AutoRule) : AutoDecision
    data class Disconnect(val networkId: String?, val rule: AutoRule) : AutoDecision
    data class Skip(val reason: SkipReason) : AutoDecision
}

/** Trims whitespace and the quotes Android adds around SSIDs. */
fun String.normalizeSsid(): String = trim().trim('"').trim()

private fun NetworkSnapshot.matches(names: Collection<String>): Boolean {
    val current = ssid?.normalizeSsid()?.takeIf { it.isNotEmpty() } ?: return false
    return names.any { it.normalizeSsid().equals(current, ignoreCase = true) }
}

fun AutoRule.actionFor(snapshot: NetworkSnapshot): RuleAction {
    if (!snapshot.connected) return RuleAction.Skip
    return when (condition) {
        AutoCondition.Always -> RuleAction.Connect
        AutoCondition.CellularOnly -> when (snapshot.type) {
            NetType.Cellular -> RuleAction.Connect
            NetType.Wifi, NetType.Other -> RuleAction.Disconnect
            NetType.None -> RuleAction.Skip
        }
        AutoCondition.WifiOnly -> when (snapshot.type) {
            NetType.Cellular, NetType.Other -> RuleAction.Disconnect
            NetType.Wifi -> if (snapshot.ssid == null) RuleAction.Skip
            else if (snapshot.matches(wifiNames)) RuleAction.Connect else RuleAction.Disconnect
            NetType.None -> RuleAction.Skip
        }
        AutoCondition.ExceptWifi -> when (snapshot.type) {
            NetType.Cellular, NetType.Other -> RuleAction.Connect
            NetType.Wifi -> if (snapshot.ssid == null) RuleAction.Skip
            else if (snapshot.matches(wifiNames)) RuleAction.Disconnect else RuleAction.Connect
            NetType.None -> RuleAction.Skip
        }
    }
}

/**
 * Per-network rules come before the global rule. A connect request beats a
 * disconnect request, so a rule that excludes a network (for example "only on
 * these Wi-Fi") cannot stop another network's rule from connecting on cellular.
 * Rules that cannot evaluate (no SSID) fall through, so generic rules still
 * work when location permission is denied.
 */
fun evaluate(snapshot: NetworkSnapshot, rules: List<AutoRule>): AutoDecision {
    val enabled = rules.filter { it.enabled }
    if (enabled.isEmpty()) return AutoDecision.Skip(SkipReason.NoRule)

    // Per-network rules first, global last; the first connect wins.
    val ordered = enabled.filterNot { it.isGlobal } + enabled.filter { it.isGlobal }
    var disconnect: AutoDecision.Disconnect? = null
    var skipped: SkipReason? = null

    for (rule in ordered) {
        when (rule.actionFor(snapshot)) {
            RuleAction.Connect -> return AutoDecision.Connect(rule.networkId, rule)
            RuleAction.Disconnect -> if (disconnect == null) {
                disconnect = AutoDecision.Disconnect(rule.networkId, rule)
            }
            RuleAction.Skip -> if (skipped == null) {
                skipped = if (!snapshot.connected) SkipReason.NoNetwork else SkipReason.SsidUnavailable
            }
        }
    }

    return disconnect ?: AutoDecision.Skip(skipped ?: SkipReason.NoRule)
}

/** Parses the comma / newline separated Wi-Fi name field used by the UI. */
fun parseWifiNames(text: String): List<String> =
    text.split(',', '\n', '，')
        .map { it.normalizeSsid() }
        .filter { it.isNotEmpty() }
        .distinct()

fun formatWifiNames(names: List<String>): String = names.joinToString(", ")
