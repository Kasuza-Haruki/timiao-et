# Third-party license notice: EasyTier kernel

The native kernel libraries in this repository (`libeasytier_android_jni.so`
and, when produced, `libeasytier_ffi.so`) are built from the EasyTier project.

- Project: EasyTier
- Upstream: https://github.com/EasyTier/EasyTier
- Pinned source tag: see `kernel/UPSTREAM_TAG`
- Exact source: https://github.com/EasyTier/EasyTier/tree/<tag> (for example
  https://github.com/EasyTier/EasyTier/tree/v2.7.1)
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- License text: https://github.com/EasyTier/EasyTier/blob/main/LICENSE
- SPDX identifier: `LGPL-3.0-only`

## How the app uses the kernel

The kernel is distributed as a separate, dynamically loaded shared library
(`System.loadLibrary("easytier_android_jni")`). The app communicates with it
only through the JNI facade `com.easytier.jni.EasyTierJNI`. The library remains
a separate dynamic component; it is not statically linked into the app.

Local changes to EasyTier are kept as patch files in `kernel/patches/`. The
build scripts apply them to the pinned upstream tag. The current patch set
has two patches:

1. `0001-android-jni-log-info.patch` changes
   `easytier-contrib/easytier-android-jni/src/logger.rs` and raises the
   Android JNI log level from `Debug` to `Info`. This stops the debug route
   log that wrote `network_secret` to logcat.
2. `0002-network-identity-debug-redact.patch` changes
   `easytier-core/src/config/mod.rs` and
   `easytier-core/src/config/toml.rs`. It replaces the derived `Debug` on
   `NetworkIdentity` with a handwritten implementation that prints
   `network_secret` and `network_secret_digest` as `Some("***")` at every log
   level.

If a patch does not apply, the build records it as `FAILED` in
`kernel/out/PATCH_STATUS.txt`. A release build then refuses to run. The
upstream-tracking workflow still opens a PR for manual rebase, but marks it
`do not merge`.

## Source code availability

The complete corresponding source for the shipped binaries is:

1. The upstream EasyTier repository at the tag in `kernel/UPSTREAM_TAG`,
   optionally plus the patches in `kernel/patches/`; and
2. The build scripts in `scripts/build-kernel.*`, which reproduce the binaries.

If you redistribute the app, keep this notice and make the source above
available as required by LGPL-3.0.

## Replacing the shared library

Users may replace the shared library with a compatible build of EasyTier.
Android does not allow changing a library inside a signed APK in place, so
replacement means one of these:

1. Build the app from source with the replacement
   `app/src/main/jniLibs/<abi>/libeasytier_android_jni.so`, or
2. Unpack the APK, replace `lib/<abi>/libeasytier_android_jni.so`, then
   re-sign and reinstall the APK.

No signature check, encryption, or anti-tamper mechanism in the app prevents
this replacement. See `THIRD-PARTY-NOTICES.md` for the exact steps and the
required ABI and JNI export.
