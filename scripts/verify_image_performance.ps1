param(
    [string]$DeviceSerial = 'dc39c31d',
    [string]$ImageDirectory = 'D:/100_Projects/110_Daily/VoiceInput/refer/image-test',
    [ValidateRange(1,5)][int]$Repeats = 3,
    [switch]$Native,
    [switch]$PauseProbe,
    [switch]$SlowLink,
    [switch]$SkipBuild,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
if ($PauseProbe -and $Native) { throw 'PauseProbe is a zero-input fake-adapter scenario; do not combine with Native' }
if ($SlowLink -and $Native) { throw 'SlowLink is an isolated fake-adapter probe; do not combine with Native' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$targetDirectory = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $projectRoot 'desktop/target' }
function Checked([scriptblock]$action) { & $action; if ($LASTEXITCODE -ne 0) { throw 'Benchmark command failed' } }
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
    if ($Native) { $arguments += '--native-target' }
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
        if ($pauses.Count -ne 5 -or @($mobilePauses.measurements).Count -ne 5) { throw 'Expected five actual pause/reconnect cycles' }
    }
    if ($Native) {
        $final = Get-Content (Join-Path $directory 'target/final.json') -Raw | ConvertFrom-Json
        if ($final.observed_images -ne $expected -or $final.pictures -ne 0 -or $final.text -ne 'existing|') { throw 'Disposable RichEdit did not verify every single paste without Enter' }
    }
    $scope = if ($PauseProbe) { 'Production LAN Repository / dual WSS, zero-input pause and reconnect scenario; text marker causes fixture-emulated local pause, explicit marker cancellation cues fixture-emulated local resume' } else { 'Native mode includes test-only RichEdit RTF acknowledgement latency; fake mode excludes native input' }
    if ($proxy) {
        Set-Content (Join-Path $proxyDirectory 'stop') -Value '' -NoNewline
        if (!$proxy.WaitForExit(10000) -or $proxy.ExitCode -ne 0) { throw 'Relay did not stop cleanly' }
        $relayResult = Get-Content (Join-Path $proxyDirectory 'result.json') -Raw | ConvertFrom-Json
        if ($relayResult.rejected_connections -ne 0 -or @($relayResult.streams | Where-Object bytes -gt 0).Count -lt 4) { throw 'Expected control and image traffic through the relay without rejection' }
    }
    @{ real_lan=$true; adb_reverse=$false; slow_link=[bool]$SlowLink; native_target=[bool]$Native; pause_probe=[bool]$PauseProbe; input_calls=$expected; core_peak_working_set_bytes=$corePeak; target_peak_working_set_bytes=$targetPeak; release_core=$true; mobile='imageTest Debug'; scope=$scope; sources=@($sources | ForEach-Object { @{ name=$_.Name; bytes=$_.Length; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } }) } | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $directory 'result.json') -Encoding utf8
    if ($PauseProbe) { Write-Output "PASS: five real LAN pause/reconnect/no-replay cycles; zero input calls. Evidence: $directory" }
    else { Write-Output "PASS: real LAN, $expected images, three bounded source rejections, native_target=$Native. Evidence: $directory" }
} finally {
    if ($instrument -and !$instrument.HasExited) { Stop-Process -Id $instrument.Id }
    if ($proxy -and !$proxy.HasExited) { Set-Content (Join-Path $proxyDirectory 'stop') -Value '' -NoNewline; if (!$proxy.WaitForExit(10000)) { Stop-Process -Id $proxy.Id } }
    if ($process -and !$process.HasExited) { Set-Content -LiteralPath (Join-Path $directory 'stop') -Value '' -NoNewline; if (!$process.WaitForExit(10000)) { Stop-Process -Id $process.Id } }
    if (Test-Path (Join-Path $directory 'target')) { Set-Content -LiteralPath (Join-Path $directory 'target/stop') -Value '' -NoNewline }
    foreach ($source in $sources) { & $adb -s $DeviceSerial shell rm -f "$remoteDirectory/$($source.Name)" | Out-Null }
    foreach ($remote in $remoteApks) { & $adb -s $DeviceSerial shell rm -f $remote | Out-Null }
    if (Test-Path -LiteralPath $fixturePath) { Remove-Item -LiteralPath $fixturePath }
}
