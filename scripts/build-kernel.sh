#!/usr/bin/env bash
# Build the EasyTier Android JNI kernel (CI / Linux, also works in Git Bash).
#
# Usage:
#   scripts/build-kernel.sh [--tag v2.7.1] [--abis arm64-v8a,x86_64]
#                           [--no-jni-libs]
#
# Environment:
#   EASYTIER_PROXY       optional git HTTP(S) proxy, e.g. http://<proxy-host>:<port>
#   EASYTIER_LOCAL_SRC   optional local EasyTier clone used to speed up cloning
#   CARGO_TARGET_DIR     optional cargo target dir (default: kernel/cargo-target)
#   ANDROID_NDK_HOME     Android NDK (r27+ recommended)
set -euo pipefail

TAG="v2.7.1"
ABIS="arm64-v8a,x86_64"
COPY_JNI_LIBS=1
UPSTREAM_URL="https://github.com/EasyTier/EasyTier"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag|-Tag) TAG="${2:?missing tag}"; shift 2 ;;
    --abis|-Abis) ABIS="${2:?missing abis}"; shift 2 ;;
    --no-jni-libs) COPY_JNI_LIBS=0; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/kernel/src"
OUT_ROOT="$ROOT/kernel/out"
JNI_LIBS_ROOT="$ROOT/app/src/main/jniLibs"
PATCH_DIR="$ROOT/kernel/patches"

PROXY="${EASYTIER_PROXY:-}"
MIRROR="${EASYTIER_LOCAL_SRC:-}"
if [[ -n "$PROXY" ]]; then
  export HTTP_PROXY="$PROXY" HTTPS_PROXY="$PROXY" http_proxy="$PROXY" https_proxy="$PROXY"
fi

if [[ ! -d "$SRC/.git" ]]; then
  mkdir -p "$ROOT/kernel"
  cloned=0
  if [[ -n "$MIRROR" ]] && git -C "$MIRROR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Cloning local mirror $MIRROR"
    if git clone --depth 1 "$MIRROR" "$SRC"; then cloned=1; fi
  fi
  if [[ "$cloned" -ne 1 ]]; then
    rm -rf "$SRC"
    echo "Cloning $UPSTREAM_URL at $TAG"
    git clone --depth 1 --branch "$TAG" "$UPSTREAM_URL" "$SRC"
  fi
  git -C "$SRC" remote set-url origin "$UPSTREAM_URL"
fi

if ! git -C "$SRC" rev-parse --verify "refs/tags/$TAG" >/dev/null 2>&1; then
  echo "Fetching tag $TAG from upstream"
  git -C "$SRC" fetch --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
fi
git -C "$SRC" checkout -f "$TAG"
git -C "$SRC" reset --hard "$TAG"
git -C "$SRC" clean -fd -e target

echo "EasyTier source: $SRC @ $(git -C "$SRC" rev-parse --short HEAD) ($TAG)"

# Patches are best effort: a patch that no longer applies must not block the
# build. Record its status in kernel/out/PATCH_STATUS.txt and keep going.
mkdir -p "$OUT_ROOT"
PATCH_STATUS_FILE="$OUT_ROOT/PATCH_STATUS.txt"
: > "$PATCH_STATUS_FILE"
patch_failed=0
if [[ -d "$PATCH_DIR" ]]; then
  while IFS= read -r patch; do
    [[ -z "$patch" ]] && continue
    name="$(basename "$patch")"
    if git -C "$SRC" apply --check "$patch"; then
      if git -C "$SRC" apply "$patch"; then
        echo "Applied patch $name"
        echo "$name: APPLIED" >> "$PATCH_STATUS_FILE"
      else
        echo "WARNING: PATCH APPLY FAILED: $name. Continuing without it." >&2
        echo "$name: FAILED" >> "$PATCH_STATUS_FILE"
        patch_failed=1
      fi
    else
      echo "WARNING: PATCH NOT APPLICABLE: $name (upstream changed?). Continuing without it." >&2
      echo "$name: FAILED" >> "$PATCH_STATUS_FILE"
      patch_failed=1
    fi
  done < <(find "$PATCH_DIR" -maxdepth 1 -name '*.patch' | sort)
fi
if [[ "$patch_failed" -eq 1 ]]; then
  echo "WARNING: some patches were not applied; the build continues without them." >&2
  echo "WARNING: patch status: $PATCH_STATUS_FILE" >&2
fi

# Optional crates.io source replacement for slow networks.
if [[ -n "${EASYTIER_CRATES_MIRROR:-}" ]]; then
  mkdir -p "$SRC/.cargo"
  cfg="$SRC/.cargo/config.toml"
  if ! grep -q "easytier-crates-mirror" "$cfg" 2>/dev/null; then
    cat >> "$cfg" <<EOF

[source.crates-io]
replace-with = "easytier-crates-mirror"

[source.easytier-crates-mirror]
registry = "$EASYTIER_CRATES_MIRROR"
EOF
    echo "Crates mirror: $EASYTIER_CRATES_MIRROR"
  fi
fi

command -v cargo >/dev/null || { echo "cargo not found in PATH" >&2; exit 1; }
command -v rustup >/dev/null || { echo "rustup not found in PATH" >&2; exit 1; }

if ! cargo ndk --version >/dev/null 2>&1; then
  echo "cargo-ndk not installed; installing"
  cargo install cargo-ndk --locked
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK_HOME:-}}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
  NDK="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "Android NDK not found. Set ANDROID_NDK_HOME (NDK r27+ recommended)." >&2
  exit 1
fi
export ANDROID_NDK_HOME="$NDK"

# MSYS/Git Bash host: Rust's windows-gnu toolchain lacks a C compiler and as.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    if [[ -n "${EASYTIER_MINGW_BIN:-}" && -x "$EASYTIER_MINGW_BIN/gcc.exe" ]]; then
      export PATH="$EASYTIER_MINGW_BIN:$PATH"
      [[ -f "$EASYTIER_MINGW_BIN/libclang.dll" ]] && export LIBCLANG_PATH="$EASYTIER_MINGW_BIN"
      mingw_root="$(dirname "$EASYTIER_MINGW_BIN")"
      if [[ -d "$mingw_root/include" ]]; then
        export CFLAGS_x86_64_pc_windows_gnu="-I$mingw_root/include"
        export CXXFLAGS_x86_64_pc_windows_gnu="-I$mingw_root/include"
      fi
    fi
    ;;
esac


# remap-path-prefix keeps the builder's home and cargo paths out of panic/debug strings.
PINNED_FLAGS="-C link-arg=-Wl,-z,max-page-size=16384 --remap-path-prefix=$HOME=/build --remap-path-prefix=$SRC=/src"
# Keep build output outside kernel/src so a fresh clone of the upstream
# source does not wipe it; the workflow caches this directory.
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/kernel/cargo-target}"
mkdir -p "$CARGO_TARGET_DIR" "$OUT_ROOT"

declare -A TRIPLE_MAP=(
  ["arm64-v8a"]="aarch64-linux-android"
  ["armeabi-v7a"]="armv7-linux-androideabi"
  ["x86"]="i686-linux-android"
  ["x86_64"]="x86_64-linux-android"
)

IFS=',' read -r -a ABI_LIST <<< "$ABIS"
for abi in "${ABI_LIST[@]}"; do
  abi="$(echo "$abi" | xargs)"
  [[ -z "$abi" ]] && continue
  if [[ -z "${TRIPLE_MAP[$abi]:-}" ]]; then
    echo "Unsupported ABI '$abi'. Supported: ${!TRIPLE_MAP[*]}" >&2
    exit 1
  fi
done

CRATES=("easytier-contrib/easytier-ffi" "easytier-contrib/easytier-android-jni")
STATUS_LINES=()

for abi in "${ABI_LIST[@]}"; do
  abi="$(echo "$abi" | xargs)"
  [[ -z "$abi" ]] && continue
  triple="${TRIPLE_MAP[$abi]}"
  # Target-specific rustflags avoid passing Android link flags to host build scripts.
  flags_key="CARGO_TARGET_$(tr 'a-z-' 'A-Z_' <<< "$triple")_RUSTFLAGS"
  export "$flags_key=$PINNED_FLAGS"
  echo "== Installing rust target $triple"
  ( cd "$SRC" && rustup target add "$triple" )

  for crate in "${CRATES[@]}"; do
    echo "== cargo ndk -t $abi --platform 26 build --release ($crate)"
    ( cd "$SRC/$crate" && cargo ndk -t "$abi" --platform 26 build --release )
  done

  release_dir="$CARGO_TARGET_DIR/$triple/release"
  jni_so="$release_dir/libeasytier_android_jni.so"
  [[ -f "$jni_so" ]] || { echo "Missing build output: $jni_so" >&2; exit 1; }

  out_dir="$OUT_ROOT/$abi"
  mkdir -p "$out_dir"
  cp -f "$jni_so" "$out_dir/"
  # easytier-ffi is linked statically into the JNI library. The separate
  # libeasytier_ffi.so is never loaded by the app, so keep it out of the
  # shipped output and remove any stale copy from an earlier build.
  rm -f "$out_dir/libeasytier_ffi.so"

  if [[ "$COPY_JNI_LIBS" -eq 1 ]]; then
    mkdir -p "$JNI_LIBS_ROOT/$abi"
    cp -f "$jni_so" "$JNI_LIBS_ROOT/$abi/"
    rm -f "$JNI_LIBS_ROOT/$abi/libeasytier_ffi.so"
  fi

  size=$(wc -c < "$jni_so" | tr -d ' ')
  align="unknown"
  for py in python3 python; do
    if command -v "$py" >/dev/null 2>&1; then
      if "$py" "$ROOT/scripts/check-elf.py" "$jni_so" >/dev/null 2>&1; then
        align="yes, JNI export present"
      else
        align="NO, run scripts/check-elf.py for details"
      fi
      break
    fi
  done
  STATUS_LINES+=("$abi: $size bytes, LOAD=$align")
done

echo ""
echo "Build complete:"
for line in "${STATUS_LINES[@]}"; do echo "  $line"; done
