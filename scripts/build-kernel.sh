#!/usr/bin/env bash
# Build the EasyTier Android JNI kernel (CI / Linux, also works in Git Bash).
#
# Usage:
#   scripts/build-kernel.sh [--tag v2.7.1] [--abis arm64-v8a,x86_64]
#                           [--no-jni-libs]
#
# Environment:
#   EASYTIER_PROXY       optional git HTTP(S) proxy, e.g. http://127.0.0.1:PORT
#   EASYTIER_LOCAL_SRC   optional local EasyTier clone used to speed up cloning
#   CARGO_TARGET_DIR     optional cargo target dir (default: kernel/src/target)
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
if [[ -z "$MIRROR" && -d "/d/extra/research/easytier-src/.git" ]]; then
  MIRROR="/d/extra/research/easytier-src"
fi
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

if [[ -d "$PATCH_DIR" ]]; then
  while IFS= read -r patch; do
    [[ -z "$patch" ]] && continue
    echo "Applying patch $(basename "$patch")"
    git -C "$SRC" apply --check "$patch"
    git -C "$SRC" apply "$patch"
  done < <(find "$PATCH_DIR" -maxdepth 1 -name '*.patch' | sort)
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

READELF="$(find "$NDK/toolchains/llvm/prebuilt" -path '*/bin/llvm-readelf' -type f 2>/dev/null | head -1 || true)"

PINNED_FLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }$PINNED_FLAGS"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$SRC/target}"
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
  echo "== Installing rust target $triple"
  rustup target add "$triple"

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
  [[ -f "$release_dir/libeasytier_ffi.so" ]] && cp -f "$release_dir/libeasytier_ffi.so" "$out_dir/"

  if [[ "$COPY_JNI_LIBS" -eq 1 ]]; then
    mkdir -p "$JNI_LIBS_ROOT/$abi"
    cp -f "$out_dir"/*.so "$JNI_LIBS_ROOT/$abi/"
  fi

  size=$(wc -c < "$jni_so" | tr -d ' ')
  needed=""
  align="unknown"
  if [[ -n "$READELF" ]]; then
    needed="$("$READELF" -d "$jni_so" | grep -o 'NEEDED.*' | tr '\n' ';' || true)"
    load_lines="$("$READELF" -lW "$jni_so" | grep -E '^[[:space:]]*LOAD' || true)"
    if [[ -n "$load_lines" ]] && ! grep -qv '0x4000' <<<"$load_lines"; then
      align="yes"
    else
      align="NO"
    fi
  fi
  STATUS_LINES+=("$abi: $size bytes, 16KB-aligned LOAD=$align, NEEDED=${needed:-<none>}")
done

echo ""
echo "Build complete:"
for line in "${STATUS_LINES[@]}"; do echo "  $line"; done
