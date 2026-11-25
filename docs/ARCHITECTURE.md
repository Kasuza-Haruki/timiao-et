# Timiao-ET architecture (contract for all agents)

Goal: native Android EasyTier client. Kotlin + Jetpack Compose UI. Kernel = official EasyTier built as JNI .so.
App name: 缇喵. Package: `app.timiao.et`. minSdk 26, targetSdk 35, compileSdk 35. Single Activity.
Repo: D:\extra\timiao-et. Design draft: D:\extra\design\easytier-cat-glass.html (look at it for style).

## Kernel
- Pin one upstream tag (default v2.7.1; JNI API is complete there). Source is cloned to kernel/src (git-ignored).
- Output: kernel/out/<abi>/libeasytier_android_jni.so (+ libeasytier_ffi.so if needed), copied to app/src/main/jniLibs/<abi>/ (git-ignored).
- ABIs: arm64-v8a, x86_64 first. armeabi-v7a, x86 later.
- Kotlin wrapper: `com.easytier.jni.EasyTierJNI` (copy from upstream kotlin/ dir). Do not rename: the .so exports Java_com_easytier_jni_EasyTierJNI_*.

## Modules and owners (each agent edits ONLY its own paths)
| Owner | Paths |
|---|---|
| shell  | settings.gradle.kts, build.gradle.kts, gradle/, gradlew*, app/build.gradle.kts, app/src/main/AndroidManifest.xml (base), app/src/main/java/app/timiao/et/ui/** , app/src/main/res/values*/ (theme, strings) |
| core   | core/** (Android library `:core`, package `app.timiao.et.core`) |
| kernel | kernel/**, .github/workflows/**, scripts/** |
| assets | app/src/main/res/drawable/ic_*.xml, app/src/main/res/mipmap-anydpi-v26/**, docs/brand/** |
| test   | testdata/** (git-ignored), scripts/test/**, core/src/androidTest is NOT yours |
| service (later) | app/src/main/java/app/timiao/et/service/** |

## Core API the UI uses (package app.timiao.et.core)
```kotlin
data class NetworkConfig(...)                // all official fields; see core agent task
interface ConfigRepository { val networks: Flow<List<NetworkConfig>>; suspend fun save(c: NetworkConfig); suspend fun delete(id: String); suspend fun importToml(text: String): NetworkConfig; fun exportToml(c: NetworkConfig): String }
enum class LinkState { Idle, Connecting, Connected, Error }
data class PeerInfo(val hostname: String, val ipv4: String?, val cost: String /*p2p|relay(N)|local*/, val tunnel: String?, val latencyMs: Double?, val lossRate: Double?, val natType: String?, val txBytes: Long, val rxBytes: Long, val version: String?)
data class NetworkStatus(val state: LinkState, val myIpv4: String?, val peers: List<PeerInfo>, val txBps: Long, val rxBps: Long, val uptimeSec: Long, val error: String?)
interface Engine { val status: StateFlow<Map<String /*network id*/, NetworkStatus>>; suspend fun start(id: String); suspend fun stop(id: String) }
```
UI depends only on these interfaces. Provide `FakeEngine` + `FakeConfigRepository` (sample data) in core for previews.

## Style (from design draft D)
- Cream/peach/lilac background blobs, glass cards (translucent + 1px light border + top highlight), pill buttons (orange gradient), Siamese-cat mascot with big glass-bead eyes and an antenna that lights when connected.
- Colors: accent #FF8A5C, text #3A2C24, bg #FDF3EC; dark: bg #1B1622, accent #FFB08F.
- Glass blur: RenderEffect on Android 12+; plain translucent fallback below. Blur only on big surfaces, never per list row.
- Respect system dark mode, reduced-motion (Settings.Global.ANIMATOR_DURATION_SCALE == 0), font scale.
- Mascot is original: do not copy any real IP's unique details (no forehead bug, no embroidery pattern).

## Safety rules
- NEVER touch the host's running easytier (${LOCAL_EASYTIER}, process easytier-gui). Do not stop it, edit its config, or use port 15888/11010 on the host.
- NEVER commit secrets. The real network_secret lives only in testdata/ (git-ignored). Never print it.
- Real-connection tests run only inside the emulator, with a NEW hostname and NO instance_id (do not reuse the phone's id).
- Commit small, on branch `feat/<your-owner-name>`; do not push anywhere.
