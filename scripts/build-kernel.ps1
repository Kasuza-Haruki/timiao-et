<#
.SYNOPSIS
  Build the EasyTier Android JNI kernel for this repository.

.DESCRIPTION
  Clones (or reuses) EasyTier at a pinned tag into kernel/src, applies patches
  from kernel/patches, builds easytier-ffi and easytier-android-jni with
  cargo-ndk, and copies the resulting .so files to kernel/out/<abi> and
  app/src/main/jniLibs/<abi>.

.EXAMPLE
  ./scripts/build-kernel.ps1
  ./scripts/build-kernel.ps1 -Tag v2.7.1 -Abis x86_64,arm64-v8a
#>
param(
    [string]$Tag = "v2.7.1",
    [string]$Abis = "arm64-v8a,x86_64",
    # Defaults come from the environment only. Set EASYTIER_PROXY to use a
    # git HTTP(S) proxy; set EASYTIER_LOCAL_SRC to reuse an existing clone.
    [string]$Proxy = $env:EASYTIER_PROXY,
    [string]$LocalMirror = $env:EASYTIER_LOCAL_SRC,
    [string]$TargetDir = $(if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { "" }),
    [switch]$NoJniLibs
)

$ErrorActionPreference = "Stop"
$UpstreamUrl = "https://github.com/EasyTier/EasyTier"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Src = Join-Path $Root "kernel/src"
$OutRoot = Join-Path $Root "kernel/out"
$JniLibsRoot = Join-Path $Root "app/src/main/jniLibs"
$PatchDir = Join-Path $Root "kernel/patches"

# Read user-level environment variables if this shell does not have them.
foreach ($name in @("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "JAVA_HOME", "PROTOC")) {
    if (-not (Get-Item "Env:$name" -ErrorAction SilentlyContinue)) {
        $value = (Get-ItemProperty -Path "HKCU:\Environment" -Name $name -ErrorAction SilentlyContinue).$name
        if ($value) { Set-Item "Env:$name" $value }
    }
}

if ($Proxy) {
    $env:HTTP_PROXY = $Proxy
    $env:HTTPS_PROXY = $Proxy
    $env:http_proxy = $Proxy
    $env:https_proxy = $Proxy
}

$TripleMap = @{
    "arm64-v8a"   = "aarch64-linux-android"
    "armeabi-v7a" = "armv7-linux-androideabi"
    "x86"         = "i686-linux-android"
    "x86_64"      = "x86_64-linux-android"
}
$AbiList = $Abis.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
foreach ($abi in $AbiList) {
    if (-not $TripleMap.ContainsKey($abi)) {
        throw "Unsupported ABI '$abi'. Supported: $($TripleMap.Keys -join ', ')"
    }
}

if (-not $TargetDir) { $TargetDir = Join-Path $Src "target" }
if (-not [System.IO.Path]::IsPathRooted($TargetDir)) { $TargetDir = Join-Path $Root $TargetDir }

function Invoke-Git {
    param([string]$Repo, [string[]]$GitArgs)
    if ($Repo) {
        & git -C $Repo @GitArgs
    } else {
        & git @GitArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs -join ' ') failed ($LASTEXITCODE)" }
}

# 1. Source checkout -------------------------------------------------------
if (-not (Test-Path (Join-Path $Src ".git"))) {
    New-Item -ItemType Directory -Force (Split-Path $Src -Parent) | Out-Null
    $cloned = $false
    if ($LocalMirror -and (Test-Path (Join-Path $LocalMirror ".git"))) {
        Write-Host "Cloning local mirror $LocalMirror"
        & git clone --depth 1 $LocalMirror $Src
        $cloned = ($LASTEXITCODE -eq 0)
    }
    if (-not $cloned) {
        if (Test-Path $Src) { Remove-Item -Recurse -Force $Src }
        Write-Host "Cloning $UpstreamUrl at $Tag"
        Invoke-Git "" @("clone", "--depth", "1", "--branch", $Tag, $UpstreamUrl, $Src)
    }
    Invoke-Git $Src @("remote", "set-url", "origin", $UpstreamUrl)
}

& git -C $Src rev-parse --verify "refs/tags/$Tag" 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Fetching tag $Tag from upstream"
    Invoke-Git $Src @("fetch", "--depth", "1", "origin", "refs/tags/$Tag`:refs/tags/$Tag")
}
Invoke-Git $Src @("checkout", "-f", $Tag)
Invoke-Git $Src @("reset", "--hard", $Tag)
& git -C $Src clean -fd -e target | Out-Null

$head = (& git -C $Src rev-parse --short HEAD).Trim()
Write-Host "EasyTier source: $Src @ $head ($Tag)"

# 2. Apply patches (best effort) -------------------------------------------
# A patch that no longer applies must not block the build. Record its status
# in kernel/out/PATCH_STATUS.txt and keep going with the remaining patches.
New-Item -ItemType Directory -Force $OutRoot | Out-Null
$PatchStatusFile = Join-Path $OutRoot "PATCH_STATUS.txt"
$PatchStatus = @()
if (Test-Path $PatchDir) {
    Get-ChildItem -Path $PatchDir -Filter "*.patch" | Sort-Object Name | ForEach-Object {
        $name = $_.Name
        & git -C $Src apply --check $_.FullName 2>$null
        if ($LASTEXITCODE -eq 0) {
            & git -C $Src apply $_.FullName
            if ($LASTEXITCODE -eq 0) {
                Write-Host "Applied patch $name"
                $PatchStatus += "$name`: APPLIED"
            } else {
                Write-Warning "PATCH APPLY FAILED: $name. Continuing without it."
                $PatchStatus += "$name`: FAILED"
            }
        } else {
            Write-Warning "PATCH NOT APPLICABLE: $name (upstream changed?). Continuing without it."
            $PatchStatus += "$name`: FAILED"
        }
    }
}
Set-Content -Path $PatchStatusFile -Value $PatchStatus
$failedPatches = @($PatchStatus | Where-Object { $_ -like "*: FAILED" })
if ($failedPatches.Count -gt 0) {
    Write-Warning "Patches needing rebase: $($failedPatches -join ', ')"
    Write-Warning "The build continues without them. Status: $PatchStatusFile"
}

# Optional crates.io source replacement for slow networks.
if ($env:EASYTIER_CRATES_MIRROR) {
    $cfgPath = Join-Path $Src ".cargo/config.toml"
    New-Item -ItemType Directory -Force (Split-Path $cfgPath -Parent) | Out-Null
    $cfgText = if (Test-Path $cfgPath) { Get-Content -Raw $cfgPath } else { "" }
    if ($cfgText -notmatch "easytier-crates-mirror") {
        $block = @(
            "",
            "[source.crates-io]",
            'replace-with = "easytier-crates-mirror"',
            "",
            "[source.easytier-crates-mirror]",
            "registry = `"$($env:EASYTIER_CRATES_MIRROR)`"",
            ""
        ) -join "`n"
        Add-Content -Path $cfgPath -Value $block
        Write-Host "Crates mirror: $($env:EASYTIER_CRATES_MIRROR)"
    }
}

# 3. Toolchain checks ------------------------------------------------------
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) { throw "cargo not found in PATH" }
if (-not (Get-Command rustup -ErrorAction SilentlyContinue)) { throw "rustup not found in PATH" }

& cargo ndk --version 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "cargo-ndk not installed; installing"
    & cargo install cargo-ndk --locked
    if ($LASTEXITCODE -ne 0) { throw "cargo install cargo-ndk failed" }
}

$Ndk = $env:ANDROID_NDK_HOME
if (-not $Ndk) { $Ndk = $env:ANDROID_NDK_ROOT }
if (-not $Ndk) { $Ndk = $env:NDK_HOME }
if (-not $Ndk) {
    throw "Android NDK not found. Set ANDROID_NDK_HOME (NDK r27+ recommended)."
}
$env:ANDROID_NDK_HOME = $Ndk

$Readelf = Get-ChildItem (Join-Path $Ndk "toolchains/llvm/prebuilt") -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName "bin/llvm-readelf.exe" } |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Readelf) {
    $Readelf = Get-ChildItem (Join-Path $Ndk "toolchains/llvm/prebuilt") -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "bin/llvm-readelf" } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}

$PinnedFlags = "-C link-arg=-Wl,-z,max-page-size=16384"
$env:CARGO_TARGET_DIR = $TargetDir
New-Item -ItemType Directory -Force $TargetDir | Out-Null
New-Item -ItemType Directory -Force $OutRoot | Out-Null

# Host C toolchain for build scripts. Rust's windows-gnu toolchain ships a
# linker-only gcc and no GNU as, so aws-lc-sys and bindgen need a real MinGW
# (gcc/as/dlltool + libclang). Set EASYTIER_MINGW_BIN to override.
try {
    Push-Location $Src
    $hostTriple = ((& rustc -vV) | Select-String "^host:").ToString().Split(":")[1].Trim()
    Pop-Location
    if ($hostTriple -match "windows-gnu") {
        $mingwBin = $env:EASYTIER_MINGW_BIN
        if ($mingwBin -and (Test-Path (Join-Path $mingwBin "gcc.exe"))) {
            $mingwRoot = Split-Path $mingwBin -Parent
            $env:PATH = "$mingwBin;$env:PATH"
            if (Test-Path (Join-Path $mingwBin "libclang.dll")) {
                $env:LIBCLANG_PATH = $mingwBin
            }
            $mingwInclude = Join-Path $mingwRoot "include"
            if (Test-Path $mingwInclude) {
                $hostKey = $hostTriple.ToUpper().Replace('-', '_')
                $inc = "-I$($mingwInclude.Replace('\', '/'))"
                Set-Item "Env:CFLAGS_$hostKey" $inc
                Set-Item "Env:CXXFLAGS_$hostKey" $inc
            }
            Write-Host "Host C toolchain: $mingwBin"
        } else {
            Write-Warning "No MinGW gcc found. Set EASYTIER_MINGW_BIN to a MinGW bin directory."
            $ndkBin = Get-ChildItem (Join-Path $Ndk "toolchains/llvm/prebuilt") -Directory |
                ForEach-Object { Join-Path $_.FullName "bin" } | Where-Object { Test-Path $_ } |
                Select-Object -First 1
            $llvmDlltool = Join-Path $ndkBin "llvm-dlltool.exe"
            if (Test-Path $llvmDlltool) {
                $shimDir = Join-Path $TargetDir "host-bin"
                New-Item -ItemType Directory -Force $shimDir | Out-Null
                Copy-Item $llvmDlltool (Join-Path $shimDir "dlltool.exe") -Force
                $env:PATH = "$shimDir;$env:PATH"
            }
        }
    }
} catch {
    Write-Warning "Could not prepare host C toolchain: $_"
}

$Crates = @(
    "easytier-contrib/easytier-ffi",
    "easytier-contrib/easytier-android-jni"
)

$Results = @()
# 4. Build each ABI --------------------------------------------------------
foreach ($abi in $AbiList) {
    $triple = $TripleMap[$abi]
    # Target-specific rustflags avoid passing Android link flags to host build scripts.
    $flagsKey = "CARGO_TARGET_$($triple.ToUpper().Replace('-', '_'))_RUSTFLAGS"
    Set-Item "Env:$flagsKey" $PinnedFlags
    Write-Host "== Installing rust target $triple"
    Push-Location $Src
    try {
        & rustup target add $triple
        if ($LASTEXITCODE -ne 0) { throw "rustup target add $triple failed" }
    } finally {
        Pop-Location
    }

    foreach ($crate in $Crates) {
        $crateDir = Join-Path $Src $crate
        Write-Host "== cargo ndk -t $abi --platform 26 build --release ($crate)"
        Push-Location $crateDir
        try {
            & cargo ndk -t $abi --platform 26 build --release
            if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed for $crate ($abi)" }
        } finally {
            Pop-Location
        }
    }

    $releaseDir = Join-Path $TargetDir "$triple/release"
    $jniSo = Join-Path $releaseDir "libeasytier_android_jni.so"
    if (-not (Test-Path $jniSo)) { throw "Missing build output: $jniSo" }

    $outDir = Join-Path $OutRoot $abi
    New-Item -ItemType Directory -Force $outDir | Out-Null
    Copy-Item $jniSo $outDir -Force
    # easytier-ffi is linked statically into the JNI library. The separate
    # libeasytier_ffi.so is never loaded by the app, so keep it out of the
    # shipped output and remove any stale copy from an earlier build.
    Remove-Item (Join-Path $outDir "libeasytier_ffi.so") -Force -ErrorAction SilentlyContinue

    if (-not $NoJniLibs) {
        $libsDir = Join-Path $JniLibsRoot $abi
        New-Item -ItemType Directory -Force $libsDir | Out-Null
        Copy-Item $jniSo $libsDir -Force
        Remove-Item (Join-Path $libsDir "libeasytier_ffi.so") -Force -ErrorAction SilentlyContinue
    }

    $so = Get-Item $jniSo
    $needed = @()
    $loadAlignOk = $null
    if ($Readelf) {
        $needed = (& $Readelf -d $jniSo | Select-String "NEEDED") | ForEach-Object { $_.Line.Trim() }
        $loads = (& $Readelf -lW $jniSo | Select-String "^\s*LOAD")
        $loadAlignOk = ($loads.Count -gt 0) -and (($loads | Where-Object { $_.Line -notmatch "0x4000" }).Count -eq 0)
    }
    $Results += [pscustomobject]@{
        Abi = $abi
        Path = $so.FullName
        SizeBytes = $so.Length
        Needed = ($needed -join "; ")
        LoadAlign16K = $loadAlignOk
    }
}

Write-Host ""
Write-Host "Build complete:"
$Results | Format-Table -AutoSize | Out-String | Write-Host
foreach ($r in $Results) {
    Write-Host ("{0}: {1:N0} bytes, 16KB-aligned LOAD={2}" -f $r.Abi, $r.SizeBytes, $r.LoadAlign16K)
    if ($r.Needed) { Write-Host "  NEEDED: $($r.Needed)" }
}
