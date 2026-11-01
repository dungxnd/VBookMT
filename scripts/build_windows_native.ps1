#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Builds `vbookmt_native.dll` for the Windows host.

.DESCRIPTION
    The Windows twin of scripts/build_android_native.sh: same dependency pins, same sources, same
    dialect of C++, so the two hosts cannot disagree about the tokenizer contract, the repetition
    guard or the position limit.

    Why this script exists at all: native/windows/CMakeLists.txt referenced it in its own
    FATAL_ERROR message, but the file did not exist and no workflow built Windows natives. The
    release job called `createDistributable`, which happily produced an MSI *without* the DLLs, so
    the app installed cleanly and then failed at the first translation with
    "Thiếu vbookmt_native.dll".

    Dependency pins are deliberately identical to build_android_native.sh:
      CTranslate2    v4.8.4   (recursive: spdlog, cpu_features, ruy)
      SentencePiece  v0.2.3pre1
      protobuf       v36.2    (must equal the runtime SentencePiece links; protoc stamps its
                               version into generated code and a mismatch aborts at load time)

    SentencePiece 0.2.3pre1 no longer vendors protobuf or abseil, so both arrive via FetchContent and
    land under `_deps/` — which is exactly where native/windows/CMakeLists.txt globs for them. Its
    public header also includes real "absl/..." paths, so no shim installation is needed.

.PARAMETER WorkDir
    Where the dependency checkouts and build trees live. Defaults to build/native-win.

.PARAMETER Clean
    Delete the work directory first. Full rebuilds take ~10-20 minutes from cold.

.PARAMETER SkipDeps
    Reuse existing dependency builds and only relink vbookmt_native.dll. Use this while iterating on
    native/*.cpp — it turns a 15 minute build into about 30 seconds.

.EXAMPLE
    ./scripts/build_windows_native.ps1

.EXAMPLE
    ./scripts/build_windows_native.ps1 -SkipDeps   # after editing native/tokenizer.cpp
#>
[CmdletBinding()]
param(
    [string]$WorkDir = "",
    [switch]$Clean,
    [switch]$SkipDeps
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Keep these in lockstep with build_android_native.sh. A mismatch between the two hosts would let
# them disagree about the tokenizer contract, which is the one thing sharing native/ is meant to
# prevent.
#
# protobuf is deliberately absent here: SentencePiece v0.2.3pre1 pins it to v36.2 in its own
# FetchContent_Declare, which is the same release the Android script names, so there is nothing to
# keep in sync by hand. Overriding it here could only introduce drift.

$SPM_TAG = "v0.2.3pre1"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $WorkDir) { $WorkDir = Join-Path $ProjectRoot "build/native-win" }
$WorkDir = [System.IO.Path]::GetFullPath($WorkDir)

$OutputDir = Join-Path $ProjectRoot "apps/windows/resources-packaged/windows"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "=== $Message" -ForegroundColor Cyan
}

function Invoke-Checked([string]$Exe, [string[]]$Arguments, [string]$What) {
    Write-Host "  $Exe $($Arguments -join ' ')" -ForegroundColor DarkGray
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE" }
}

# ---------------------------------------------------------------------------------------------
# Toolchain.
#
# `cmake -G "Visual Studio 17 2022"` needs an installed VS instance it can locate, and on a bare
# runner (or a CI shell that never ran vcvars) it fails with "could not find any instance of Visual
# Studio" before compiling anything. Locate the install with vswhere and import its developer
# environment, so this script works from a plain PowerShell exactly the way it works from a Developer
# prompt.
# ---------------------------------------------------------------------------------------------
Write-Step "Checking toolchain"

foreach ($tool in @("git", "cmake")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "'$tool' is not on PATH. Install it, or run this from a Developer PowerShell."
    }
}

# Only needed when the compiler is not already on PATH — a Developer prompt has already done this.
$VsInstallPath = $null
if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path $vswhere) {
        $vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($vsPath) {
            $VsInstallPath = $vsPath
            $vcvars = Join-Path $vsPath "VC\Auxiliary\Build\vcvars64.bat"
            if (Test-Path $vcvars) {
                Write-Host "  Importing MSVC environment from $vsPath" -ForegroundColor DarkGray
                # vcvars64.bat only exports into its own cmd session, so run it and import the
                # resulting environment into this PowerShell process.
                $envDump = cmd /c "`"$vcvars`" >nul 2>&1 && set"
                foreach ($line in $envDump) {
                    $idx = $line.IndexOf("=")
                    if ($idx -gt 0) {
                        $name = $line.Substring(0, $idx)
                        $value = $line.Substring($idx + 1)
                        if ($name -and $name -notmatch '^=') {
                            Set-Item -Path "env:$name" -Value $value -ErrorAction SilentlyContinue
                        }
                    }
                }
            }
        }
    }
    if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
        throw "cl.exe is not available. Install the 'Desktop development with C++' workload for Visual Studio, or run this from a Developer PowerShell."
    }
}

# ---------------------------------------------------------------------------------------------
# CMake generator.
#
# The generator name encodes the Visual Studio *major version*, so a hardcoded "Visual Studio 17
# 2022" fails outright on a machine with VS 18 ("could not find any instance of Visual Studio") even
# though vcvars imported cleanly. Ask CMake which VS generators it knows and take the newest, then
# point the generator at the install vswhere found — CMAKE_GENERATOR_INSTANCE exists precisely so a
# build does not have to rely on discovery.
#
# Falling back to Ninja keeps a machine with no usable VS generator buildable: Ninja only needs
# cl.exe, which the block above has already put on PATH.
# ---------------------------------------------------------------------------------------------
$CmakeGenerator = $null
$CmakeGeneratorArgs = @()
$vsGenerators = @()
try {
    $vsGenerators = & cmake --help 2>$null |
        Select-String -Pattern '^\s*\*?\s*(Visual Studio (\d+) (\d{4}))' |
        ForEach-Object { $_.Matches[0] }
    if ($vsGenerators) {
        $best = $vsGenerators | Sort-Object { [int]$_.Groups[2].Value } -Descending | Select-Object -First 1
        $CmakeGenerator = $best.Groups[1].Value
    }
} catch { }

if ($CmakeGenerator) {
    $CmakeGeneratorArgs = @("-G", $CmakeGenerator, "-A", "x64")
    if ($VsInstallPath) {
        $CmakeGeneratorArgs += "-DCMAKE_GENERATOR_INSTANCE=$VsInstallPath"
    }
} elseif (Get-Command ninja -ErrorAction SilentlyContinue) {
    $CmakeGenerator = "Ninja"
    $CmakeGeneratorArgs = @("-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release")
} else {
    throw "No usable CMake generator: no Visual Studio generator recognised by CMake, and ninja is not on PATH."
}
Write-Host "  CMake generator: $CmakeGenerator" -ForegroundColor DarkGray

# JNI headers come from the JDK, and JAVA_HOME is what Gradle and CI both set.
if (-not $env:JAVA_HOME) {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java.Source)
    }
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "include/jni.h"))) {
    throw "JAVA_HOME is not set to a JDK containing include/jni.h (needed for JNI)."
}
$JniRoot = [System.IO.Path]::GetFullPath($env:JAVA_HOME).Replace('\', '/')

if ($Clean -and (Test-Path $WorkDir)) {
    Write-Host "  Removing $WorkDir" -ForegroundColor DarkGray
    Remove-Item -Recurse -Force $WorkDir
}
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
Push-Location $WorkDir
try {

if (-not $SkipDeps) {

    # -----------------------------------------------------------------------------------------
    # SentencePiece.
    #
    # Built STATIC (Windows forces `SPM_ENABLE_SHARED OFF` in upstream CMakeLists), because the
    # engine links it into the DLL. `SPM_ENABLE_MSVC_MT_BUILD` stays OFF: turning it on rewrites
    # /MD to /MT, which would give a different CRT than the DLL's MultiThreadedDLL setting and
    # produce two implementations of the C++ runtime in one process.
    #
    # 0.2.3pre1 no longer vendors protobuf or abseil, so `SPM_PROTOBUF_PROVIDER=module` and
    # `SPM_ABSL_PROVIDER=module` fetch them via FetchContent. That is deliberate rather than
    # convenient: the tag itself pins protobuf to v36.2 — the same release build_android_native.sh
    # uses — so the two hosts cannot drift apart by editing one script and not the other.
    #
    # No pre-cloned protobuf is passed via FETCHCONTENT_SOURCE_DIR_PROTOBUF, unlike the Android
    # build: that override exists there only because a cross build needs a *host* protoc. Here the
    # build machine and the target are the same, so protoc is just built alongside.
    # -----------------------------------------------------------------------------------------
    Write-Step "Building SentencePiece $SPM_TAG"

    if (-not (Test-Path "sentencepiece")) {
        Invoke-Checked git @("clone", "--depth", "1", "--branch", $SPM_TAG,
            "https://github.com/google/sentencepiece.git", "sentencepiece") "clone sentencepiece"
    }

    if (-not (Test-Path "build-spm/CMakeCache.txt")) {
        $spmArgs = @("-S", "sentencepiece", "-B", "build-spm") + $CmakeGeneratorArgs + @(
            "-DCMAKE_BUILD_TYPE=Release",
            "-DSPM_ENABLE_SHARED=OFF",
            "-DSPM_ENABLE_TCMALLOC=OFF",
            "-DSPM_ENABLE_LITE=OFF",      # Lite wants .spm.fb models; ours are classic .spm
            "-DSPM_PROTOBUF_PROVIDER=module",
            "-DSPM_ABSL_PROVIDER=module",
            "-DSPM_BUILD_TEST=OFF",
            "-DSPM_BUILD_EXAMPLES=OFF",
            "-DSPM_BUILD_COMMANDS=OFF",
            "-DSPM_ENABLE_MSVC_MT_BUILD=OFF"
        )
        Invoke-Checked cmake $spmArgs "configure sentencepiece"
    }
    # `sentencepiece-static`, not `sentencepiece`: on Windows the shared target does not exist and
    # the alias of that name is not buildable through the Visual Studio generator. Its OUTPUT_NAME
    # is still "sentencepiece", so the resulting archive is sentencepiece.lib — which is what
    # native/windows/CMakeLists.txt looks for.
    Invoke-Checked cmake @("--build", "build-spm", "--config", "Release", "--target", "sentencepiece-static",
        "--", "/m") "build sentencepiece"

    # -----------------------------------------------------------------------------------------
    # CTranslate2 used to be built here (v4.8.4) and linked into the engine DLL. It is gone:
    # translation runs on ONNX Runtime on both hosts, and this DLL now serves the SentencePiece
    # tokenizer alone — the only tokenizer, and the one every ONNX graph was exported against.
    # -----------------------------------------------------------------------------------------

} else {
    Write-Host "  -SkipDeps: reusing $WorkDir/build-spm" -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------------------------
# The engine DLL.
# ---------------------------------------------------------------------------------------------
Write-Step "Building vbookmt_native.dll"

$spmRoot = (Join-Path $WorkDir "sentencepiece").Replace('\', '/')
$spmBuildDir = (Join-Path $WorkDir "build-spm").Replace('\', '/')

$engineArgs = @("-S", (Join-Path $ProjectRoot "native/windows"), "-B", "build-engine") +
    $CmakeGeneratorArgs + @(
        "-DCMAKE_BUILD_TYPE=Release",
        "-DSPM_ROOT=$spmRoot",
        "-DSPM_BUILD_DIR=$spmBuildDir",
        "-DJNI_ROOT=$JniRoot"
    )
Invoke-Checked cmake $engineArgs "configure vbookmt_native" 
Invoke-Checked cmake @("--build", "build-engine", "--config", "Release", "--", "/m") "build vbookmt_native"

# ---------------------------------------------------------------------------------------------
# Install into the tree the Compose distributable packages.
#
# nativeDir() resolves the directory containing the running jar, which in a packaged build is the
# app root. Files under `windows/` in appResourcesRootDir are copied there by the Compose plugin,
# so dropping the DLLs here is all that is needed for them to land beside the executable.
# ---------------------------------------------------------------------------------------------
Write-Step "Installing DLLs"

$engineDll = Get-ChildItem -Path "build-engine" -Filter "vbookmt_native.dll" -Recurse |
    Select-Object -First 1
if (-not $engineDll) { throw "vbookmt_native.dll was not produced by the build." }

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
Copy-Item $engineDll.FullName $OutputDir -Force

# ---------------------------------------------------------------------------------------------
# Prove the exports are what the Kotlin facade looks for.
#
# JNI binds an `external fun` by its fully-qualified class name, and this host's facade is
# com.vbookmt.windows.backend.WindowsCt2Engine. A DLL that still exports the Android names links and
# copies fine, then fails at run time with UnsatisfiedLinkError — which is exactly the bug that
# shipped once already. Checking here turns that into a build failure.
# ---------------------------------------------------------------------------------------------
Write-Step "Verifying exports"

$expected = @(
    "nativeLoad", "nativeTokenCount", "nativeEncode", "nativeDecode"
)
$prefix = "Java_com_vbookmt_windows_backend_WindowsNativeTokenizer_"

$dumpbin = Get-Command dumpbin -ErrorAction SilentlyContinue
if ($dumpbin) {
    $exports = & dumpbin /nologo /exports (Join-Path $OutputDir "vbookmt_native.dll") | Out-String
    $missing = @()
    foreach ($name in $expected) {
        if ($exports -notmatch [regex]::Escape("$prefix$name")) { $missing += "$prefix$name" }
    }
    if ($missing.Count -gt 0) {
        throw ("vbookmt_native.dll is missing $($missing.Count) expected export(s):`n  " +
            ($missing -join "`n  "))
    }
    Write-Host "  All $($expected.Count) JNI entry points exported under $prefix*" -ForegroundColor Green
} else {
    # Not fatal: dumpbin is only on PATH inside a Developer prompt. The symbol check is still
    # covered by CI on windows-latest, which has it.
    Write-Host "  dumpbin not on PATH; skipping export verification." -ForegroundColor Yellow
}

Write-Step "Done"
Get-ChildItem $OutputDir -Filter *.dll |
    ForEach-Object { Write-Host ("  {0,-24} {1,8:N0} KB" -f $_.Name, ($_.Length / 1KB)) }
Write-Host ""
Write-Host "Next: gradle :apps:windows:createDistributable" -ForegroundColor Cyan

} finally {
    Pop-Location
}
