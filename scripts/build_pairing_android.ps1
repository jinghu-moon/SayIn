param([string]$NdkVersion = '29.0.14206865')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$ndkBin = Join-Path $sdkRoot "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin"
$linker = Join-Path $ndkBin 'aarch64-linux-android26-clang.cmd'
if (!(Test-Path -LiteralPath $linker)) { throw "Android NDK $NdkVersion is required." }
$priorLinker = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$priorCc = $env:CC_aarch64_linux_android
$priorAr = $env:AR_aarch64_linux_android
Push-Location (Join-Path $projectRoot 'desktop')
try {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $linker
    $env:CC_aarch64_linux_android = $linker
    $env:AR_aarch64_linux_android = Join-Path $ndkBin 'llvm-ar.exe'
    & cargo build -p voicelink-pairing --release --locked --target aarch64-linux-android
    if ($LASTEXITCODE -ne 0) { throw 'Android pairing native build failed.' }
    $jniDirectory = Join-Path $projectRoot 'android\app\build\generated\pairingJni\arm64-v8a'
    New-Item -ItemType Directory -Path $jniDirectory -Force | Out-Null
    Copy-Item -LiteralPath 'target\aarch64-linux-android\release\libvoicelink_pairing.so' -Destination $jniDirectory -Force
} finally {
    Pop-Location
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $priorLinker
    $env:CC_aarch64_linux_android = $priorCc
    $env:AR_aarch64_linux_android = $priorAr
}
