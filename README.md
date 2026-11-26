<div align="center">

<img src="docs/brand/mascot.svg" width="360" alt="缇喵吉祥物：一只暹罗配色的大眼猫" />

# 缇喵 Timiao

**好看、好用、真正原生的 EasyTier 安卓客户端**

内核就是官方 EasyTier，界面为手机重新设计。<br/>
点一下就连上，在哪都能回到自己的网络。

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![EasyTier](https://img.shields.io/badge/EasyTier-v2.7.1-FF8A5C)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![中文 / English](https://img.shields.io/badge/i18n-中文%20%7C%20English-8A6448)

[English summary below ↓](#english)

<img src="docs/screenshots/hero.png" width="860" alt="缇喵截图：首页、节点拓扑、网络编辑" />

</div>

---

## 它是什么

[EasyTier](https://github.com/EasyTier/EasyTier) 是一个去中心化的组网工具：几台设备不管在哪，都能连成一个局域网，互相直连访问。

官方的安卓 App 由桌面端的跨平台界面改来，在手机上用起来不太顺手。缇喵把它重做成一个**安卓原生 App**：

- **内核不变**：直接编译官方 EasyTier 源码，打洞、中继、加密都和官方完全一致，可以和官方客户端放在同一个网络里。
- **界面重做**：用 Jetpack Compose 为手机重写。液态玻璃风格，有一只克制的小猫，支持深色模式和中英文。
- **功能更多**：官方 GUI 有的配置都有，还加了不少手机上才需要的便利功能。

## 亮点

<table>
<tr>
<td width="50%" valign="top">

### 🐾 一眼就懂的连接状态
首页显示一只猫：睡着是未连接，睁眼是已连接，掉线时会显示"正在重连"，不会假装还连着。
虚拟 IP、上下行速率、节点数量都在同一屏。

</td>
<td width="50%" valign="top">

### 🗺️ 看得见的网络拓扑
列表和拓扑两种视图。谁是直连、谁走中继、经过哪个节点转发，画得清清楚楚。
点开节点能看到每条连接的协议、延迟、丢包和流量。

</td>
</tr>
<tr>
<td valign="top">

### ⚙️ 官方全部配置，一个都不少
31 个高级开关、端口转发、子网代理、VPN Portal（可生成 WireGuard 客户端配置）、Magic DNS、IPv6……全部可以在表单里改。
也可以直接改 TOML 原文，表单和原文随时切换，字段不会丢。

</td>
<td valign="top">

### 📲 为手机准备的便利功能
- **扫码 / 链接分享**：一个二维码把配置发给朋友，默认不带密钥
- **按应用分流**：只让指定的 App 走 EasyTier
- **自动连接**：开机自启，按 Wi-Fi / 移动网络自动开关
- **桌面小部件 + 快捷设置磁贴**：不进 App 也能开关

</td>
</tr>
<tr>
<td valign="top">

### 🩺 连不上？一键诊断
自动检查配置、服务器可达性、NAT 类型、对端状态和手机权限。
用大白话告诉你问题在哪，再告诉你怎么改。

</td>
<td valign="top">

### 📊 流量心里有数
今日 / 本月用量、近 7 天柱状图。
可以设置"移动数据下不连接"或"超过 X MB 提醒"，不会偷跑流量。

</td>
</tr>
</table>

## 截图

| 首页（浅色） | 首页（深色） | 节点列表 | 拓扑（深色） |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/light-home-connected.png" width="200"/> | <img src="docs/screenshots/dark-home-connected.png" width="200"/> | <img src="docs/screenshots/light-peers-list.png" width="200"/> | <img src="docs/screenshots/dark-peers-topology.png" width="200"/> |

| 网络编辑 | 设置与工具 | 按应用分流 | 一键诊断 |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/light-edit.png" width="200"/> | <img src="docs/screenshots/dark-settings.png" width="200"/> | <img src="docs/screenshots/light-split.png" width="200"/> | <img src="docs/screenshots/light-diagnostics.png" width="200"/> |

| 扫码分享 | English UI | 桌面小部件 |
|:---:|:---:|:---:|
| <img src="docs/screenshots/light-share.png" width="200"/> | <img src="docs/screenshots/en-home-connected.png" width="200"/> | <img src="docs/screenshots/light-widget.png" width="200"/> |

<sub>截图使用虚构的演示数据，不对应任何真实网络。</sub>

## 快速上手

1. **安装**：在 [Releases](../../releases) 下载最新的 `timiao-*-release.apk`，在手机上打开安装。
2. **添加网络**，任选一种：
   - 点「创建网络」，填写网络名、密钥和服务器地址；
   - 扫描朋友分享的二维码，或打开分享链接；
   - 导入现有的 `.toml` 配置文件（官方客户端导出的也可以）。
3. **连接**：点首页的「连接」，第一次需要允许 VPN 权限。连上后就能用虚拟 IP 访问网络里的其他设备。

> 💡 下拉手机的快捷设置，添加「缇喵」磁贴，以后一键开关。

## 和官方 GUI 的对照

| | 官方 GUI | 缇喵 |
|---|:---:|:---:|
| 内核 | EasyTier | **同一个官方内核** |
| 基础 / 高级配置、端口转发、子网代理 | ✅ | ✅ |
| VPN Portal 与 WireGuard 客户端配置 | ✅ | ✅ |
| Magic DNS、IPv6 | ✅ | ✅ |
| 节点表、本机信息、事件日志 | ✅ | ✅ |
| 同时连接多个网络 | ✅ | ✅（共用一张虚拟网卡） |
| ACL 规则 | 结构化编辑 | 原文编辑 |
| 扫码分享、按应用分流、自动连接 | — | ✅ |
| 一键诊断、流量统计、桌面小部件 | — | ✅ |

逐项对照和源码出处见 [`docs/parity-audit.md`](docs/parity-audit.md)。

## 内核与更新

- 内核来自官方 EasyTier 源码，版本固定在 [`kernel/UPSTREAM_TAG`](kernel/UPSTREAM_TAG)。
- 仓库里的 [GitHub Actions](.github/workflows/track-upstream.yml) 每天检查官方新版本。有新版本时会自动编译内核，并开一个 PR 等人工确认。目前 App 打包 64 位手机（arm64-v8a）和模拟器（x86_64）两种架构。
- 安卓不允许 App 下载并加载新的原生代码，所以内核升级 = 安装新版 App。设置页「关于」会显示当前内核版本，并提示官方是否有新版本。

## 隐私与安全

- **配置加密存储**：网络密钥等配置用 Android Keystore 加密后保存在本机，不上传任何地方。
- **日志不泄露密钥**：官方内核会把网络密钥写进系统日志。缇喵给内核打了两个小补丁（[`kernel/patches/`](kernel/patches)），日志里的密钥显示为 `***`。补丁失效时，release 构建会直接失败；debug 构建不阻止。CI 仍会开一个 PR 便于人工 rebase，但会打上 `do not merge` 标签并在正文顶部警告。
- **分享默认不含密钥**：二维码、链接和导出文件默认去掉密钥；TOML 原文里的密钥默认遮盖。
- **App 自身的联网**：只有「检查更新」会访问 `api.github.com`，查询本项目的 Releases 和 EasyTier 的 Releases。打开设置页「关于」时会自动检查一次，也可以点「检查更新」手动触发。请求只发送 GET 和固定 User-Agent，不上传配置、网络密钥、设备标识或使用统计。
- **EasyTier 与诊断**：连接时，EasyTier 只按你的配置连接服务器和节点；诊断页只在你点「开始诊断」时，向你填写的服务器地址发 TCP/UDP 探测。
- **没有遥测**：没有统计、广告或崩溃上报 SDK。release APK 不含 Google ML Kit、Firebase 或 Google Data Transport。

## 从源码构建

需要 JDK 17 和 Android SDK（platform 35、NDK 27）。

```bash
# 1. 编译 EasyTier 内核（需要 Rust 与 cargo-ndk）
./scripts/build-kernel.sh --tag "$(cat kernel/UPSTREAM_TAG)" --abis arm64-v8a,x86_64
#    Windows：./scripts/build-kernel.ps1

# 2. 编译 App
./gradlew :app:assembleDebug

# 正式包：用环境变量指向你的签名配置（storeFile / storePassword / keyAlias / keyPassword）
TIMIAO_SIGNING=/path/to/signing.properties ./gradlew :app:assembleRelease
```

项目结构：

```
core/      纯 Kotlin：配置模型、TOML、内核桥接、状态解析、诊断
app/       Android App：界面（Compose）、VPN 服务、分流、自动连接、小部件
kernel/    官方内核的版本、补丁、许可说明
scripts/   内核编译脚本
docs/      架构说明、官方对照、截图
```

更多设计细节见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 已知限制

- ACL 规则目前只能编辑原文，还没有结构化编辑器。
- 两个网络不能使用重叠的网段同时运行，缇喵会拒绝并提示。
- App 进程被系统强制杀死后，通知栏可能残留一条旧通知，再次打开 App 即可清除。

## 参与贡献

欢迎提 Issue 和 PR。开发环境、模块划分、构建与测试步骤见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。提交前请运行：

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

## 致谢与许可

- 缇喵代码以 [Apache License 2.0](LICENSE) 授权。
- 网络内核：[EasyTier](https://github.com/EasyTier/EasyTier)，以 LGPL-3.0 授权，作为独立的动态库加载。详见 [`kernel/LICENSE-NOTICE.md`](kernel/LICENSE-NOTICE.md)。
- 第三方组件与内核补丁清单见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。App 内「设置 > 关于 > 开源许可」也可查看。
- 小猫是缇喵的原创形象，与任何现有角色无关。

## English

Timiao (缇喵) is a native Android client for [EasyTier](https://github.com/EasyTier/EasyTier), built with Kotlin and Jetpack Compose.

- The network kernel is the official EasyTier source pinned in `kernel/UPSTREAM_TAG`, loaded as a separate dynamic library.
- Features: Liquid Glass UI, QR/link sharing, per-app split tunneling, auto-connect, diagnostics, traffic stats, a home-screen widget, and the full official configuration.
- The app code is licensed under Apache-2.0 (see `LICENSE`); the EasyTier kernel is LGPL-3.0. See `THIRD-PARTY-NOTICES.md` for all components and their licenses.
- The only app-initiated network request is the update check to `api.github.com`: it runs once when the About section opens, or on demand. EasyTier itself connects only to the servers and peers in your configuration.
- Build steps, module layout, and tests are in `docs/ARCHITECTURE.md`. The UI is available in Chinese and English.

<div align="center">
<sub>Made with 🐾 for people who keep their devices in one network.</sub>
</div>
