# 网络切换保活建议（设计建议，未接入）

本文只给建议。`TimiaoVpnService.kt` 未改动。

## 内核现状（依据 EasyTier 上游源码）

1. 内核没有平台网络变化监听器：全仓库 grep `network_change` / `NetworkChange` / `ConnectivityManager` / `setUnderlyingNetworks`，没有 Android 侧实现。
2. 断线靠重连循环兜底：
   - `easytier-core/src/connectivity/manual/mod.rs`：`reconnect_interval` 默认 1s，死连接每分钟（interval）被重新连接。
   - `easytier-core/src/peers/conn/peer_conn.rs`：ping/pong 探活。Manual/Direct/Listener/UDP 打洞的 `max_ping_interval` 为 32s，TCP 打洞为 1s；超时后关闭连接并触发重连。
   - 结论：Wi-Fi ↔ 移动网络切换后，旧 socket 失效，内核会在约 1s 的循环里重连；最坏情况约 32s 才发现静默死链。TUN 设备本身不需要重建。
3. `bind_device`（SO_BINDTODEVICE）默认 false，见 `easytier-core/src/config/toml.rs`。Android 上不要开启：它把出口 socket 钉死在某个网卡，切换网络后必然断，且需要高权限。
4. `docs/socket-protection.md`：新内核要求宿主在 connect/bind 前对底层 socket 调用 VPN 保护。当前 App 在 `establishTun` 里调用了 `addDisallowedApplication(packageName)`，整个应用流量绕过自己的 VPN，内核 socket 走系统默认网络，因此暂时不需要 `protect()`。如果以后 JNI 支持 socket protector，应改为逐个 `VpnService.protect(fd)`。

## 建议做法（`TimiaoVpnService` 内）

```kotlin
private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        // null = 跟随系统默认网络；数组 = 明确声明承载 VPN 的底层网络
        setUnderlyingNetworks(arrayOf(network))
    }
    override fun onLost(network: Network) {
        setUnderlyingNetworks(null) // 回到系统默认网络，等待下一次 onAvailable
    }
}
```

- 在 `onCreate`/`onStartCommand` 注册 `registerDefaultNetworkCallback(defaultCallback)`，在 `onDestroy`/`onRevoke` 注销。
- 不要 `bindProcessToNetwork`：它会把进程钉在旧网络上，切换后不跟随。
- 不要因网络切换主动 `stop` 网络实例：内核会自己重连。若 40s 后仍全断，可作为兜底再 stop/start。
- `setUnderlyingNetworks` 的作用是告诉系统 VPN 流量走哪条底层网络，影响 VPN NetworkCapabilities、计费和多网络策略；它不会重绑内核已有 socket。内核 socket 能跟随切换，靠的是 `addDisallowedApplication` 让应用使用系统默认网络。
- 多网络场景下可用 `onCapabilitiesChanged` 判断 `VALIDATED`，只把已验证的网络传给 `setUnderlyingNetworks`。

## 自动连接的网络回调取舍（模拟器实测）

- `registerNetworkCallback(NetworkRequest, PendingIntent)` 能收到事件并唤醒进程，但 Android 15 模拟器实测：进程被 `kill -9` 后，系统在 `dumpsys connectivity` 里立刻 RELEASE 了这些 PendingIntent request，之后的 Wi-Fi 变化不再唤醒应用。README/API 注释里的“进程被杀也能唤醒”在本环境不成立。
- 因此 `AutoConnect` 用两层：
  1. 进程存活时用普通 `NetworkCallback`（default / Wi-Fi / cellular）立即响应。VPN 前台服务存活期内进程一定在，Wi-Fi ↔ 移动网络切换可靠。
  2. PendingIntent 回调作为进程存活期内的补充；开机、打开应用、收到广播时重新注册。
- 进程被杀且 VPN 未运行时的自动连接，由 BOOT_COMPLETED 或下次打开应用恢复。没有常驻服务时无法保证即时响应，这是 Android 后台限制的结果，不建议用 15 分钟级的 WorkManager 轮询换取电量消耗。
- FGS 后台启动限制（Android 12+）：网络广播里启动 VPN 可能被拒，`AutoConnect` 捕获异常并发通知，用户点按进入应用连接。
- 读 SSID：API 31+ 的进程内回调使用 `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` 拿 `WifiInfo`，因为普通 `getNetworkCapabilities()` 与 PendingIntent 回调都可能返回被抹掉的名称；仍以 `ACCESS_FINE_LOCATION` 为前提，读不到时 SSID 规则跳过而不是猜。

## 与本模块的关系

`AutoConnect` 用 `registerNetworkCallback(NetworkRequest, PendingIntent)` 监听可用性变化，只负责规则判断和启动/停止；不碰 TUN，也不依赖上述改动。两者互不冲突。
