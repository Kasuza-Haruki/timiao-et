# Timiao-ET architecture

Goal: native Android EasyTier client. Kotlin + Jetpack Compose UI. Kernel = official EasyTier built as a JNI `.so`.
App name: 缇喵. Package: `app.timiao.et`. minSdk 26, targetSdk 35, compileSdk 35. Single Activity.
Repository root: this directory. Design references are kept outside the repository.

## Kernel
- Pin one upstream tag (default v2.7.1; the JNI API is complete there). Source is cloned to `kernel/src` (git-ignored).
- Output: `kernel/out/<abi>/libeasytier_android_jni.so` (+ `libeasytier_ffi.so` if needed), copied to `app/src/main/jniLibs/<abi>/` (git-ignored).
- ABIs: arm64-v8a, x86_64 first. armeabi-v7a, x86 later.
- Kotlin wrapper: `com.easytier.jni.EasyTierJNI` (copy from the upstream `kotlin/` directory). Do not rename it: the `.so` exports `Java_com_easytier_jni_EasyTierJNI_*`.

## Modules
| Module | Paths | Responsibility |
|---|---|---|
| shell | `settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew*`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/app/timiao/et/ui/**`, `app/src/main/res/values*/` | App shell, navigation, theme, strings |
| core | `core/**` (Android library `:core`, package `app.timiao.et.core`) | Config model, TOML codec, engine, status parser |
| kernel | `kernel/**`, `.github/workflows/**`, `scripts/**` | EasyTier source pin, patches, native build |
| assets | `app/src/main/res/drawable/ic_*.xml`, `app/src/main/res/mipmap-anydpi-v26/**`, `docs/brand/**` | Icons and brand drawing |
| service | `app/src/main/java/app/timiao/et/service/**` | VpnService, TUN lifecycle, foreground service |

## Core API the UI uses (package app.timiao.et.core)
```kotlin
data class NetworkConfig(...)                // all official fields
interface ConfigRepository { val networks: Flow<List<NetworkConfig>>; suspend fun save(c: NetworkConfig); suspend fun delete(id: String); suspend fun importToml(text: String): NetworkConfig; fun exportToml(c: NetworkConfig): String }
enum class LinkState { Idle, Connecting, Connected, Error }
data class PeerInfo(val hostname: String, val ipv4: String?, val cost: String /*p2p|relay(N)|local*/, val tunnel: String?, val latencyMs: Double?, val lossRate: Double?, val natType: String?, val txBytes: Long, val rxBytes: Long, val version: String?)
data class NetworkStatus(val state: LinkState, val myIpv4: String?, val peers: List<PeerInfo>, val txBps: Long, val rxBps: Long, val uptimeSec: Long, val error: String?)
interface Engine { val status: StateFlow<Map<String /*network id*/, NetworkStatus>>; suspend fun start(id: String); suspend fun stop(id: String) }
```
UI depends only on these interfaces. `FakeEngine` + `FakeConfigRepository` provide sample data for previews.

## Style
- Cream/peach/lilac background blobs, glass cards (translucent + 1px light border + top highlight), pill buttons (orange gradient), Siamese-cat mascot with big glass-bead eyes and an antenna that lights when connected.
- Colors: accent #FF8A5C, text #3A2C24, bg #FDF3EC; dark: bg #1B1622, accent #FFB08F.
- Glass blur: RenderEffect on Android 12+; plain translucent fallback below. Blur only on big surfaces, never per list row.
- Respect system dark mode, reduced-motion (Settings.Global.ANIMATOR_DURATION_SCALE == 0), font scale.
- Mascot is original: do not copy any real IP's unique details (no forehead bug, no embroidery pattern).

## Development and testing guide

### Prerequisites
- JDK 17 and Android SDK with platform 35.
- Gradle 8.11.1. Use the wrapper, or a local Gradle install at `<GRADLE_HOME>`.
- Set `JAVA_HOME=<JDK17>` and `ANDROID_HOME=<ANDROID_SDK>`.
- In Git Bash, set `MSYS_NO_PATHCONV=1` when you pass Android paths to `adb`.

### Build commands
```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```
If the wrapper download times out, use the local Gradle:
```bash
"<GRADLE_HOME>/bin/gradle" --no-daemon :app:assembleDebug
```
Check the APK timestamp after every build. Show build errors with `grep -E "^e:|FAIL"`. Do not hide a failed build.

The app needs the native kernel. Build it with `scripts/build-kernel.*` (see `kernel/README.md`), then copy the output into `app/src/main/jniLibs/<abi>/` (git-ignored).

### Unit tests
```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest
```
Test data uses document address ranges (203.0.113.0/24, 198.51.100.0/24, 192.0.2.0/24) and the `10.144.144.0/24` demo network. Replace sample data only with other documentation values.

### Emulator verification
1. Start an emulator (create or reuse an AVD):
   ```bash
   emulator -avd <AVD_NAME> -no-snapshot-save -no-audio
   ```
2. Install the debug build:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Use a test config. Debug builds read a developer-provided TOML file from `getExternalFilesDir(null)/emu.toml`. This file is not in the repository. Do not commit it and do not print `network_secret`.
4. Debug-only hook: `DebugReceiver` accepts `DEBUG_IMPORT`, `DEBUG_START`, `DEBUG_STOP`, `DEBUG_DUMP`, `DEBUG_SPLIT`, `DEBUG_LIST` and `DEBUG_PREPARE`. It exists only in debug builds.
5. Demo mode (`DEBUG_DEMO`): debug builds include preview activities backed by `FakeConfigRepository` and seeded traffic data. They do not connect to a real network. Use them for UI and screenshot checks:
   ```bash
   adb shell am start -n app.timiao.et/.debug.ShareDebugActivity
   adb shell am start -n app.timiao.et/.debug.DiagnosticsDebugActivity
   ```
6. Check both themes:
   ```bash
   adb shell cmd uimode night yes
   adb shell cmd uimode night no
   ```
7. Disconnect regression check: after you stop the VPN, `tun0` must disappear and the foreground service must stop. Run this check whenever service code changes:
   ```bash
   adb shell ip link show tun0
   adb shell dumpsys activity services app.timiao.et
   ```

### Contributor rules
- Do not modify or stop an EasyTier instance that you do not own. If ports 15888 or 11010 are already in use, choose other ports.
- Never commit secrets. The network secret belongs only in your local test config.
- Run a real-connection test only in an emulator, with a new hostname and without reusing an existing instance ID.
- Commit small changes. Do not push to a shared repository unless you have permission.

## Roadmap
Phase 2 features: per-app split tunnelling, auto-connect rules (boot, Wi-Fi SSID / mobile data), one-tap diagnostics, home-screen widget, multi-network on one TUN.
Phase 3 features: QR scan/share config, copy/launch actions on peers (ssh/rdp via installed apps), traffic limit on mobile data, battery/traffic stats, app + kernel update check (kernel tag shown in About, app self-update from GitHub releases), English UI (`strings.xml` en) + working language switch.
