param([string]$DeviceSerial = 'dc39c31d', [switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$package = 'seeyuer.sayin.imagetest'
$tag = [guid]::NewGuid().ToString()
$sampleName = "lan-ordering-$tag.jpg"
$remote = "/sdcard/Android/data/$package/files"
$sampleDirectory = 'image-performance'
$directory = Join-Path $root "desktop/target/lan-ordering-$tag"
$desktop = $null
$instrument = $null
$remoteApks = @()
$lease = [System.Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
if (!$lease.WaitOne(0)) { $lease.Dispose(); throw 'Another SayIn verification is active' }
function Checked([scriptblock]$action) {
    & $action
    if ($LASTEXITCODE -ne 0) { throw "Verification command exited $LASTEXITCODE" }
}
function Await-File([string]$path) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (!(Test-Path -LiteralPath $path)) {
        if ($desktop.HasExited -or [DateTime]::UtcNow -ge $deadline) { throw "Fixture startup failed. Evidence: $directory" }
        Start-Sleep -Milliseconds 100
    }
}
function Read-Receipts {
    $rows = & sqlite3 -readonly -json (Join-Path $directory 'receiver.sqlite3') 'SELECT id,state,request_rev,method,execution_stage,executable_at FROM receipts ORDER BY accept_order'
    if ($LASTEXITCODE -ne 0) { throw 'Could not read isolated receipt ledger' }
    if ($rows) { ($rows -join "`n") | ConvertFrom-Json }
}
try {
    New-Item -ItemType Directory -Path $directory | Out-Null
    if (!$SkipInstall) {
        $apkRoot = Join-Path $root 'android/app/build/outputs/apk'
        $metadata = Get-Content (Join-Path $apkRoot 'imageTest/output-metadata.json') -Raw | ConvertFrom-Json
        if ($metadata.applicationId -ne $package) { throw 'Unexpected isolated package' }
        foreach ($apk in @('imageTest/app-imageTest.apk','androidTest/imageTest/app-imageTest-androidTest.apk')) {
            $temporary = "/data/local/tmp/sayin-$tag-$(Split-Path $apk -Leaf)"
            $remoteApks += $temporary
            Checked { & $adb -s $DeviceSerial push (Join-Path $apkRoot $apk) $temporary }
            $result = & $adb -s $DeviceSerial shell "su -c 'pm install -r -t $temporary'"
            if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') { throw 'Isolated install failed' }
        }
    }
    Checked { & $adb -s $DeviceSerial shell mkdir -p "$remote/$sampleDirectory" }
    Checked { & $adb -s $DeviceSerial push (Join-Path $root 'refer/image-test/001_370.73KB_1.jpg') "$remote/$sampleDirectory/$sampleName" }
    $fixtureExe = Join-Path $root 'desktop/target/release/examples/android_fixture.exe'
    $desktop = Start-Process -FilePath $fixtureExe -ArgumentList ('"' + $directory + '"'), '--images', '--lan', '--port=0', '--image-then-text', '--replay-old-receipt' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory 'desktop-stdout.log') -RedirectStandardError (Join-Path $directory 'desktop-stderr.log')
    $fixtureFile = Join-Path $directory 'fixture.json'
    Await-File $fixtureFile
    $fixture = Get-Content -LiteralPath $fixtureFile -Raw | ConvertFrom-Json
    if ($fixture.address -notmatch '^\d+\.\d+\.\d+\.\d+$' -or $fixture.address -match '^127\.' -or $fixture.code -notmatch '^\d{8}$') { throw 'Unexpected fixture address or invitation' }
    Checked { & $adb -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP }
    Checked { & $adb -s $DeviceSerial shell am start -W -n "$package/androidx.activity.ComponentActivity" }
    $arguments = @('-s',$DeviceSerial,'shell','am','instrument','-w','-r','-e','timeout_msec','120000','-e','lanOrdering','true','-e','fixtureCode',$fixture.code,'-e','fixturePort',"$($fixture.port)",'-e','fixtureAddress',$fixture.address,'-e','imageSample',"$sampleDirectory/$sampleName",'-e','class','seeyuer.sayin.data.LanOrderingInstrumentedTest#queuedTextAfterNativeImageAndOlderReceiptAfterTerminal',"$package.test/androidx.test.runner.AndroidJUnitRunner")
    $instrument = Start-Process -FilePath $adb -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory 'instrumentation.log') -RedirectStandardError (Join-Path $directory 'instrumentation-stderr.log')
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $rows = @(Read-Receipts)
        if ($rows.Count -eq 2 -and $rows[0].state -eq 'executing' -and $rows[1].state -eq 'accepted' -and (Test-Path (Join-Path $directory 'target/observed.json'))) { break }
        if ($desktop.HasExited -or $instrument.HasExited -or $timer.Elapsed.TotalSeconds -gt 50) { throw "Did not observe queued text behind native image. Evidence: $directory" }
        Start-Sleep -Milliseconds 150
    }
    $first = $rows
    $waiting = [Diagnostics.Stopwatch]::StartNew()
    Start-Sleep -Seconds 31
    $last = @(Read-Receipts)
    if ($last.Count -ne 2 -or $last[0].id -ne $first[0].id -or $last[1].id -ne $first[1].id -or $last[0].state -ne 'executing' -or $last[1].state -ne 'accepted' -or $last[1].request_rev -ne $first[1].request_rev -or $null -ne $last[1].executable_at) { throw 'Queued text changed before becoming executable' }
    $waitMs = $waiting.Elapsed.TotalMilliseconds
    $observed = Get-Content (Join-Path $directory 'target/observed.json') -Raw | ConvertFrom-Json
    if ($observed.pictures -ne 1 -or $observed.observed_images -ne 1 -or $observed.observed_texts -ne 0) { throw 'Expected exactly one pasted picture and no text yet' }
    New-Item -ItemType File -Path (Join-Path $directory 'target/finish-image') | Out-Null
    if (!$instrument.WaitForExit(25000)) { throw "Instrumentation timed out. Evidence: $directory" }
    $testLog = Get-Content (Join-Path $directory 'instrumentation.log') -Raw
    if ($instrument.ExitCode -ne 0 -or $testLog -notmatch 'OK \(1 test\)' -or $testLog -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw "Instrumentation failed. Evidence: $directory" }
    $remoteResult = [IO.Path]::GetFileNameWithoutExtension($sampleName) + '-result.json'
    Checked { & $adb -s $DeviceSerial pull "$remote/$remoteResult" (Join-Path $directory 'mobile-result.json') }
    $mobile = Get-Content (Join-Path $directory 'mobile-result.json') -Raw | ConvertFrom-Json
    $terminal = @(Read-Receipts)
    $calls = Get-Content (Join-Path $directory 'calls.json') -Raw | ConvertFrom-Json
    $observed = Get-Content (Join-Path $directory 'target/observed.json') -Raw | ConvertFrom-Json
    if ($terminal.Count -ne 2 -or $terminal[0].id -ne $mobile.image_request -or $terminal[1].id -ne $mobile.text_request -or
        $terminal[0].state -ne 'injected' -or $terminal[1].state -ne 'injected' -or $terminal[0].method -ne 'clipboard_image' -or $terminal[1].method -ne 'unicode' -or
        $calls.native_input_calls -ne 2 -or $calls.fake_input_calls -ne 0 -or $observed.pictures -ne 1 -or $observed.observed_images -ne 1 -or $observed.observed_texts -ne 1) { throw 'Native input count, observation, or receipt mismatch' }
    $replay = "old_receipt_replayed $($mobile.text_request) $($mobile.accepted_revision) $($mobile.terminal_revision)"
    if (@(Select-String -LiteralPath (Join-Path $directory 'desktop-stderr.log') -SimpleMatch $replay).Count -ne 1 -or !$mobile.old_query_processed -or !$mobile.room_unchanged -or !$mobile.history_unchanged) { throw 'Old-receipt delivery or preservation not proven' }
    New-Item -ItemType File -Path (Join-Path $directory 'stop') | Out-Null
    if (!$desktop.WaitForExit(15000) -or $desktop.ExitCode -ne 0) { throw "Fixture did not stop cleanly. Evidence: $directory" }
    @{ real_lan = $true; queued_wait_ms = $waitMs; native_calls = $calls.native_input_calls; phone = $mobile; queued_before = $first; queued_after = $last; desktop_terminal = $terminal } |
        ConvertTo-Json -Depth 6 | Set-Content (Join-Path $directory 'result.json') -Encoding utf8
    Write-Output "PASS M14 and M19. Evidence: $directory"
} finally {
    if ($instrument -and !$instrument.HasExited) { $instrument.Kill(); $instrument.WaitForExit() }
    if ($desktop -and !$desktop.HasExited) {
        New-Item -ItemType File -Path (Join-Path $directory 'target/finish-image') -Force -ErrorAction SilentlyContinue | Out-Null
        New-Item -ItemType File -Path (Join-Path $directory 'stop') -Force | Out-Null
        if (!$desktop.WaitForExit(15000)) { $desktop.Kill(); $desktop.WaitForExit() }
    }
    & $adb -s $DeviceSerial shell rm -f "$remote/$sampleDirectory/$sampleName" "$remote/$([IO.Path]::GetFileNameWithoutExtension($sampleName))-result.json" @remoteApks | Out-Null
    $lease.ReleaseMutex()
    $lease.Dispose()
}
