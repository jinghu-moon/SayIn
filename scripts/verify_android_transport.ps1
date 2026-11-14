param([string]$DeviceSerial, [switch]$UseExistingRootInstall, [switch]$SkipBuild, [switch]$TransportOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
function Invoke-Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed with exit code $LASTEXITCODE" }
}
if (!$DeviceSerial) {
    $devices = @(& $adbPath devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
    if ($devices.Count -ne 1) { throw 'Specify -DeviceSerial when exactly one authorized device is not connected.' }
    $DeviceSerial = $devices[0]
}
if (!$SkipBuild) {
    Invoke-Checked { & (Join-Path $PSScriptRoot 'gradle.ps1') :app:assembleDebug :app:assembleDebugAndroidTest }
    Push-Location (Join-Path $projectRoot 'desktop')
    try { Invoke-Checked { cargo build -p voicelink-transport --example android_fixture --locked } } finally { Pop-Location }
}
# The suite now includes foreground Compose tests. Fail clearly instead of
# waiting indefinitely when a secure lockscreen rejects the test Activity.
$deviceWindowPolicy = (& $adbPath -s $DeviceSerial shell dumpsys window policy) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Could not read device lock state.' }
if ($deviceWindowPolicy -match '(?m)^\s*showing=true\r?$' -and $deviceWindowPolicy -match '(?m)^\s*secure=true\r?$') {
    throw 'Unlock the connected phone and keep its screen on before running foreground UI tests.'
}
$desktopTarget = Join-Path $projectRoot 'desktop\target'
$fixtureDirectory = Join-Path $desktopTarget ("android-fixture-" + [guid]::NewGuid().ToString())
$fixtureExe = Join-Path $desktopTarget 'debug\examples\android_fixture.exe'
$logPath = Join-Path $projectRoot 'android\instrumented-manual-test.log'
$process = $null
try {
    if ($UseExistingRootInstall) {
        $rootIdentity = & $adbPath -s $DeviceSerial shell su -c id
        if ($LASTEXITCODE -ne 0 -or $rootIdentity -notmatch 'uid=0\(root\)') { throw 'Existing Root authorization is required; this script does not change security settings.' }
        foreach ($apk in @(
            @('android\app\build\outputs\apk\debug\app-debug.apk', '/data/local/tmp/voicelink-debug.apk'),
            @('android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk', '/data/local/tmp/voicelink-test.apk')
        )) {
            Invoke-Checked { & $adbPath -s $DeviceSerial push (Join-Path $projectRoot $apk[0]) $apk[1] }
            $result = & $adbPath -s $DeviceSerial shell "su -c 'pm install -r -t $($apk[1])'"
            if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') { throw 'APK install failed.' }
        }
    } else {
        Invoke-Checked { & $adbPath -s $DeviceSerial install -r (Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk') }
        Invoke-Checked { & $adbPath -s $DeviceSerial install -r -t (Join-Path $projectRoot 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') }
    }
    Invoke-Checked { & $adbPath -s $DeviceSerial reverse tcp:17888 tcp:17888 }
    $process = Start-Process -FilePath $fixtureExe -ArgumentList @("`"$fixtureDirectory`"") -PassThru -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    $fixturePath = Join-Path $fixtureDirectory 'fixture.json'
    while (!(Test-Path -LiteralPath $fixturePath)) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Loopback fixture did not start.' }
        Start-Sleep -Milliseconds 100
    }
    $fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
    if ($fixture.port -ne 17888 -or $fixture.code -notmatch '^\d{8}$') { throw 'Invalid fixture metadata.' }
    $instrumentationArguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'timeout_msec', '60000', '-e', 'fixtureCode', $fixture.code, '-e', 'fixturePort', $fixture.port)
    if ($TransportOnly) { $instrumentationArguments += @('-e', 'class', 'seeyuer.sayin.data.TransportInstrumentedTest') }
    else { $instrumentationArguments += @('-e', 'notClass', 'seeyuer.sayin.asr.DoubaoLiveInstrumentedTest,seeyuer.sayin.data.DiscoveryInstrumentedTest') }
    $instrumentationArguments += 'seeyuer.sayin.test/androidx.test.runner.AndroidJUnitRunner'
    & $adbPath @instrumentationArguments > $logPath 2>&1
    $testOutput = Get-Content -LiteralPath $logPath -Raw
    $expectedTests = if ($TransportOnly) { 1 } else { 37 }
    $successPattern = if ($TransportOnly) { 'OK \(1 test\)' } else { 'OK \(37 tests\)' }
    if ($LASTEXITCODE -ne 0 -or $testOutput -notmatch $successPattern -or $testOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw "Instrumentation failed; see $logPath" }
    Start-Sleep -Milliseconds 150
    $calls = Get-Content -LiteralPath (Join-Path $fixtureDirectory 'calls.json') -Raw | ConvertFrom-Json
    if ($calls.fake_input_calls -ne 2) { throw 'Expected one injected input and one uncertain attempt; reconnect must not repeat either.' }
    $evidence = @{ fixture = 'USB reverse -> loopback; fake input only'; instrumentation_tests = $expectedTests; fake_input_calls = $calls.fake_input_calls; transport_only = [bool]$TransportOnly }
    $evidence | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectRoot 'android\transport-test-result.json') -Encoding utf8
    Write-Output "Android verification: $expectedTests test(s) passed; transport_only=$TransportOnly; exactly two fake attempts (one injected, one uncertain), with no replay across reconnect/reconstruction."
} finally {
    if ($process -and !$process.HasExited) {
        Set-Content -LiteralPath (Join-Path $fixtureDirectory 'stop') -Value '' -NoNewline
        if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id }
    }
    & $adbPath -s $DeviceSerial reverse --remove tcp:17888 2>$null | Out-Null
    if ($UseExistingRootInstall) { & $adbPath -s $DeviceSerial shell rm -f /data/local/tmp/voicelink-debug.apk /data/local/tmp/voicelink-test.apk | Out-Null }
    if (Test-Path -LiteralPath (Join-Path $fixtureDirectory 'fixture.json')) { Remove-Item -LiteralPath (Join-Path $fixtureDirectory 'fixture.json') }
}
