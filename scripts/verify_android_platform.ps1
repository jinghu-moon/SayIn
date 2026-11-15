param([Parameter(Mandatory)][string]$DeviceSerial, [switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$targetDirectory = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $projectRoot 'desktop/target' }
if ($DeviceSerial -notmatch '^emulator-\d+$') { throw 'Platform probes only operate on explicitly selected isolated emulators.' }
function Invoke-Checked([scriptblock]$Action) {
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code $LASTEXITCODE" }
}
$api = [int](& $adb -s $DeviceSerial shell getprop ro.build.version.sdk)
if ($api -notin @(29,37)) { throw 'Use the minimum supported API29 or permission-boundary API37 emulator.' }
$metadata = Get-Content (Join-Path $projectRoot 'android/app/build/outputs/apk/imageTest/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'seeyuer.sayin.imagetest') { throw 'Refusing the daily application.' }
$directory = Join-Path $targetDirectory "android-platform-api$api-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $directory | Out-Null
$process = $null
$port = $null
function Invoke-Probe([string]$Phase) {
    $arguments = @('-s',$DeviceSerial,'shell','am','instrument','-w','-r','-e','platformProbe','true','-e','class','seeyuer.sayin.data.PlatformNetworkInstrumentedTest')
    if ($Phase -eq 'revoked') { $arguments += @('-e','permissionPhase','revoked') }
    if ($fixture) { $arguments += @('-e','fixtureAddress',$fixture.address,'-e','fixturePort',$fixture.port,'-e','fixtureCode',$fixture.code) }
    $arguments += 'seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner'
    $log = Join-Path $directory "$Phase.log"
    & $adb @arguments > $log 2>&1
    $code = $LASTEXITCODE
    $output = Get-Content -LiteralPath $log -Raw
    if ($code -ne 0 -or $output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "Platform probe failed; see $log" }
}
try {
    $bootDeadline = [DateTime]::UtcNow.AddMinutes(3)
    while ((& $adb -s $DeviceSerial shell getprop sys.boot_completed) -ne '1') {
        if ([DateTime]::UtcNow -gt $bootDeadline) { throw 'Selected emulator did not finish booting.' }
        Start-Sleep -Milliseconds 500
    }
    if (!$SkipInstall) {
        foreach ($relative in @('android/app/build/outputs/apk/imageTest/app-imageTest.apk','android/app/build/outputs/apk/androidTest/imageTest/app-imageTest-androidTest.apk')) {
            Invoke-Checked { & $adb -s $DeviceSerial install -r -t (Join-Path $projectRoot $relative) }
        }
    }
    # Clear only this disposable emulator's isolated package, never the daily UID.
    Invoke-Checked { & $adb -s $DeviceSerial shell pm clear seeyuer.sayin.imagetest }
    $fixture = $null
    if ($api -eq 37) {
        $fixtureDirectory = Join-Path $directory 'fixture'
        $process = Start-Process -FilePath (Join-Path $targetDirectory 'debug/examples/android_fixture.exe') -ArgumentList @("`"$fixtureDirectory`"",'--images','--port=0') -PassThru -WindowStyle Hidden
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        $fixturePath = Join-Path $fixtureDirectory 'fixture.json'
        while (!(Test-Path -LiteralPath $fixturePath)) {
            if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Platform fixture did not start.' }
            Start-Sleep -Milliseconds 50
        }
        $fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
        $port = [int]$fixture.port
        if ($port -le 1024 -or $port -gt 65535 -or $fixture.code -notmatch '^\d{8}$') { throw 'Invalid fixture metadata.' }
        Invoke-Checked { & $adb -s $DeviceSerial reverse "tcp:$port" "tcp:$port" }
    }
    Invoke-Probe 'initial'
    if ($api -eq 37) {
        foreach ($choice in @('deny','allow')) {
            Invoke-Checked { & $adb -s $DeviceSerial pull "/sdcard/Android/data/seeyuer.sayin.imagetest/files/network-permission-$choice.png" (Join-Path $directory "$choice.png") }
        }
        Invoke-Checked { & $adb -s $DeviceSerial shell pm revoke seeyuer.sayin.imagetest android.permission.ACCESS_LOCAL_NETWORK }
        Invoke-Probe 'revoked'
        $calls = Get-Content (Join-Path $fixtureDirectory 'calls.json') -Raw | ConvertFrom-Json
        if ($calls.fake_input_calls -ne 0) { throw 'Permission tests must never input or replay.' }
    }
    @{api=$api;isolated_package='seeyuer.sayin.imagetest';platform_tests=if($api -eq 37){2}else{1};fake_input_calls=0;real_lan=$false;scope=if($api -eq 37){'System permission deny/allow/revoke, bounded NSD/cancel, pinned pairing via ADB reverse, full identity preserved after process restart'}else{'Minimum supported OS native TLS1.3 capability'}} | ConvertTo-Json | Set-Content (Join-Path $directory 'result.json') -Encoding utf8
    Write-Output "PASS: platform API$api; evidence $directory"
} finally {
    if ($process -and !$process.HasExited) {
        Set-Content -LiteralPath (Join-Path $fixtureDirectory 'stop') -Value '' -NoNewline
        if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id }
    }
    if ($port) { & $adb -s $DeviceSerial reverse --remove "tcp:$port" 2>$null | Out-Null }
    if ($fixtureDirectory -and (Test-Path -LiteralPath (Join-Path $fixtureDirectory 'fixture.json'))) { Remove-Item -LiteralPath (Join-Path $fixtureDirectory 'fixture.json') }
}
