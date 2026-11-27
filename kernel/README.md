# EasyTier kernel

This directory pins and builds the EasyTier native kernel used by the app.

- Upstream: <https://github.com/EasyTier/EasyTier>
- Pinned tag: see [`UPSTREAM_TAG`](UPSTREAM_TAG) (currently `v2.7.1`)
- Source checkout: `kernel/src/` (git-ignored, shallow clone)
- Build output: only `libeasytier_android_jni.so`, in `kernel/out/<abi>/` and
  `app/src/main/jniLibs/<abi>/`. `libeasytier_ffi.so` is not shipped; its code
  is linked statically into the JNI library.
- License: LGPL-3.0, see [`LICENSE-NOTICE.md`](LICENSE-NOTICE.md)

## Build

Windows:

```powershell
./scripts/build-kernel.ps1
./scripts/build-kernel.ps1 -Tag v2.7.1 -Abis x86_64,arm64-v8a
```

Linux / CI:

```bash
EASYTIER_PROXY= scripts/build-kernel.sh --tag v2.7.1 --abis arm64-v8a,x86_64
```

Both scripts:

1. Reuse `kernel/src` when it is already a clone, otherwise shallow-clone the tag.
   Set `EASYTIER_LOCAL_SRC` to an existing local EasyTier checkout to speed up
   cloning. Both scripts read this value from the environment only; they have
   no built-in local path.
2. Try every `kernel/patches/*.patch` in filename order with `git apply
   --check`. A patch is applied only when the check passes. Failures are
   recorded in `kernel/out/PATCH_STATUS.txt` (`<name>: APPLIED` or
   `<name>: FAILED`) and do not stop the build.
3. Run `cargo ndk -t <abi> --platform 26 build --release` for
   `easytier-contrib/easytier-ffi` and `easytier-contrib/easytier-android-jni`.
4. Copy only `libeasytier_android_jni.so` to `kernel/out/<abi>/` and
   `app/src/main/jniLibs/<abi>/`, and remove any stale `libeasytier_ffi.so`.
5. Print size, `NEEDED` entries, and 16 KB LOAD alignment.

Requirements: Rust 1.95 (from upstream `rust-toolchain.toml`), `cargo-ndk`,
Android NDK r27+ (`ANDROID_NDK_HOME`). `CARGO_TARGET_DIR` is honored; set it to
a large disk, for example `<CARGO_TARGET_DIR>` (default:
`kernel/src/target`).

Windows-gnu hosts also need a real MinGW toolchain (gcc, as, dlltool) and
libclang for `aws-lc-sys` and `kcp-sys`/bindgen. Rust's bundled windows-gnu gcc
is linker-only. Set `EASYTIER_MINGW_BIN` to a MinGW `bin` directory. The scripts
have no built-in local toolchain path. On a slow network, set
`EASYTIER_CRATES_MIRROR` to a sparse registry, for example
`sparse+https://mirrors.ustc.edu.cn/crates.io-index/`. If the pinned Rust 1.95
toolchain is unavailable, set `RUSTUP_TOOLCHAIN=stable` (must be >= 1.95).

Current build pins `-C link-arg=-Wl,-z,max-page-size=16384` so every LOAD
segment is aligned to `0x4000` for Android 15 16 KB pages.

### Verified build (v2.7.1)

| ABI | libeasytier_android_jni.so | NEEDED (JNI) | LOAD align |
|---|---|---|---|
| x86_64 | 20,479,224 bytes | liblog, libdl, libm, libc | 0x4000 |
| arm64-v8a | 18,110,896 bytes | liblog, libdl, libm, libc | 0x4000 |

Both ABIs were rebuilt from v2.7.1 with `0001` and `0002` APPLIED on
2026-11-26.

The JNI library does not list `libeasytier_ffi.so` in `NEEDED`; FFI code is
linked statically. Cargo also produces a separate `libeasytier_ffi.so` in the
target directory, but the build scripts do not copy it to `kernel/out` or
`jniLibs`, because the app never loads it.

## Patch policy

Patches live in `kernel/patches/*.patch`, numbered by application order. Both
build scripts reset `kernel/src` to the pinned tag, then try every patch in
filename order with `git apply --check`. Only patches that pass are applied.
Failures do not stop the build; each result is written to
`kernel/out/PATCH_STATUS.txt` and printed as a warning.

The two current patches are independent fallbacks for the network-secret leak:

- `0001-android-jni-log-info.patch` changes only
  `easytier-contrib/easytier-android-jni/src/logger.rs`
  (`LevelFilter::Debug` -> `LevelFilter::Info`). This file is small and rarely
  changes. This patch alone suppresses the debug-level route log that leaked
  the secret.
- `0002-network-identity-debug-redact.patch` replaces the `Debug` derive on
  `NetworkIdentity` (core and TOML config) with a handwritten `Debug` that
  prints `Some("***")` for `network_secret` and `network_secret_digest`. This
  redacts the secret at every log level, including `Info`, `Warn` and `Error`.

Failure handling:

- If only `0001` applies, `Info` still drops the debug log that caused the
  measured leak. If only `0002` applies, the structure is redacted at any
  level. Each patch is a fallback for the other.
- If both fail, the native library can write the secret to logcat again. The
  app cannot filter logcat output written directly by the native library.
  Treat this as a security regression and rebase the patches before shipping.
- The upstream-tracking workflow still builds and opens a PR when a patch
  fails. The PR title gets `[patch needs rebase]`, the body lists failed
  patches, and the workflow opens or updates an issue. The workflow does not
  fail because of a patch failure.

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
newest GitHub release (upstream flags most releases as prereleases, so the
newest one wins), compares it with `kernel/UPSTREAM_TAG` and does nothing unless
it is strictly higher, builds the two ABIs the app ships with NDK 27, applies patches on
a best-effort basis, checks the `runNetworkInstance` export and 16 KB LOAD alignment
with `scripts/check-elf.py`, and then opens a PR that updates `UPSTREAM_TAG`. A failed build does
not update the tag. A patch that no longer applies does not fail the workflow:
the PR still opens with `[patch needs rebase]` in the title, the failed patch
names go into the body, and an issue is opened or updated for manual rebase.
