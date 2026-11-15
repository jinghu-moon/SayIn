param(
    [string]$SdkRoot = '',
    [string]$HostCompiler = 'C:/A_Softwares/mingw64_v15.1.0/bin/g++.exe'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$project = Join-Path $repo 'root-helper'
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (-not $SdkRoot) {
    $local = Join-Path $repo 'android/local.properties'
    if (Test-Path -LiteralPath $local) {
        $line = Get-Content -LiteralPath $local | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
        if ($line) { $SdkRoot = $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
}
if (-not $SdkRoot) { throw 'Set -SdkRoot, ANDROID_HOME or android/local.properties.' }
$ndk = Join-Path $SdkRoot 'ndk/29.0.14206865'
$revision = Join-Path $ndk 'source.properties'
if (!(Test-Path -LiteralPath $revision) -or !(Select-String -LiteralPath $revision -Pattern '^Pkg.Revision = 29\.0\.14206865$' -Quiet)) {
    throw 'SayIn Helper requires Android NDK 29.0.14206865.'
}
$ninja = (Get-Command ninja -ErrorAction Stop).Source
function Run-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed ($LASTEXITCODE)" }
}
Run-Checked cmake @('-S', $project, '-B', "$project/build/host", '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_CXX_COMPILER=$HostCompiler", '-DCMAKE_BUILD_TYPE=Debug')
Run-Checked cmake @('--build', "$project/build/host", '--parallel', '2')
Run-Checked ctest @('--test-dir', "$project/build/host", '--output-on-failure')
Run-Checked cmake @('-S', $project, '-B', "$project/build/android", '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-33', '-DANDROID_STL=c++_static',
    '-DBUILD_TESTING=OFF', '-DCMAKE_BUILD_TYPE=RelWithDebInfo')
Run-Checked cmake @('--build', "$project/build/android", '--parallel', '2')
$packageDirectory = Join-Path $project 'build/package'
New-Item -ItemType Directory -Force -Path $packageDirectory | Out-Null
Run-Checked (Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe') @(
    '--strip-unneeded', '-o', "$packageDirectory/sayin-helper", "$project/build/android/sayin-helper")
Run-Checked python @('-m', 'unittest', 'discover', '-s', "$project/tests", '-p', 'test_*.py', '-v')
Run-Checked python @((Join-Path $PSScriptRoot 'package_root_helper.py'))
