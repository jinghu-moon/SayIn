param([string]$DeviceSerial, [switch]$UseExistingRootInstall, [switch]$SkipBuild,
    [switch]$SkipInstall, [switch]$Lan, [switch]$TransportOnly, [switch]$FullSuite,
    [switch]$KeepUpgradeFixture, [switch]$QrPairing, [switch]$HotTargets, [switch]$PairReplacement, [switch]$PairReplacementExpiry,
    [switch]$ImageBatch, [switch]$NativeBatch, [switch]$BatchPathReceipt,
    [ValidateSet('Switch', 'Revoke')][string]$BatchInterruption,
    [switch]$MissingReceipt, [switch]$ControlPerformance, [switch]$NativeText, [switch]$ControlClick, [switch]$MeasureControlResources, [ValidateRange(1,100)][int]$ControlSamples = 100)
$ErrorActionPreference = 'Stop'
if ($KeepUpgradeFixture -and !$TransportOnly) { throw 'KeepUpgradeFixture requires TransportOnly.' }
if ($QrPairing -and (!$TransportOnly -or $KeepUpgradeFixture)) { throw 'QrPairing requires a fresh TransportOnly verification.' }
if ($HotTargets -and (!$TransportOnly -or $KeepUpgradeFixture -or $QrPairing)) { throw 'HotTargets requires a fresh TransportOnly verification.' }
if ($PairReplacement -and (!$TransportOnly -or $KeepUpgradeFixture -or $QrPairing -or $HotTargets)) { throw 'PairReplacement requires a fresh TransportOnly verification.' }
if ($PairReplacementExpiry -and (!$Lan -or $TransportOnly -or $FullSuite -or $PairReplacement -or $ImageBatch -or $BatchInterruption -or $ControlPerformance -or $MissingReceipt)) { throw 'PairReplacementExpiry requires a separate LAN run.' }
if ($ImageBatch -and ($FullSuite -or $TransportOnly)) { throw 'ImageBatch uses a separate image fixture.' }
if ($NativeBatch -and (!$ImageBatch -or !$Lan)) { throw 'NativeBatch requires ImageBatch and Lan.' }
if ($BatchPathReceipt -and !$ImageBatch) { throw 'BatchPathReceipt requires ImageBatch.' }
if ($BatchInterruption -and (!$ImageBatch -or $NativeBatch -or $BatchPathReceipt)) { throw 'BatchInterruption requires an isolated fake ImageBatch.' }
if ($ControlPerformance -and (!$Lan -or $FullSuite -or $TransportOnly -or $ImageBatch)) { throw 'ControlPerformance requires a separate LAN run.' }
if ($NativeText -and !$ControlPerformance) { throw 'NativeText requires ControlPerformance.' }
if ($MeasureControlResources -and !$NativeText) { throw 'MeasureControlResources requires NativeText.' }
if ($ControlClick -and !$NativeText) { throw 'ControlClick requires NativeText.' }
if ($MissingReceipt -and ($FullSuite -or $TransportOnly -or $ImageBatch -or $ControlPerformance)) { throw 'MissingReceipt requires a separate fixture.' }
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
    try {
        if ($NativeBatch -or $NativeText) { Invoke-Checked { cargo build --release -p voicelink-receiver --example native_input_fixture -p voicelink-transport --example android_fixture --locked } }
        elseif ($ControlPerformance) { Invoke-Checked { cargo build --release -p voicelink-transport --example android_fixture --locked } }
        else { Invoke-Checked { cargo build -p voicelink-transport --example android_fixture --locked } }
    } finally { Pop-Location }
}
$metadata = Get-Content (Join-Path $projectRoot 'android/app/build/outputs/apk/imageTest/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'seeyuer.sayin.imagetest') { throw 'Refusing to replace the daily application.' }
$targetDirectory = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $projectRoot 'desktop/target' }
$testTag = [guid]::NewGuid().ToString()
$fixtureDirectory = Join-Path $targetDirectory "image-android-fixture-$testTag"
$log = Join-Path $targetDirectory "image-android-$testTag.log"
$remoteApks = @()
$instrument = $null
$process = $null
$port = $null
$replacementPort = $null
$batchPort = $null
$additionalFixtures = @()
if (($Lan -and ($TransportOnly -or $FullSuite)) -or ($TransportOnly -and $FullSuite)) { throw 'FullSuite/TransportOnly are mutually exclusive loopback regressions; use the image suite for LAN.' }
$lease = [System.Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
$leaseAcquired = $false
$testClass = if ($ControlPerformance) { 'seeyuer.sayin.data.ControlPerformanceInstrumentedTest' }
    elseif ($HotTargets) { 'seeyuer.sayin.data.HotTargetsInstrumentedTest' }
    elseif ($PairReplacement -or $PairReplacementExpiry) { 'seeyuer.sayin.data.PairReplacementInstrumentedTest' }
    elseif ($MissingReceipt) { 'seeyuer.sayin.data.TransportInstrumentedTest#unknownReceiptRequiresExplicitRetryAfterReconnect' }
    elseif ($TransportOnly) { 'seeyuer.sayin.data.TransportInstrumentedTest#nativeCodePairingPinnedTlsEncryptedOutboxAndReconnect' }
    elseif ($BatchInterruption) { 'seeyuer.sayin.data.ImageInputInstrumentedTest#interruptedBatchKeepsItsFirstReceiptAndNeverSubmitsTheSecond' }
    elseif ($ImageBatch) { 'seeyuer.sayin.data.ImageInputInstrumentedTest#batchSubmitsInOrderAndStopsAtInvalidSecondImage' }
    else { 'seeyuer.sayin.data.ImageInputInstrumentedTest' }
$suiteName = if ($FullSuite) { 'text-and-ui' } else { $testClass }
$expectedTextUiTests = 181
$expectedImageTests = 7
$expectedTests = if ($FullSuite) { $expectedTextUiTests } elseif ($TransportOnly -or $ImageBatch -or $ControlPerformance -or $MissingReceipt -or $PairReplacementExpiry) { 1 } else { $expectedImageTests }
$expectedCalls = if ($HotTargets -or $ControlPerformance) { 0 } elseif ($PairReplacement -or $PairReplacementExpiry -or $BatchPathReceipt -or $BatchInterruption) { 1 } elseif ($ImageBatch) { 3 } elseif ($FullSuite -or $TransportOnly) { 2 } else { 1 }
try {
    $leaseAcquired = $lease.WaitOne(0)
    if (!$leaseAcquired) { throw 'Another SayIn device verification is active; run serially.' }
    $activityState = (& $adb -s $DeviceSerial shell dumpsys activity) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $activityState -match 'mRunningInstrumentation=InstrumentationRecord|Active instrumentation:') { throw 'Cannot obtain an idle instrumentation device.' }
    $policy = (& $adb -s $DeviceSerial shell dumpsys window policy) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the selected phone window policy.' }
    if (($FullSuite -or $ControlClick) -and $policy -match '(?m)^\s*showing=true\r?$' -and $policy -match '(?m)^\s*secure=true\r?$') { throw 'Unlock the selected phone before foreground UI verification.' }
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
    # The upgrade fixture has a reserved namespace and retains its Keystore key.
    # Regular suites start with fresh isolated package state.
    if (!$KeepUpgradeFixture) {
        Invoke-Checked { & $adb -s $DeviceSerial shell pm clear seeyuer.sayin.imagetest | Out-Null }
    }
    if ($FullSuite) {
        # The package-data-cleared broadcast can revoke a new grant after pm clear returns.
        Invoke-Checked { & $adb -s $DeviceSerial shell am wait-for-broadcast-idle | Out-Null }
    }
    $api = [int](& $adb -s $DeviceSerial shell getprop ro.build.version.sdk)
    if ($LASTEXITCODE -ne 0 -or $api -lt 29) { throw 'Cannot determine the supported Android API level.' }
    if ($api -ge 37) {
        Invoke-Checked { & $adb -s $DeviceSerial shell pm grant seeyuer.sayin.imagetest android.permission.ACCESS_LOCAL_NETWORK }
    }
    if ($FullSuite) {
        # Grant before the test process starts so its first microphone check sees the cleared package's new grant.
        Invoke-Checked { & $adb -s $DeviceSerial shell pm grant seeyuer.sayin.imagetest android.permission.RECORD_AUDIO }
    }
    $fixtureArguments = @("`"$fixtureDirectory`"", '--images', '--port=0', '--lifetime=7200', '--hold-retention-seconds=20')
    if ($HotTargets) { $fixtureArguments[-1] = '--hold-retention-seconds=120' }
    if ($Lan) { $fixtureArguments += '--lan' }
    if ($QrPairing) { $fixtureArguments += '--qr-pairing' }
    if ($PairReplacement) { $fixtureArguments += '--pair-replacement' }
    if ($PairReplacementExpiry) {
        $fixtureArguments[3] = '--hold-retention-seconds=600'
        $fixtureArguments += '--pair-replacement-expiry'
    }
    if ($NativeBatch) { $fixtureArguments += '--native-target' }
    if ($BatchPathReceipt) { $fixtureArguments += '--image-path-receipt' }
    if ($BatchInterruption) { $fixtureArguments += "--batch-$($BatchInterruption.ToLowerInvariant())" }
    $profile = if ($NativeBatch -or $ControlPerformance) { 'release' } else { 'debug' }
    $process = Start-Process -FilePath (Join-Path $targetDirectory "$profile/examples/android_fixture.exe") -ArgumentList $fixtureArguments -PassThru -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    $fixturePath = Join-Path $fixtureDirectory 'fixture.json'
    while (!(Test-Path -LiteralPath $fixturePath)) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Image fixture did not start.' }
        Start-Sleep -Milliseconds 50
    }
    $fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
    $port = [int]$fixture.port
    if ($port -le 1024 -or $port -gt 65535 -or $fixture.code -notmatch '^\d{8}$') { throw 'Invalid fixture metadata.' }
    if (!$Lan) { Invoke-Checked { & $adb -s $DeviceSerial reverse "tcp:$port" "tcp:$port" } }
    $arguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'timeout_msec', '120000', '-e', 'fixtureCode', $fixture.code, '-e', 'fixturePort', $port, '-e', 'fixtureAddress', $fixture.address)
    if ($PairReplacement -or $PairReplacementExpiry) {
        $replacementPort = [int]$fixture.replacement_port
        if ($replacementPort -le 1024 -or $replacementPort -gt 65535) { throw 'Invalid replacement fixture metadata.' }
        Invoke-Checked { & $adb -s $DeviceSerial reverse "tcp:$replacementPort" "tcp:$replacementPort" }
        $arguments += @('-e', 'replacementControlPort', $replacementPort)
    }
    if ($PairReplacementExpiry) { $arguments += @('-e','pairReplacementExpiry','true') }
    if ($ControlPerformance) { $arguments += @('-e','controlPerformance','true','-e','controlSamples',$ControlSamples) }
    if ($NativeText) { $arguments += @('-e','nativeText','true') }
    if ($ControlClick) { $arguments += @('-e','controlClick','true','-e','timeout_msec','600000') }
    if ($BatchInterruption) {
        $batchPort = [int]$fixture.batch_port
        if ($batchPort -le 1024 -or $batchPort -gt 65535) { throw 'Invalid batch control port.' }
        Invoke-Checked { & $adb -s $DeviceSerial reverse "tcp:$batchPort" "tcp:$batchPort" }
        $arguments += @('-e', 'batchControlPort', $batchPort, '-e', 'batchInterruption', $BatchInterruption)
    }
    if ($HotTargets -or $ControlPerformance -or $BatchInterruption -eq 'Switch') {
        $positions = if ($BatchInterruption) { @('second') } else { @('second', 'third') }
        foreach ($position in $positions) {
            $directory = Join-Path $fixtureDirectory $position
            $extraArguments = @("`"$directory`"", '--port=0', '--lifetime=7200', '--hold-retention-seconds=120')
            if ($Lan) { $extraArguments += '--lan' }
            if ($BatchInterruption) { $extraArguments += '--images' }
            if ($ControlPerformance -and $position -eq 'second') {
                $extraArguments += '--text-commit-timing'
                if ($NativeText) { $extraArguments += @('--images', '--native-text-target') }
            }
            $startOptions = @{}
            if ($ControlPerformance -and $position -eq 'second') {
                New-Item -ItemType Directory -Path $directory | Out-Null
                $startOptions.RedirectStandardError = Join-Path $directory 'desktop-stderr.log'
            }
            $extra = @{ directory = $directory; process = (Start-Process -FilePath (Join-Path $targetDirectory "$profile/examples/android_fixture.exe") -ArgumentList $extraArguments -PassThru -WindowStyle Hidden @startOptions); port = $null }
            $additionalFixtures += $extra
            $extraPath = Join-Path $directory 'fixture.json'
            $deadline = [DateTime]::UtcNow.AddSeconds(10)
            while (!(Test-Path -LiteralPath $extraPath)) {
                if ($extra.process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Additional control fixture did not start.' }
                Start-Sleep -Milliseconds 50
            }
            $endpoint = Get-Content -LiteralPath $extraPath -Raw | ConvertFrom-Json
            $extra.port = [int]$endpoint.port
            if ($extra.port -le 1024 -or $extra.port -gt 65535 -or $endpoint.code -notmatch '^\d{8}$') { throw 'Invalid additional fixture metadata.' }
            if (!$Lan) { Invoke-Checked { & $adb -s $DeviceSerial reverse "tcp:$($extra.port)" "tcp:$($extra.port)" } }
            $arguments += @('-e', "${position}FixturePort", $extra.port, '-e', "${position}FixtureCode", $endpoint.code, '-e', "${position}FixtureAddress", $endpoint.address)
        }
    }
    if ($FullSuite) { $arguments += @('-e', 'notAnnotation', 'seeyuer.sayin.ui.RootHelperDeviceTest') }
    # Pairing invitations and active-device gates are single-use state. The
    # image data suite gets its own fresh desktop identity, not a reset gate.
    if ($FullSuite) { $arguments += @('-e', 'notClass', 'seeyuer.sayin.asr.DoubaoLiveInstrumentedTest,seeyuer.sayin.asr.QwenLiveInstrumentedTest,seeyuer.sayin.data.DiscoveryInstrumentedTest,seeyuer.sayin.data.ImageInputInstrumentedTest,seeyuer.sayin.data.ImagePerformanceInstrumentedTest,seeyuer.sayin.data.ImagePauseInstrumentedTest,seeyuer.sayin.data.ControlPerformanceInstrumentedTest,seeyuer.sayin.data.PlatformNetworkInstrumentedTest,seeyuer.sayin.data.UpgradeInstrumentedTest,seeyuer.sayin.ui.TranslationNativeInstrumentedTest,seeyuer.sayin.ui.ForegroundLiveInstrumentedTest') }
    else { $arguments += @('-e', 'class', $testClass) }
    if ($ImageBatch) { $arguments += @('-e', 'fixtureImageBatch', 'true') }
    if ($MissingReceipt) { $arguments += @('-e', 'fixtureMissingReceipt', 'true') }
    if ($BatchPathReceipt) { $arguments += @('-e', 'fixtureBatchPathReceipt', 'true') }
    if ($KeepUpgradeFixture) { $arguments += @('-e', 'keepUpgradeFixture', 'true') }
    if ($QrPairing) {
        if ($fixture.qr -notmatch '^[A-Za-z0-9_-]{152}$') { throw 'Invalid QR fixture metadata.' }
        $arguments += @('-e', 'fixtureQr', $fixture.qr)
    }
    $arguments += 'seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner'
    if ($MeasureControlResources) {
        $timedDirectory = $additionalFixtures[0].directory
        $core = $additionalFixtures[0].process
        $window = Get-Content -LiteralPath (Join-Path $timedDirectory 'target/window.json') -Raw | ConvertFrom-Json
        $edit = Get-Process -Id $window.pid
        if ($edit.ProcessName -ne 'native_input_fixture') { throw 'Wrong disposable EDIT process.' }
        $instrument = Start-Process -FilePath $adb -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.stderr"
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $resourceSamples = [Collections.Generic.List[object]]::new()
        while (!$instrument.WaitForExit(2000)) {
            $core.Refresh()
            $edit.Refresh()
            if ($core.HasExited -or $edit.HasExited -or $clock.Elapsed.TotalMinutes -gt 11) { throw 'Resource sampling lost its target or timed out.' }
            $socketCount = @(
                Get-NetTCPConnection -OwningProcess $core.Id -State Established -ErrorAction SilentlyContinue |
                    Where-Object { $null -ne $_ }
            ).Count
            $disk = Get-ChildItem -LiteralPath $timedDirectory -File -Filter 'receiver.sqlite3*' |
                Measure-Object -Property Length -Sum
            $io = Get-CimInstance Win32_PerfRawData_PerfProc_Process -Filter "IDProcess=$($core.Id)"
            $meminfo = & $adb -s $DeviceSerial shell dumpsys meminfo seeyuer.sayin.imagetest
            $pss = $meminfo | Select-String '^\s*TOTAL PSS:\s*(\d+)' | Select-Object -First 1
            $resourceSamples.Add(@{
                seconds = $clock.Elapsed.TotalSeconds
                core_cpu_seconds = $core.TotalProcessorTime.TotalSeconds
                edit_cpu_seconds = $edit.TotalProcessorTime.TotalSeconds
                core_private_bytes = $core.PrivateMemorySize64
                edit_private_bytes = $edit.PrivateMemorySize64
                core_working_set_bytes = $core.WorkingSet64
                edit_working_set_bytes = $edit.WorkingSet64
                core_io_read_bytes = if ($io) { $io.IOReadBytesPersec } else { $null }
                core_io_write_bytes = if ($io) { $io.IOWriteBytesPersec } else { $null }
                core_established_tcp = $socketCount
                sqlite_file_bytes = $disk.Sum
                android_pss_kib = if ($pss -and [long]$pss.Matches[0].Groups[1].Value -gt 0) { [long]$pss.Matches[0].Groups[1].Value } else { $null }
            })
        }
        $instrumentExit = $instrument.ExitCode
        @{ windows_logical_processors = [Environment]::ProcessorCount; core_pid = $core.Id; edit_pid = $edit.Id; samples = @($resourceSamples.ToArray()) } |
            ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'control-resources.json') -Encoding utf8
    } else {
        & $adb @arguments > $log 2>&1
        $instrumentExit = $LASTEXITCODE
    }
    if ($ControlPerformance) {
        Invoke-Checked { & $adb -s $DeviceSerial pull '/sdcard/Android/data/seeyuer.sayin.imagetest/files/control-performance-result.json' (Join-Path $fixtureDirectory 'control-performance.json') }
    }
    $output = Get-Content -LiteralPath $log -Raw
    if ($instrumentExit -ne 0 -or $output -notmatch "OK \($expectedTests tests?\)" -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "Instrumentation failed; see $log" }
    $skippedTests = [regex]::Matches($output, '(?m)^INSTRUMENTATION_STATUS_CODE: -[34]\r?$').Count
    $passedTests = $expectedTests - $skippedTests
    Start-Sleep -Milliseconds 200
    $calls = Get-Content -LiteralPath (Join-Path $fixtureDirectory 'calls.json') -Raw | ConvertFrom-Json
    if ($NativeBatch) {
        if ($calls.native_input_calls -ne $expectedCalls -or $calls.fake_input_calls -ne 0) { throw 'Unexpected native input call count.' }
        if ($BatchPathReceipt) {
            $exports = @(Get-ChildItem -LiteralPath (Join-Path $fixtureDirectory 'exports') -File)
            if ($exports.Count -ne 1 -or $exports[0].Length -le 0 -or (Get-Clipboard -Raw) -cne $exports[0].FullName) {
                throw 'Expected exactly one exported image and its path on the Windows clipboard.'
            }
            $window = Get-Content (Join-Path $fixtureDirectory 'target/window.json') -Raw | ConvertFrom-Json
            $target = Get-Process -Id $window.pid
            if ($target.ProcessName -ne 'native_input_fixture') { throw 'Wrong disposable RichEdit process.' }
            Set-Content -LiteralPath (Join-Path $fixtureDirectory 'target/stop') -Value '' -NoNewline
            if (!$target.WaitForExit(10000)) { throw 'Disposable RichEdit did not stop.' }
            Set-Content -LiteralPath (Join-Path $fixtureDirectory 'stop') -Value '' -NoNewline
            if (!$process.WaitForExit(15000) -or $process.ExitCode -ne 0) { throw 'Native fixture did not shut down cleanly.' }
            $final = Get-Content (Join-Path $fixtureDirectory 'target/final.json') -Raw | ConvertFrom-Json
            if ($final.observed_images -ne 0 -or $final.pictures -ne 0 -or $final.text -ne 'existing|') {
                throw 'Path copy modified the disposable RichEdit content.'
            }
        } else {
            $observed = Get-Content (Join-Path $fixtureDirectory 'target/observed.json') -Raw | ConvertFrom-Json
            if ($observed.observed_images -ne $expectedCalls -or $observed.pictures -ne 1 -or
                $observed.text -ne 'existing| ' -or ($observed.picture_widths_twips -join ',') -ne '465,510,465') {
                throw 'RichEdit did not observe the expected picture sequence.'
            }
        }
    } elseif ($calls.fake_input_calls -ne $expectedCalls) { throw "Expected exactly $expectedCalls fake input calls; cancellation/recovery must not replay." }
    if ($BatchInterruption) {
        foreach ($extra in $additionalFixtures) {
            $extraCalls = Get-Content -LiteralPath (Join-Path $extra.directory 'calls.json') -Raw | ConvertFrom-Json
            if ($extraCalls.fake_input_calls -ne 0 -or $extraCalls.native_input_calls -ne 0) { throw 'The new target received unexpected input.' }
        }
        Set-Content -LiteralPath (Join-Path $fixtureDirectory 'stop') -Value '' -NoNewline
        if (!$process.WaitForExit(15000) -or $process.ExitCode -ne 0) { throw 'Interrupted batch fixture did not persist exactly one input outcome.' }
    }
    if ($ControlPerformance) {
        $samples = Get-Content -LiteralPath (Join-Path $fixtureDirectory 'control-performance.json') -Raw | ConvertFrom-Json
        if (@($samples.warm_text).Count -ne $ControlSamples -or @($samples.cold_switch).Count -ne $ControlSamples -or $skippedTests -ne 0) { throw 'Missing control performance samples.' }
        for ($index = 0; $index -lt $additionalFixtures.Count; $index++) {
            $extraCalls = Get-Content -LiteralPath (Join-Path $additionalFixtures[$index].directory 'calls.json') -Raw | ConvertFrom-Json
            $expectedNative = $ControlSamples * [int]($NativeText -and $index -eq 0)
            $expectedFake = $ControlSamples * [int](!$NativeText -and $index -eq 0)
            if ($extraCalls.native_input_calls -ne $expectedNative -or $extraCalls.fake_input_calls -ne $expectedFake) { throw 'Unexpected control fixture input count.' }
        }
        $timedFixture = $additionalFixtures[0]
        Set-Content -LiteralPath (Join-Path $timedFixture.directory 'stop') -Value '' -NoNewline
        if (!$timedFixture.process.WaitForExit(15000) -or $timedFixture.process.ExitCode -ne 0) { throw 'Timed fixture did not stop cleanly.' }
        if ($NativeText) {
            $final = Get-Content -LiteralPath (Join-Path $timedFixture.directory 'target/final.json') -Raw | ConvertFrom-Json
            if ($final.observed_texts -ne $ControlSamples -or $final.text -cne 'existing|') { throw 'Disposable EDIT did not observe every exact text sample.' }
        }
        $commits = Get-Content -LiteralPath (Join-Path $timedFixture.directory 'text-commits.json') -Raw | ConvertFrom-Json -NoEnumerate
        if ($commits.Count -ne (9 * $ControlSamples)) { throw 'Missing desktop commit timestamps.' }
        $desktop = @{}
        foreach ($commit in $commits) {
            if (!$desktop.ContainsKey($commit[0])) { $desktop[$commit[0]] = @{} }
            $desktop[$commit[0]].Add($commit[1], [long]$commit[2])
        }
        $preflights = @{}
        $nativeTimings = @{}
        $nativeStages = @{}
        if ($NativeText) {
            foreach ($line in (Get-Content -LiteralPath (Join-Path $timedFixture.directory 'desktop-stderr.log'))) {
                if ($line -match '^text_preflight ([0-9a-f-]{36}) ([0-9]+)$') {
                    $preflights.Add($Matches[1], [long]$Matches[2])
                }
                if ($line -match '^text_native ([0-9a-f-]{36}) ([0-9]+) ([0-9]+)$') {
                    $nativeTimings.Add($Matches[1], @([long]$Matches[2], [long]$Matches[3]))
                }
                if ($line -match '^text_stage ([0-9a-f-]{36}) (execution_guard|send_guard|send_input|post_input_guard) ([0-9]+)$') {
                    if (!$nativeStages.ContainsKey($Matches[1])) { $nativeStages[$Matches[1]] = @{} }
                    $nativeStages[$Matches[1]].Add($Matches[2], [long]$Matches[3])
                }
            }
            if ($preflights.Count -ne $ControlSamples -or $nativeTimings.Count -ne $ControlSamples) { throw 'Missing native text timings.' }
        }
        $stagesInOrder = @('accepted', 'deadline_begin', 'deadline_ready', 'preflight_returned', 'execution_check_complete', 'executing_write_begin', 'executing', 'outcome_returned', 'injected')
        foreach ($sample in $samples.warm_text) {
            $times = $desktop[$sample.id]
            if (!$times -or $times.Count -ne 9 -or !$times.ContainsKey('accepted') -or !$times.ContainsKey('executing') -or !$times.ContainsKey('injected') -or
                $times.accepted -gt $times.executing -or $times.executing -gt $times.injected) { throw 'Unmatched or invalid text commit timestamps.' }
            $sample | Add-Member -NotePropertyName accepted_to_executing_ms -NotePropertyValue (($times.executing - $times.accepted) / 1e6)
            $sample | Add-Member -NotePropertyName executing_to_terminal_ms -NotePropertyValue (($times.injected - $times.executing) / 1e6)
            for ($stageIndex = 1; $stageIndex -lt $stagesInOrder.Count; $stageIndex++) {
                $from = $stagesInOrder[$stageIndex - 1]
                $to = $stagesInOrder[$stageIndex]
                if (!$times.ContainsKey($to) -or $times[$from] -gt $times[$to]) { throw "Invalid desktop timing at $to." }
                $sample | Add-Member -NotePropertyName ("${from}_to_${to}_ms") -NotePropertyValue (($times[$to] - $times[$from]) / 1e6)
            }
            if ($NativeText) {
                $nativeTiming = $nativeTimings[$sample.id]
                if ($null -eq $nativeTiming -or !$preflights.ContainsKey($sample.id)) { throw 'Unmatched native text timings.' }
                $sample | Add-Member -NotePropertyName preflight_ms -NotePropertyValue ($preflights[$sample.id] / 1e6)
                $sample | Add-Member -NotePropertyName native_execute_ms -NotePropertyValue ($nativeTiming[0] / 1e6)
                $sample | Add-Member -NotePropertyName fixture_observation_ms -NotePropertyValue ($nativeTiming[1] / 1e6)
                $stages = $nativeStages[$sample.id]
                if ($stages.Count -ne 4) { throw 'Missing native text execution stages.' }
                foreach ($stage in $stages.Keys) {
                    $sample | Add-Member -NotePropertyName ($stage + '_ms') -NotePropertyValue ($stages[$stage] / 1e6)
                }
            }
            $desktop.Remove($sample.id)
        }
        $samples | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'control-segments.json') -Encoding utf8
        $expectedCalls = $ControlSamples
        if ($ControlClick) {
            if (!$samples.click_through_ui) { throw 'Missing Compose click evidence.' }
            $controlGates = foreach ($metric in @(
                @{ key = 'first_ms'; limit = 150; timeout = 500; rows = $samples.warm_text }
                @{ key = 'ready_ms'; limit = 1000; timeout = 3000; rows = $samples.cold_switch }
                @{ key = 'accepted_to_executing_ms'; limit = 20; timeout = 500; rows = $samples.warm_text }
                @{ key = 'executing_to_terminal_ms'; limit = 30; timeout = 500; rows = $samples.warm_text }
            )) {
                $values = @($metric.rows | ForEach-Object {
                    $value = $_.($metric.key)
                    if ($null -eq $value -or $value -lt 0) { throw "Invalid sample for $($metric.key)." }
                    [double]$value
                } | Sort-Object)
                $p95 = $values[[int][Math]::Ceiling($ControlSamples * 0.95) - 1]
                $timeouts = @($values | Where-Object { $_ -gt $metric.timeout }).Count
                $gate = if ($ControlSamples -lt 100) { 'smoke' }
                    elseif ($p95 -le $metric.limit -and $timeouts / $ControlSamples -le 0.01) { 'pass' }
                    else { 'fail' }
                Write-Host ("{0}: {1}, p95 {2:N2} ms (limit {3} ms), {4}/{5} over {6} ms." -f
                    $metric.key, $gate, $p95, $metric.limit, $timeouts, $ControlSamples, $metric.timeout)
                @{ metric = $metric.key; samples = $ControlSamples; p95_ms = $p95; limit_ms = $metric.limit; timeouts = $timeouts; timeout_ms = $metric.timeout; gate = $gate }
            }
            $controlGates[0] | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'click-receipt-gate.json') -Encoding utf8
            $controlGates | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'control-gates.json') -Encoding utf8
        }
    }
    if ($HotTargets) {
        for ($index = 0; $index -lt $additionalFixtures.Count; $index++) {
            $directory = $additionalFixtures[$index].directory
            $extraCalls = Get-Content -LiteralPath (Join-Path $directory 'calls.json') -Raw | ConvertFrom-Json
            if ($extraCalls.fake_input_calls -ne (1 - $index)) { throw 'A control fixture received an unexpected input count.' }
        }
        $expectedCalls = 1
    }
    if (($KeepUpgradeFixture -or $ImageBatch -or $MissingReceipt -or $PairReplacementExpiry) -and $skippedTests -ne 0) { throw 'The requested isolated fixture was not exercised.' }
    $inputMode = if ($NativeBatch) { 'native RichEdit' } elseif ($NativeText) { 'native EDIT' } else { 'fake' }
    @{ scope = "isolated imageTest UID/identity/data; $inputMode input"; suite = $suiteName; real_lan = [bool]$Lan; batch_interruption = $BatchInterruption; pair_replacement_expiry = [bool]$PairReplacementExpiry; tests = $expectedTests; passed = $passedTests; skipped = $skippedTests; fake_input_calls = if ($NativeText) { 0 } elseif ($ControlPerformance -or $HotTargets) { $expectedCalls } else { $calls.fake_input_calls }; native_input_calls = if ($NativeText) { $ControlSamples } else { $calls.native_input_calls }; retained_upgrade_fixture = [bool]$KeepUpgradeFixture } | ConvertTo-Json | Set-Content (Join-Path $fixtureDirectory 'result.json') -Encoding utf8
    if ($ControlClick) {
        $failedGates = @($controlGates | Where-Object { $_.gate -eq 'fail' } | ForEach-Object { $_.metric })
        if ($failedGates.Count) { throw "Control performance gates failed: $($failedGates -join ', '). Evidence: $fixtureDirectory" }
    }
    Write-Output "PASS: $passedTests passed, $skippedTests skipped; $expectedCalls $inputMode input calls, no replay. Evidence: $fixtureDirectory"
    if ($FullSuite) {
        & $PSCommandPath -DeviceSerial $DeviceSerial -SkipBuild -SkipInstall
        if (!$?) { throw 'Image data suite failed in its separate identity.' }
        Write-Output "PASS: text/UI and image suites completed across independent desktop identities."
    }
} finally {
    try {
        if ($leaseAcquired) {
            if ($instrument -and !$instrument.HasExited) { Stop-Process -Id $instrument.Id }
            if ($process -and !$process.HasExited) {
                Set-Content -LiteralPath (Join-Path $fixtureDirectory 'stop') -Value '' -NoNewline
                if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id }
            }
            if ($port -and !$Lan) { & $adb -s $DeviceSerial reverse --remove "tcp:$port" 2>$null | Out-Null }
            if ($replacementPort) { & $adb -s $DeviceSerial reverse --remove "tcp:$replacementPort" 2>$null | Out-Null }
            if ($batchPort) { & $adb -s $DeviceSerial reverse --remove "tcp:$batchPort" 2>$null | Out-Null }
            foreach ($extra in $additionalFixtures) {
                if (!$extra.process.HasExited) {
                    Set-Content -LiteralPath (Join-Path $extra.directory 'stop') -Value '' -NoNewline
                    if (!$extra.process.WaitForExit(5000)) { Stop-Process -Id $extra.process.Id }
                }
                if ($extra.port -and !$Lan) { & $adb -s $DeviceSerial reverse --remove "tcp:$($extra.port)" 2>$null | Out-Null }
                $extraPath = Join-Path $extra.directory 'fixture.json'
                if (Test-Path -LiteralPath $extraPath) { Remove-Item -LiteralPath $extraPath }
            }
            foreach ($remote in $remoteApks) { & $adb -s $DeviceSerial shell rm -f $remote | Out-Null }
            if (Test-Path -LiteralPath (Join-Path $fixtureDirectory 'fixture.json')) { Remove-Item -LiteralPath (Join-Path $fixtureDirectory 'fixture.json') }
        }
    } finally {
        if ($leaseAcquired) { $lease.ReleaseMutex() }
        $lease.Dispose()
    }
}
