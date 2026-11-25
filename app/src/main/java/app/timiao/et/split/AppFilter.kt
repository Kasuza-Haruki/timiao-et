package app.timiao.et.split

import android.net.VpnService
import app.timiao.et.BuildConfig

/**
 * Applies [policy] to a VpnService.Builder. Call this after the routes are added and before
 * [VpnService.Builder.establish]. The app's own package is always excluded to prevent a loop.
 */
fun applyAppFilter(
    builder: VpnService.Builder,
    policy: SplitPolicy,
    selfPackage: String = BuildConfig.APPLICATION_ID,
) {
    val plan = policy.toFilterPlan(selfPackage)
    var allowed = 0
    plan.allowed.forEach { pkg ->
        if (runCatching { builder.addAllowedApplication(pkg) }.isSuccess) allowed++
    }
    // No allowed package could be registered (e.g. all uninstalled). An empty allow list
    // would route every app — including this one — so exclude at least this app.
    if (allowed == 0) {
        val fallback = plan.disallowed.ifEmpty { setOf(selfPackage) }
        fallback.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
        }
    }
}
