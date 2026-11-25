# EasyTier kernel

This directory pins and builds the EasyTier native kernel used by the app.

- Upstream: <https://github.com/EasyTier/EasyTier>
- Pinned tag: see [`UPSTREAM_TAG`](UPSTREAM_TAG) (currently `v2.7.1`)
- Source checkout: `kernel/src/` (git-ignored, shallow clone)
- Build output: `kernel/out/<abi>/` and `app/src/main/jniLibs/<abi>/`
- License: LGPL-3.0, see [`LICENSE-NOTICE.md`](LICENSE-NOTICE.md)

## Build

Windows:

```powershell
./scripts/build-kernel.ps1
./scripts/build-kernel.ps1 -Tag v2.7.1 -Abis x86_64,arm64-v8a
```

Linux / CI:

```bash
EASYTIER_PROXY= scripts/build-kernel.sh --tag v2.7.1 --abis arm64-v8a,armeabi-v7a,x86,x86_64
```

Both scripts:

1. Reuse `kernel/src` when it is already a clone, otherwise shallow-clone the tag.
   `EASYTIER_LOCAL_SRC` (defaults to an existing
   `D:\extra\research\easytier-src` on Windows) is used as a local mirror to
   speed up cloning.
2. Apply every `kernel/patches/*.patch` in filename order with `git apply`.
3. Run `cargo ndk -t <abi> --platform 26 build --release` for
   `easytier-contrib/easytier-ffi` and `easytier-contrib/easytier-android-jni`.
4. Copy `libeasytier_android_jni.so` (and `libeasytier_ffi.so` when produced)
   to `kernel/out/<abi>/` and `app/src/main/jniLibs/<abi>/`.
5. Print size, `NEEDED` entries, and 16 KB LOAD alignment.

Requirements: Rust 1.95 (from upstream `rust-toolchain.toml`), `cargo-ndk`,
Android NDK r27+ (`ANDROID_NDK_HOME`). `CARGO_TARGET_DIR` is honored; set it to
a large disk, for example `${ANDROID_ENV}\cargo-target`.

Windows-gnu hosts also need a real MinGW toolchain (gcc, as, dlltool) and
libclang for `aws-lc-sys` and `kcp-sys`/bindgen. Rust's bundled windows-gnu gcc
is linker-only. Set `EASYTIER_MINGW_BIN` to a MinGW `bin` directory; the script
also auto-detects `${ANDROID_ENV}\mingw64\bin`. On a slow network, set
`EASYTIER_CRATES_MIRROR` to a sparse registry, for example
`sparse+https://mirrors.ustc.edu.cn/crates.io-index/`. If the pinned Rust 1.95
toolchain is unavailable, set `RUSTUP_TOOLCHAIN=stable` (must be >= 1.95).

Current build pins `-C link-arg=-Wl,-z,max-page-size=16384` so every LOAD
segment is aligned to `0x4000` for Android 15 16 KB pages.

### Verified build (v2.7.1)

| ABI | libeasytier_android_jni.so | NEEDED | LOAD align |
|---|---|---|---|
| x86_64 | 20,491,640 bytes | liblog, libdl, libm, libc | 0x4000 |
| arm64-v8a | 18,122,544 bytes | liblog, libdl, libm, libc | 0x4000 |

The JNI library does not list `libeasytier_ffi.so` in `NEEDED`; FFI code is
linked statically. The separate `libeasytier_ffi.so` is only for C ABI users.

## Patch policy

Patches live in `kernel/patches/*.patch`, numbered by application order. The
build fails if a patch no longer applies, so an upstream update cannot silently
drop a local fix. Re-validate patches on every upstream bump.

## Does v2.7.1 need an `attachMobileTunFd` patch?

**Decision: no patch is required for multi-instance shared TUN.**

Evidence from the v2.7.1 source:

- `easytier-contrib/easytier-ffi/src/instance_api.rs::set_tun_fd` resolves the
  instance name and calls `manager.attach_tun_fd(instance_id, fd)`.
- `easytier-core/src/instance/manager.rs::attach_tun_fd` forwards to the
  instance, then to `NativeInstanceRuntimeHost::attach_tun_fd` and
  `tun_mobile.rs::attach_fd(fd)`.
- `easytier/src/instance/virtual_nic.rs::NicCtx::new` forces `dev_name = ""`
  on mobile and always selects `NicBackend::Shared(registry.create_member(...))`.
  The registry key is `(net_ns, "")`, identical for every instance, so all
  instances join one `SharedVirtualNic`.
- The first `setTunFd` starts the shared dispatcher
  (`SharedVirtualNicDispatcher::start_for_mobile`) with the VpnService fd.
  Later `setTunFd` calls route through
  `dispatcher.rs::apply_tun_update`, which reopens the TUN halves on the same
  raw fd (`close_fd_on_drop(false)`).
- Each member's addresses and routes are applied through
  `run_for_mobile -> apply_mobile_tun_claims -> replace_claims`, with
  `SharedIfConfig::ensure_disjoint` enforcing non-overlapping claims.

Consequences:

- Calling `setTunFd(name, fd)` once per running instance shares one Android TUN
  fd. Packet demultiplexing uses the shared source table, not one reader per
  instance.
- The kernel-level `attach_mobile_tun_fd(fd, instance_ids)` (available in
  `easytier/src/instance/factory/mobile_tun.rs`) is a higher-level coordinator:
  it attaches all requested instances in one call, returns the aggregate
  `MobileTunConfig` (addresses, routes, DNS, rejected members), and guarantees
  that a rejected member never replaces or tears down the shared fd.
- The shipped JNI (`setTunFd`, `collectNetworkInfos`) does not expose that
  coordinator. The app does not need it today: it creates one VpnService fd,
  starts each instance, then calls `setTunFd` per instance and derives routes
  from its own `NetworkConfig`.

Known limits of the per-instance path:

- Attachments are sequential and each call reopens the dispatcher TUN halves,
  causing a short packet pause.
- There is no atomic all-or-nothing attach and no aggregated config result.

Add a JNI patch exposing
`attachMobileTunFd(fd: Int, instanceNames: Array<String>?)` only if the app
needs one atomic attach with aggregate route/DNS data. No patch is present now.

## Notes on libraries

`easytier-android-jni` depends on `easytier-ffi` as a normal Rust dependency,
so the FFI code is linked into `libeasytier_android_jni.so`. The build also
produces `libeasytier_ffi.so` because the crate is `cdylib`; check `NEEDED`
before loading it. The JNI `.so` does not require the separate FFI `.so` when
`NEEDED` does not list it.

## Upstream tracking

`.github/workflows/track-upstream.yml` runs daily and on demand. It resolves the
latest stable GitHub release (prereleases ignored unless requested), compares it
with `kernel/UPSTREAM_TAG`, builds all four ABIs with NDK 27, applies patches,
checks the `runNetworkInstance` export and `0x4000` LOAD alignment, and then
opens a PR that updates `UPSTREAM_TAG`. A failed build does not update the tag.
