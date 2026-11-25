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

Local changes to EasyTier, if any, are kept as patch files in
`kernel/patches/`. The build scripts apply them to the pinned upstream tag.
Current patch set: none.

## Source code availability

The complete corresponding source for the shipped binaries is:

1. The upstream EasyTier repository at the tag in `kernel/UPSTREAM_TAG`,
   optionally plus the patches in `kernel/patches/`; and
2. The build scripts in `scripts/build-kernel.*`, which reproduce the binaries.

If you redistribute the app, keep this notice and make the source above
available as required by LGPL-3.0. Users may replace the shared library with a
compatible build of EasyTier; the app must not prevent that replacement.
