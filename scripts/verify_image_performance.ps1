param(
    [string]$DeviceSerial = 'dc39c31d',
    [string]$ImageDirectory = (Join-Path $PSScriptRoot '../refer/image-test'),
    [ValidateRange(1,100)][int]$Repeats = 3,
    [switch]$Native,
    [switch]$PauseProbe,
    [switch]$SlowLink,
    [switch]$ReverseImages,
    [switch]$SkipBuild,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
if ($PauseProbe -and $Native) { throw 'PauseProbe is a zero-input fake-adapter scenario; do not combine with Native' }
if ($SlowLink -and $Native) { throw 'SlowLink is an isolated fake-adapter probe; do not combine with Native' }
if (!$PauseProbe -and $Repeats -gt 5) { throw 'Image performance sampling supports at most five rounds per run' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$targetDirectory = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $projectRoot 'desktop/target' }
function Checked([scriptblock]$action) { & $action; if ($LASTEXITCODE -ne 0) { throw 'Benchmark command failed' } }
function Measure-PhoneClock {
    $frequency = [Diagnostics.Stopwatch]::Frequency
    $info = [Diagnostics.ProcessStartInfo]::new($adb)
    foreach ($argument in @('-s', $DeviceSerial, 'shell', '-T')) { $info.ArgumentList.Add($argument) }
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $clock = [Diagnostics.Process]::Start($info)
    try {
        $clock.StandardInput.WriteLine('echo clock-ready')
        if ($clock.StandardOutput.ReadLineAsync().WaitAsync([TimeSpan]::FromSeconds(5)).GetAwaiter().GetResult() -ne 'clock-ready') {
            throw 'Phone clock shell unavailable'
        }
        $bounds = foreach ($probe in 1..10) {
            $before = [Diagnostics.Stopwatch]::GetTimestamp() * (1e9 / $frequency)
            $clock.StandardInput.WriteLine('cat /proc/uptime')
            $uptime = $clock.StandardOutput.ReadLineAsync().WaitAsync([TimeSpan]::FromSeconds(5)).GetAwaiter().GetResult()
            $after = [Diagnostics.Stopwatch]::GetTimestamp() * (1e9 / $frequency)
            if ($uptime -notmatch '^([0-9]+\.[0-9]{2}) ') { throw 'Phone monotonic clock unavailable' }
            $phone = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture) * 1e9
            # /proc/uptime truncates boottime to centiseconds.
            @{ lower = $before - $phone - 1e7; upper = $after - $phone }
        }
        @{ lower = ($bounds.lower | Measure-Object -Maximum).Maximum; upper = ($bounds.upper | Measure-Object -Minimum).Minimum }
    } finally {
        if (!$clock.HasExited) { $clock.Kill(); $clock.WaitForExit() }
        $clock.Dispose()
    }
}
if (!$SkipBuild) {
    Checked { & (Join-Path $PSScriptRoot 'gradle.ps1') '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest }
    Push-Location (Join-Path $projectRoot 'desktop')
    try { Checked { cargo build --release -p voicelink-receiver --example native_input_fixture -p voicelink-transport --example android_fixture --locked } } finally { Pop-Location }
}
$metadata = Get-Content (Join-Path $projectRoot 'android/app/build/outputs/apk/imageTest/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'seeyuer.sayin.imagetest') { throw 'Refusing the daily application' }
$sources = @(Get-ChildItem -LiteralPath $ImageDirectory -File | Where-Object Extension -eq '.jpg' | Sort-Object Name)
if ($sources.Count -ne 8 -or @($sources | Where-Object Name -notmatch '^[a-zA-Z0-9_.-]+$').Count) { throw 'Expected the eight named image-test JPEG samples' }
$rootIdentity = & $adb -s $DeviceSerial shell su -c id
if ($LASTEXITCODE -ne 0 -or $rootIdentity -notmatch 'uid=0\(root\)') { throw 'Existing Root install authorization is required; no settings are changed' }
$tag = [guid]::NewGuid().ToString()
$directory = Join-Path $targetDirectory "image-performance-$tag"
$fixturePath = Join-Path $directory 'fixture.json'
$remoteDirectory = '/sdcard/Android/data/seeyuer.sayin.imagetest/files/image-performance'
$process = $null; $instrument = $null; $proxy = $null; $remoteApks = @()
$corePeak = 0L; $targetPeak = 0L; $targetProcess = $null
$lease = [System.Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
if (!$lease.WaitOne(0)) {
    $lease.Dispose()
    throw 'Another SayIn device verification is active'
}
try {
    if (!$SkipInstall) {
        foreach ($item in @(@('imageTest/app-imageTest.apk','app'),@('androidTest/imageTest/app-imageTest-androidTest.apk','test'))) {
            $remote = "/data/local/tmp/sayin-image-perf-$tag-$($item[1]).apk"; $remoteApks += $remote
            Checked { & $adb -s $DeviceSerial push (Join-Path $projectRoot ('android/app/build/outputs/apk/'+$item[0])) $remote }
            $result = & $adb -s $DeviceSerial shell "su -c 'pm install -r -t $remote'"
            if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') { throw 'Isolated benchmark install failed' }
        }
    }
    Checked { & $adb -s $DeviceSerial shell mkdir -p $remoteDirectory }
    foreach ($source in $sources) { Checked { & $adb -s $DeviceSerial push $source.FullName "$remoteDirectory/$($source.Name)" } }
    New-Item -ItemType Directory -Path $directory | Out-Null
    $arguments = @("`"$directory`"",'--images','--lan','--port=0','--benchmark')
    if ($Native) { $arguments += '--native-target', '--image-commit-timing' }
    if ($PauseProbe) { $arguments += '--pause-probe' }
    $process = Start-Process -FilePath (Join-Path $targetDirectory 'release/examples/android_fixture.exe') -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $directory 'desktop-stderr.log') -RedirectStandardOutput (Join-Path $directory 'desktop-stdout.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (!(Test-Path -LiteralPath $fixturePath)) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw "Fixture failed; see $directory" }
        Start-Sleep -Milliseconds 50
    }
    $fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
    if ($fixture.code -notmatch '^\d{8}$' -or $fixture.address -notmatch '^\d+\.\d+\.\d+\.\d+$') { throw 'Invalid fixture metadata' }
    $proxyPort = 0
    if ($SlowLink) {
        $proxyDirectory = Join-Path $directory 'slow-link'
        $proxyArgs = @("`"$(Join-Path $PSScriptRoot 'image_slow_link.py')`"",'--address',$fixture.address,'--port',"$($fixture.port)",'--directory',"`"$proxyDirectory`"")
        $proxy = Start-Process -FilePath 'python' -ArgumentList $proxyArgs -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $directory 'relay-stdout.log') -RedirectStandardError (Join-Path $directory 'relay-stderr.log')
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        while (!(Test-Path (Join-Path $proxyDirectory 'ready.json'))) {
            if ($proxy.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Isolated relay did not start' }
            Start-Sleep -Milliseconds 50
        }
        $proxyPort = (Get-Content (Join-Path $proxyDirectory 'ready.json') -Raw | ConvertFrom-Json).port
        if ($proxyPort -lt 1 -or $proxyPort -gt 65535) { throw 'Invalid relay port' }
    }
    if ($Native) {
        $window = Get-Content (Join-Path $directory 'target/window.json') -Raw | ConvertFrom-Json
        $targetProcess = Get-Process -Id $window.pid
        if ($targetProcess.ProcessName -ne 'native_input_fixture') { throw 'Wrong disposable target process' }
    }
    $testClass = if ($PauseProbe) { 'seeyuer.sayin.data.ImagePauseInstrumentedTest' } else { 'seeyuer.sayin.data.ImagePerformanceInstrumentedTest' }
    $arguments = @('-s',$DeviceSerial,'shell','am','instrument','-w','-r','-e','timeout_msec','600000','-e','imageBenchmark','true','-e','imagePauseProbe',([string][bool]$PauseProbe).ToLowerInvariant(),'-e','imageRepeats',"$Repeats",'-e','fixtureCode',$fixture.code,'-e','fixturePort',"$($fixture.port)",'-e','fixtureAddress',$fixture.address,'-e','class',$testClass,'seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner')
    $arguments = $arguments[0..($arguments.Count-2)] + @('-e','fixtureProxyPort',"$proxyPort",$arguments[-1])
    if ($ReverseImages) {
        $arguments = $arguments[0..($arguments.Count-2)] + @('-e','reverseImages','true',$arguments[-1])
    }
    if ($PauseProbe) {
        $arguments = $arguments[0..($arguments.Count-2)] + @('-e','imagePauseRepeats',"$Repeats",$arguments[-1])
    }
    if ($Native) {
        $clockStart = [Diagnostics.Stopwatch]::GetTimestamp()
        $beforeClock = Measure-PhoneClock
    }
    $instrument = Start-Process -FilePath $adb -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $directory 'instrumentation.log') -RedirectStandardError (Join-Path $directory 'instrumentation-stderr.log')
    $deadline = [DateTime]::UtcNow.AddMinutes(10)
    while (!$instrument.HasExited) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw "Benchmark service exited or timed out; see $directory" }
        $process.Refresh(); $corePeak = [Math]::Max($corePeak,$process.PeakWorkingSet64)
        if ($targetProcess -and !$targetProcess.HasExited) { $targetProcess.Refresh(); $targetPeak = [Math]::Max($targetPeak,$targetProcess.PeakWorkingSet64) }
        Start-Sleep -Milliseconds 100
    }
    $output = Get-Content (Join-Path $directory 'instrumentation.log') -Raw
    if ($instrument.ExitCode -ne 0 -or $output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]') { throw "Benchmark instrumentation failed; see $directory" }
    $remoteResult = if ($PauseProbe) { 'image-pause-result.json' } else { 'image-performance-result.json' }
    Checked { & $adb -s $DeviceSerial pull "/sdcard/Android/data/seeyuer.sayin.imagetest/files/$remoteResult" (Join-Path $directory 'mobile-result.json') }
    $calls = Get-Content (Join-Path $directory 'calls.json') -Raw | ConvertFrom-Json
    $expected = if ($PauseProbe) { 0 } else { 5*$Repeats }
    if (($Native -and ($calls.native_input_calls -ne $expected -or $calls.fake_input_calls -ne 0)) -or (!$Native -and ($calls.fake_input_calls -ne $expected -or $calls.native_input_calls -ne 0))) { throw 'Unexpected input count/replay' }
    Set-Content -LiteralPath (Join-Path $directory 'stop') -Value '' -NoNewline
    if (!$process.WaitForExit(15000)) { throw 'Service did not stop' }
    if ($PauseProbe) {
        $pauses = @(Get-Content (Join-Path $directory 'pause-metrics.json') -Raw | ConvertFrom-Json)
        $mobilePauses = Get-Content (Join-Path $directory 'mobile-result.json') -Raw | ConvertFrom-Json
        if ($pauses.Count -ne $Repeats -or @($mobilePauses.measurements).Count -ne $Repeats) { throw "Expected $Repeats actual pause/reconnect cycles" }
    }
    if ($Native) {
        # Wait for the disposable target to finish writing its final RTF observation.
        $targetProcess.Refresh()
        if (!$targetProcess.HasExited) {
            Set-Content -LiteralPath (Join-Path $directory 'target/stop') -Value '' -NoNewline
            if (!$targetProcess.WaitForExit(10000)) { throw 'Disposable RichEdit did not stop.' }
        }
        $final = Get-Content (Join-Path $directory 'target/final.json') -Raw | ConvertFrom-Json
        if ($final.observation -ne 'rich_edit_raw_text' -or $final.observed_images -ne $expected -or $final.pictures -ne 0 -or $final.text -ne 'existing|') { throw 'Disposable RichEdit did not verify every single paste without Enter' }
        $afterClock = Measure-PhoneClock
        $frequency = [Diagnostics.Stopwatch]::Frequency
        $drift = ([Diagnostics.Stopwatch]::GetTimestamp() - $clockStart) * (1e9 / $frequency) * 0.0001
        $lower = [Math]::Max($beforeClock.lower, $afterClock.lower) - $drift
        $upper = [Math]::Min($beforeClock.upper, $afterClock.upper) + $drift
        if ($upper -lt $lower -or $upper - $lower -gt 1e8) {
            throw "Phone/desktop clock alignment out of bounds ($lower..$upper ns)"
        }
        $registrations = @{}
        $commits = @{}
        $terminalSent = @{}
        foreach ($line in (Get-Content (Join-Path $directory 'desktop-stderr.log'))) {
            if ($line -match '^image_registered ([0-9a-f-]{36}) ([0-9]+)$') {
                $registrations.Add($Matches[1], [long]$Matches[2])
            }
            if ($line -match '^image_commit ([0-9a-f-]{36}) ([0-9a-f-]{36}) (accepted|executing|injected) ([0-9]+)$') {
                $commits.Add("$($Matches[1])/$($Matches[2])/$($Matches[3])", [long]$Matches[4])
            }
            if ($line -match '^receipt_sent ([0-9a-f-]{36}) injected ([0-9]+)$' -and !$terminalSent.ContainsKey($Matches[1])) {
                $terminalSent.Add($Matches[1], [long]$Matches[2])
            }
        }
        $mobile = Get-Content (Join-Path $directory 'mobile-result.json') -Raw | ConvertFrom-Json
        if ($registrations.Count -ne $expected -or $commits.Count -ne 3*$expected -or @($mobile.measurements).Count -ne $expected) { throw 'Missing desktop or mobile timing samples' }
        $segments = foreach ($sample in $mobile.measurements) {
            $ticks = $registrations[$sample.resource_id]
            if ($null -eq $ticks -or $null -eq $sample.send_at_nanos -or $null -eq $sample.terminal_at_nanos) { throw 'Unmatched image registration' }
            $registrations.Remove($sample.resource_id)
            $registered = $ticks * (1e9 / $frequency)
            $phoneEarliest = $registered - $upper
            $phoneLatest = $registered - $lower
            if ($phoneLatest -lt $sample.send_at_nanos -or $phoneEarliest -gt $sample.registration_ack_at_nanos) { throw 'Desktop registration outside verified send/ack interval' }
            $phoneEarliest = [Math]::Max($phoneEarliest, $sample.send_at_nanos)
            $phoneLatest = [Math]::Min($phoneLatest, $sample.registration_ack_at_nanos)
            $key = "$($sample.resource_id)/$($sample.request_id)"
            $accepted = $commits["$key/accepted"]
            $executing = $commits["$key/executing"]
            $injected = $commits["$key/injected"]
            $phoneInjected = $sample.phone_receipts_at_nanos.injected
            if ($null -eq $accepted -or $null -eq $executing -or $null -eq $injected -or $null -eq $phoneInjected -or
                $accepted -lt $ticks -or $executing -lt $accepted -or $injected -lt $executing -or
                $phoneInjected -lt $sample.registration_ack_at_nanos -or $phoneInjected -gt $sample.terminal_at_nanos) {
                throw 'Missing or out-of-order receipt timestamps'
            }
            $sent = $terminalSent[$sample.request_id]
            $received = $sample.phone_terminal_received_at_nanos
            $processing = $sample.phone_terminal_processing_at_nanos
            if ($null -eq $sent -or $null -eq $received -or $null -eq $processing -or
                $sent -lt $injected -or $received -lt $sample.registration_ack_at_nanos -or
                $processing -lt $received -or $phoneInjected -lt $processing) {
                throw 'Missing or out-of-order terminal delivery timestamps'
            }
            @{
                file = $sample.file; resource_id = $sample.resource_id; request_id = $sample.request_id; prepare_ms = $sample.prepare_ms
                upload_lower_ms = ($phoneEarliest - $sample.send_at_nanos) / 1e6
                upload_upper_ms = ($phoneLatest - $sample.send_at_nanos) / 1e6
                paste_lower_ms = ($sample.terminal_at_nanos - $phoneLatest) / 1e6
                paste_upper_ms = ($sample.terminal_at_nanos - $phoneEarliest) / 1e6
                registration_to_accepted_ms = ($accepted - $ticks) * (1e3 / $frequency)
                accepted_to_executing_ms = ($executing - $accepted) * (1e3 / $frequency)
                executing_to_injected_ms = ($injected - $executing) * (1e3 / $frequency)
                injected_to_phone_persisted_lower_ms = [Math]::Max(0, ($phoneInjected - ($injected * (1e9 / $frequency) - $lower)) / 1e6)
                injected_to_phone_persisted_upper_ms = ($phoneInjected - ($injected * (1e9 / $frequency) - $upper)) / 1e6
                desktop_commit_to_ws_send_ms = ($sent - $injected) * (1e3 / $frequency)
                ws_send_to_phone_callback_lower_ms = [Math]::Max(0, ($received - ($sent * (1e9 / $frequency) - $lower)) / 1e6)
                ws_send_to_phone_callback_upper_ms = [Math]::Max(0, ($received - ($sent * (1e9 / $frequency) - $upper)) / 1e6)
                phone_callback_to_processing_ms = ($processing - $received) / 1e6
                phone_processing_to_persisted_ms = ($phoneInjected - $processing) / 1e6
                phone_persisted_to_observed_ms = ($sample.terminal_at_nanos - $phoneInjected) / 1e6
            }
        }
        @{ clock_width_ms = ($upper - $lower) / 1e6; measurements = @($segments) } |
            ConvertTo-Json -Depth 4 | Set-Content (Join-Path $directory 'segments.json') -Encoding utf8
    }
    $scope = if ($PauseProbe) { 'Production LAN Repository / dual WSS, zero-input pause and reconnect scenario; text marker causes fixture-emulated local pause, explicit marker cancellation cues fixture-emulated local resume' } else { 'Native mode confirms a new embedded-object marker in RichEdit before returning the input outcome; fake mode excludes native input' }
    if ($proxy) {
        Set-Content (Join-Path $proxyDirectory 'stop') -Value '' -NoNewline
        if (!$proxy.WaitForExit(10000) -or $proxy.ExitCode -ne 0) { throw 'Relay did not stop cleanly' }
        $relayResult = Get-Content (Join-Path $proxyDirectory 'result.json') -Raw | ConvertFrom-Json
        if ($relayResult.rejected_connections -ne 0 -or @($relayResult.streams | Where-Object bytes -gt 0).Count -lt 4) { throw 'Expected control and image traffic through the relay without rejection' }
    }
    @{ real_lan=$true; adb_reverse=$false; slow_link=[bool]$SlowLink; native_target=[bool]$Native; pause_probe=[bool]$PauseProbe; input_calls=$expected; core_peak_working_set_bytes=$corePeak; target_peak_working_set_bytes=$targetPeak; release_core=$true; mobile='imageTest Debug'; scope=$scope; sources=@($sources | ForEach-Object { @{ name=$_.Name; bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } }) } | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $directory 'result.json') -Encoding utf8
    if ($PauseProbe) { Write-Output "PASS: $Repeats real LAN pause/reconnect/no-replay cycles; zero input calls. Evidence: $directory" }
    else { Write-Output "PASS: real LAN, $expected images, three bounded source rejections, native_target=$Native. Evidence: $directory" }
} finally {
    if ($instrument -and !$instrument.HasExited) { Stop-Process -Id $instrument.Id }
    if ($proxy -and !$proxy.HasExited) { Set-Content (Join-Path $proxyDirectory 'stop') -Value '' -NoNewline; if (!$proxy.WaitForExit(10000)) { Stop-Process -Id $proxy.Id } }
    if ($process -and !$process.HasExited) { Set-Content -LiteralPath (Join-Path $directory 'stop') -Value '' -NoNewline; if (!$process.WaitForExit(10000)) { Stop-Process -Id $process.Id } }
    if (Test-Path (Join-Path $directory 'target')) { Set-Content -LiteralPath (Join-Path $directory 'target/stop') -Value '' -NoNewline }
    foreach ($source in $sources) { & $adb -s $DeviceSerial shell rm -f "$remoteDirectory/$($source.Name)" | Out-Null }
    foreach ($remote in $remoteApks) { & $adb -s $DeviceSerial shell rm -f $remote | Out-Null }
    if (Test-Path -LiteralPath $fixturePath) { Remove-Item -LiteralPath $fixturePath }
    $lease.ReleaseMutex()
    $lease.Dispose()
}
