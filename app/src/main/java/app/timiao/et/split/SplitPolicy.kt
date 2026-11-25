package app.timiao.et.split

import androidx.annotation.StringRes
import app.timiao.et.R

/** How the VPN routes app traffic. Mirrors the three options offered in the UI. */
enum class SplitMode(val key: String, @StringRes val labelRes: Int) {
    All("all", R.string.split_mode_all),
    Allow("allow", R.string.split_mode_allow),
    Deny("deny", R.string.split_mode_deny);

    companion object {
        fun fromKey(key: String?): SplitMode? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Per-network app split policy. [packages] holds the user's selection only; the app's own
 * package is never stored and is handled by [toFilterPlan].
 */
data class SplitPolicy(
    val mode: SplitMode = SplitMode.All,
    val packages: Set<String> = emptySet(),
) {
    fun normalized(): SplitPolicy = copy(
        packages = packages.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    )

}

/** The two builder calls are mutually exclusive on Android, so exactly one set is used. */
data class AppFilterPlan(
    val allowed: Set<String>,
    val disallowed: Set<String>,
)

/**
 * Pure rule that resolves a policy into Builder calls.
 *
 * The app itself is always excluded from the tunnel to prevent a routing loop.
 * Android rejects calling both addAllowedApplication and addDisallowedApplication, so an
 * "allow" plan never contains disallowed packages. When the allow list becomes empty after
 * removing the app itself, the safe fallback is to disallow only the app (all others tunnel).
 */
fun SplitPolicy.toFilterPlan(selfPackage: String): AppFilterPlan {
    val clean = normalized().packages - selfPackage
    return when (mode) {
        SplitMode.All -> AppFilterPlan(allowed = emptySet(), disallowed = setOf(selfPackage))
        SplitMode.Allow ->
            if (clean.isEmpty()) {
                AppFilterPlan(allowed = emptySet(), disallowed = setOf(selfPackage))
            } else {
                AppFilterPlan(allowed = clean, disallowed = emptySet())
            }
        SplitMode.Deny -> AppFilterPlan(allowed = emptySet(), disallowed = clean + selfPackage)
    }
}

/** Plain-text codec; one policy per SharedPreferences entry. Kept Android-free for unit tests. */
object SplitPolicyCodec {
    private const val VERSION = "v1"

    fun encode(policy: SplitPolicy): String {
        val p = policy.normalized()
        return buildString {
            append(VERSION).append('\n')
            append(p.mode.key).append('\n')
            p.packages.sorted().forEach { append(it).append('\n') }
        }
    }

    fun decode(text: String?): SplitPolicy {
        if (text.isNullOrBlank()) return SplitPolicy()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2 || lines[0] != VERSION) return SplitPolicy()
        val mode = SplitMode.fromKey(lines[1]) ?: return SplitPolicy()
        return SplitPolicy(mode = mode, packages = lines.drop(2).toSet())
    }
}
