param([string]$DeviceSerial, [switch]$UseExistingRootInstall, [switch]$SkipBuild,
    [switch]$SkipInstall, [switch]$Lan, [switch]$TransportOnly, [switch]$FullSuite)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
function Invoke-Checked([scriptblock]$Action) {
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code $LASTEXITCODE" }
}
if (!$DeviceSerial) {
    $devices = @(& $adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
    if ($devices.Count -ne 1) { throw 'Specify one authorized device.' }
    $DeviceSerial = $devices[0]
}
if (!$SkipBuild) {
    Invoke-Checked { & (Join-Path $PSScriptRoot 'gradle.ps1') '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest }
    Push-Location (Join-Path $projectRoot 'desktop')
    try { Invoke-Checked { cargo build -p voicelink-transport --example android_fixture --locked } } finally { Pop-Location }
}
$metadata = Get-Content (Join-Path $projectRoot 'android/app/build/outputs/apk/imageTest/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'seeyuer.sayin.imagetest') { throw 'Refusing to replace the daily application.' }
$targetDirectory = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $projectRoot 'desktop/target' }
$testTag = [guid]::NewGuid().ToString()
$fixtureDirectory = Join-Path $targetDirectory "image-android-fixture-$testTag"
$log = Join-Path $targetDirectory "image-android-$testTag.log"
$remoteApks = @()
$process = $null
$port = $null
if (($Lan -and ($TransportOnly -or $FullSuite)) -or ($TransportOnly -and $FullSuite)) { throw 'FullSuite/TransportOnly are mutually exclusive loopback regressions; use the image suite for LAN.' }
$lease = [System.Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
$leaseAcquired = $false
$testClass = if ($TransportOnly) { 'seeyuer.sayin.data.TransportInstrumentedTest' } else { 'seeyuer.sayin.data.ImageInputInstrumentedTest' }
$expectedTests = if ($FullSuite) { 117 } elseif ($TransportOnly) { 1 } else { 4 }
$expectedCalls = if ($FullSuite -or $TransportOnly) { 2 } else { 1 }
try {
    $leaseAcquired = $lease.WaitOne(0)
    if (!$leaseAcquired) { throw 'Another SayIn device verification is active; run serially.' }
    $activityState = (& $adb -s $DeviceSerial shell dumpsys activity) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $activityState -match 'mRunningInstrumentation=InstrumentationRecord|Active instrumentation:') { throw 'Cannot obtain an idle instrumentation device.' }
    $policy = (& $adb -s $DeviceSerial shell dumpsys window policy) -join "`n"
    if ($LASTEXITCODE -ne 0 -or ($policy -match '(?m)^\s*showing=true\r?$' -and $policy -match '(?m)^\s*secure=true\r?$')) { throw 'Unlock the selected phone before foreground UI verification.' }
    if ($UseExistingRootInstall) {
        $rootIdentity = & $adb -s $DeviceSerial shell su -c id
        if ($LASTEXITCODE -ne 0 -or $rootIdentity -notmatch 'uid=0\(root\)') { throw 'Existing Root authorization is required; no settings are changed.' }
    }
    $apks = if ($SkipInstall) { @() } else { @('android/app/build/outputs/apk/imageTest/app-imageTest.apk','android/app/build/outputs/apk/androidTest/imageTest/app-imageTest-androidTest.apk') }
    foreach ($relative in $apks) {
        $apk = Join-Path $projectRoot $relative
        if ($UseExistingRootInstall) {
            $remote = "/data/local/tmp/sayin-image-$testTag-$($remoteApks.Count).apk"
            $remoteApks += $remote
            Invoke-Checked { & $adb -s $DeviceSerial push $apk $remote }
            $result = & $adb -s $DeviceSerial shell "su -c 'pm install -r -t $remote'"
            if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') { throw 'Isolated test APK installation failed.' }
        } else { Invoke-Checked { & $adb -s $DeviceSerial install -r -t $apk } }
    }
    $fixtureArguments = @("`"$fixtureDirectory`"", '--images', '--port=0')
    if ($Lan) { $fixtureArguments += '--lan' }
    $process = Start-Process -FilePath (Join-Path $targetDirectory 'debug/examples/android_fixture.exe') -ArgumentList $fixtureArguments -PassThru -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    $fixturePath = Join-Path $fixtureDirectory 'fixture.json'
    while (!(Test-Path -LiteralPath $fixturePath)) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Image fixture did not start.' }
        Start-Sleep -Milliseconds 50
    }
    $fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
    $port = [int]$fixture.port
    if ($port -le 1024 -or $port -gt 65535 -or $fixture.code -notmatch '^\d{8}$') { throw 'Invalid fixture metadata.' }
    if (!$Lan) { Invoke-Checked { & $adb -s $DeviceSerial reverse "tcp:$port" "tcp:$port" } }
    $arguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'timeout_msec', '60000', '-e', 'fixtureCode', $fixture.code, '-e', 'fixturePort', $port, '-e', 'fixtureAddress', $fixture.address)
    if ($FullSuite) { $arguments += @('-e', 'notAnnotation', 'seeyuer.sayin.ui.RootHelperDeviceTest') }
    # Pairing invitations and active-device gates are single-use state. The
    # image data suite gets its own fresh desktop identity, not a reset gate.
    if ($FullSuite) { $arguments += @('-e', 'notClass', 'seeyuer.sayin.asr.DoubaoLiveInstrumentedTest,seeyuer.sayin.data.DiscoveryInstrumentedTest,seeyuer.sayin.data.ImageInputInstrumentedTest,seeyuer.sayin.data.ImagePerformanceInstrumentedTest,seeyuer.sayin.data.ImagePauseInstrumentedTest,seeyuer.sayin.data.PlatformNetworkInstrumentedTest,seeyuer.sayin.ui.TranslationNativeInstrumentedTest,seeyuer.sayin.ui.ForegroundLiveInstrumentedTest') }
    else { $arguments += @('-e', 'class', $testClass) }
    $arguments += 'seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner'
    & $adb @arguments > $log 2>&1
    $instrumentExit = $LASTEXITCODE
    $output = Get-Content -LiteralPath $log -Raw
    if ($instrumentExit -ne 0 -or $output -notmatch "OK \($expectedTests tests?\)" -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "Instrumentation failed; see $log" }
    Start-Sleep -Milliseconds 200
    $calls = Get-Content -LiteralPath (Join-Path $fixtureDirectory 'calls.json') -Raw | ConvertFrom-Json
    if ($calls.fake_input_calls -ne $expectedCalls) { throw "Expected exactly $expectedCalls fake input calls; cancellation/recovery must not replay." }
    @{ scope = 'isolated imageTest UID/identity/data; fake input only'; suite = $testClass; real_lan = [bool]$Lan; tests = $expectedTests; fake_input_calls = $calls.fake_input_calls } | ConvertTo-Json | Set-Content (Join-Path $fixtureDirectory 'result.json') -Encoding utf8
    Write-Output "PASS: $expectedTests instrumentation tests; $expectedCalls fake input calls, no replay. Evidence: $fixtureDirectory"
    if ($FullSuite) {
        & $PSCommandPath -DeviceSerial $DeviceSerial -SkipBuild -SkipInstall
        if (!$?) { throw 'Image data suite failed in its separate identity.' }
        Write-Output 'PASS: all 121 regular device tests (117 text/UI + 4 image) across independent text/image desktop identities; live paid cloud and discovery fixtures remain separate.'
    }
} finally {
    try {
        if ($leaseAcquired) {
            if ($process -and !$process.HasExited) {
                Set-Content -LiteralPath (Join-Path $fixtureDirectory 'stop') -Value '' -NoNewline
                if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id }
            }
            if ($port -and !$Lan) { & $adb -s $DeviceSerial reverse --remove "tcp:$port" 2>$null | Out-Null }
            foreach ($remote in $remoteApks) { & $adb -s $DeviceSerial shell rm -f $remote | Out-Null }
            if (Test-Path -LiteralPath (Join-Path $fixtureDirectory 'fixture.json')) { Remove-Item -LiteralPath (Join-Path $fixtureDirectory 'fixture.json') }
        }
    } finally {
        if ($leaseAcquired) { $lease.ReleaseMutex() }
        $lease.Dispose()
    }
}
