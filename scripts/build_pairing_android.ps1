param([string]$NdkVersion = '29.0.14206865', [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'arm64-v8a')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$ndkBin = Join-Path $sdkRoot "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin"
$triple = if ($Abi -eq 'x86_64') { 'x86_64-linux-android' } else { 'aarch64-linux-android' }
$linker = Join-Path $ndkBin "$($triple)29-clang.cmd"
if (!(Test-Path -LiteralPath $linker)) { throw "Android NDK $NdkVersion is required." }
$linkerVariable = 'CARGO_TARGET_'+$triple.Replace('-','_').ToUpperInvariant()+'_LINKER'
$ccVariable = 'CC_'+$triple.Replace('-','_')
$arVariable = 'AR_'+$triple.Replace('-','_')
$prior = @{}
foreach ($name in @($linkerVariable,$ccVariable,$arVariable)) { $prior[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location (Join-Path $projectRoot 'desktop')
try {
    [Environment]::SetEnvironmentVariable($linkerVariable,$linker,'Process')
    [Environment]::SetEnvironmentVariable($ccVariable,$linker,'Process')
    [Environment]::SetEnvironmentVariable($arVariable,(Join-Path $ndkBin 'llvm-ar.exe'),'Process')
    & cargo build -p voicelink-pairing --release --locked --target $triple
    if ($LASTEXITCODE -ne 0) { throw 'Android pairing native build failed.' }
    $jniDirectory = Join-Path $projectRoot "android/app/build/generated/pairingJni/$Abi"
    New-Item -ItemType Directory -Path $jniDirectory -Force | Out-Null
    $targetDirectory = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { 'target' }
    Copy-Item -LiteralPath (Join-Path $targetDirectory "$triple/release/libvoicelink_pairing.so") -Destination $jniDirectory -Force
} finally {
    Pop-Location
    foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name,$prior[$name],'Process') }
}
