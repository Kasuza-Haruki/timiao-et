package app.timiao.et.core.diag

/**
 * Android-side facts. Implemented by the app so the core checks stay unit-testable.
 * Only three checks are needed; return true when the permission/state is fine.
 */
public interface DeviceProbe {
    public fun hasVpnPermission(): Boolean
    public fun isIgnoringBatteryOptimizations(): Boolean
    public fun hasNotificationPermission(): Boolean
}
