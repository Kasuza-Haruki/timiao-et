# Third-party notices

Timiao (缇喵) is distributed under the Apache License 2.0. See `LICENSE`.

This file lists third-party components that are packaged into the release
APK, plus the EasyTier kernel, which is a separate dynamically loaded
library. The list was checked against the resolved `releaseRuntimeClasspath`
with:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## Runtime libraries bundled in the APK

All libraries below are packages that the release build packages into the
APK. Unless stated otherwise, they are licensed under the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0).

| Component | Version | License | Upstream |
|---|---|---|---|
| AndroidX Core KTX | 1.15.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/core |
| AndroidX AppCompat | 1.6.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/appcompat |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/activity |
| AndroidX Lifecycle Runtime Compose | 2.8.7 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle |
| AndroidX Lifecycle ViewModel Compose | 2.8.7 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/lifecycle |
| AndroidX Navigation Compose | 2.8.5 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/navigation |
| Jetpack Compose BOM | 2024.12.01 | Apache-2.0 | https://developer.android.com/develop/ui/compose/bom |
| Compose UI / Foundation / Runtime | 1.7.6 (BOM) | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose |
| Compose Material 3 | 1.3.1 (BOM) | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material3 |
| Compose Material Icons Core | 1.7.6 (BOM) | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose-material |
| Kotlin standard library | 2.0.21 | Apache-2.0 | https://kotlinlang.org/ |
| kotlinx.coroutines (core, android) | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization (core, json) | 1.7.3 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| CameraX (core, camera2, lifecycle, view, video) | 1.4.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/camera |
| ZXing core | 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing |

The release runtime classpath also resolves transitive AndroidX and JetBrains
modules: `androidx.annotation`, `androidx.collection`, `androidx.arch.core`,
`androidx.savedstate`, `androidx.startup`, `androidx.tracing`,
`androidx.profileinstaller`, `androidx.emoji2`, `androidx.exifinterface`,
`androidx.vectordrawable`, `androidx.concurrent`,
`androidx.customview`, `androidx.fragment`, `androidx.loader`, and related
`-ktx`, `-jvm`, and `-android` artifacts. These are Apache-2.0 components from
the Android Open Source Project and the AndroidX project. Their license texts
are included in the upstream artifacts.

## Test-only dependencies

| Component | Version | License | Notes |
|---|---|---|---|
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 | Unit tests only, not packaged in the APK |
| AndroidX Test (ext-junit, runner) | 1.2.1 / 1.6.2 | Apache-2.0 | Instrumentation tests only, not packaged in the release APK |
| Compose UI Test | 2024.12.01 BOM | Apache-2.0 | Instrumentation tests only, not packaged in the release APK |

## EasyTier kernel

The app loads a separate native library, `libeasytier_android_jni.so`, built
from the EasyTier project.

- Project: EasyTier
- Upstream: https://github.com/EasyTier/EasyTier
- Pinned tag: `v2.7.1` (see `kernel/UPSTREAM_TAG`)
- Source at the pinned tag: https://github.com/EasyTier/EasyTier/tree/v2.7.1
- License: GNU Lesser General Public License v3.0 only (LGPL-3.0-only)
- License text: https://github.com/EasyTier/EasyTier/blob/main/LICENSE
- SPDX identifier: `LGPL-3.0-only`

The kernel is not statically linked into the app. It is loaded with
`System.loadLibrary("easytier_android_jni")` and used only through the JNI
facade. The app does not prevent replacing it with a compatible build.

### Our kernel patches

The shipped kernel is the upstream `v2.7.1` source plus two local patches in
`kernel/patches/`:

| Patch | Files | Purpose |
|---|---|---|
| `0001-android-jni-log-info.patch` | `easytier-contrib/easytier-android-jni/src/logger.rs` | Raises the Android JNI log level from `Debug` to `Info`, which stops the debug route log that wrote `network_secret` to logcat. |
| `0002-network-identity-debug-redact.patch` | `easytier-core/src/config/mod.rs`, `easytier-core/src/config/toml.rs` | Replaces the derived `Debug` on `NetworkIdentity` with a redacting implementation. `network_secret` and `network_secret_digest` print as `Some("***")` at every level. |

### Replacing the kernel library

LGPL-3.0 requires that users can replace the library with their own compatible
build. The app meets this requirement as follows:

1. Build `libeasytier_android_jni.so` from the upstream tag, with or without
   the patches above, using the scripts in `scripts/` or your own NDK
   toolchain. Keep the ABI (`arm64-v8a` or `x86_64`) and the exported JNI
   symbol `Java_com_easytier_jni_EasyTierJNI_runNetworkInstance`.
2. Put the library at `app/src/main/jniLibs/<abi>/libeasytier_android_jni.so`
   and build the app from source (`./gradlew :app:assembleRelease` with your
   own signing config), or
3. Unpack an existing APK, replace `lib/<abi>/libeasytier_android_jni.so`,
   re-sign the APK with `apksigner`, and install it.

No signature check, encryption, or anti-tamper mechanism in the app blocks
this replacement. The complete corresponding source is the upstream tag plus
the patches, together with the build scripts in `scripts/build-kernel.sh` and
`scripts/build-kernel.ps1`.
