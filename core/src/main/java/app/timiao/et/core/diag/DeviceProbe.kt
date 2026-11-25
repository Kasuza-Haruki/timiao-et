package app.timiao.et.core.diag

/**
 * Android-side facts. Implemented by the app so the core checks stay unit-testable.
 * Return true when the permission/state is fine.
 */
public interface DeviceProbe {
    public fun hasVpnPermission(): Boolean
    public fun isIgnoringBatteryOptimizations(): Boolean
    public fun hasNotificationPermission(): Boolean

    /**
     * True when a non-VPN network has INTERNET and is VALIDATED. False means
     * the device has no usable underlying network right now.
     */
    public fun hasUnderlyingNetwork(): Boolean
}
