param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [Parameter(Mandatory)][string]$V2Fixture,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$source = (Resolve-Path -LiteralPath $V2Fixture).Path
$capture = Get-Content -LiteralPath (Join-Path $source 'result.json') -Raw | ConvertFrom-Json
if (!$capture.retained_upgrade_fixture -or $capture.passed -ne 1 -or $capture.real_lan -or
    $capture.suite -ne 'seeyuer.sayin.data.TransportInstrumentedTest') {
    throw 'Pass the closed v2 fixture from verify_android_transport.ps1 -TransportOnly -KeepUpgradeFixture.'
}
$withImage = $capture.retained_upgrade_image -eq $true
foreach ($journal in @('receiver.sqlite3-wal', 'receiver.sqlite3-shm')) {
    if (Test-Path -LiteralPath (Join-Path $source $journal)) { throw 'Close the v2 fixture before copying it.' }
}
$apkRoot = Join-Path $root 'android/app/build/outputs/apk'
$metadata = Get-Content -LiteralPath (Join-Path $apkRoot 'imageTest/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'seeyuer.sayin.imagetest') { throw 'Expected the isolated imageTest APK.' }
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$target = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $root 'desktop/target' }
$directory = Join-Path $target ('v3-upgrade-' + [guid]::NewGuid().ToString('N'))
$lease = [System.Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
$locked = $false
$process = $null
$port = $null
try {
    $locked = $lease.WaitOne(0)
    if (!$locked) { throw 'Another SayIn device verification is active.' }
    New-Item -ItemType Directory -Path $directory | Out-Null
    $files = @('host-identity.dpapi', 'receiver-key.dpapi', 'receiver.sqlite3')
    $before = @{}
    foreach ($file in $files) {
        $original = Join-Path $source $file
        $before[$file] = (Get-FileHash -LiteralPath $original).Hash
        Copy-Item -LiteralPath $original -Destination $directory
    }
    $apks = if ($SkipInstall) { @() } else { @('imageTest/app-imageTest.apk', 'androidTest/imageTest/app-imageTest-androidTest.apk') }
    foreach ($apk in $apks) {
        & $adb -s $DeviceSerial install -r -t (Join-Path $apkRoot $apk)
        if ($LASTEXITCODE) { throw 'Isolated APK update failed.' }
    }

    $stderr = Join-Path $directory 'fixture-error.log'
    $process = Start-Process -FilePath (Join-Path $target 'debug/examples/android_fixture.exe') `
        -ArgumentList @("`"$directory`"", '--port=0', '--upgrade-probe') -PassThru -WindowStyle Hidden `
        -RedirectStandardError $stderr
    $metadataPath = Join-Path $directory 'fixture.json'
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (!(Test-Path -LiteralPath $metadataPath)) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw "Upgrade fixture startup failed. See $stderr" }
        Start-Sleep -Milliseconds 50
    }
    $port = [int](Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json).port
    if ($port -le 1024 -or $port -gt 65535) { throw 'Invalid fixture port.' }
    & $adb -s $DeviceSerial reverse "tcp:$port" "tcp:$port"
    if ($LASTEXITCODE) { throw 'ADB reverse failed.' }
    $log = Join-Path $directory 'instrumentation.log'
    & $adb -s $DeviceSerial shell am instrument -w -r -e upgradePort $port -e upgradeImage $withImage.ToString().ToLowerInvariant() `
        -e class seeyuer.sayin.data.UpgradeInstrumentedTest `
        seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner > $log 2>&1
    $output = Get-Content -LiteralPath $log -Raw
    if ($LASTEXITCODE -or $output -notmatch 'OK \(1 test\)' -or $output -match 'INSTRUMENTATION_STATUS_CODE: -[34]') {
        throw "Upgrade instrumentation failed. See $log and $stderr"
    }
    Set-Content -LiteralPath (Join-Path $directory 'stop') -Value '' -NoNewline
    if (!$process.WaitForExit(5000) -or $process.ExitCode -ne 0) { throw "Upgrade fixture failed to close. See $stderr" }

    $calls = Get-Content -LiteralPath (Join-Path $directory 'calls.json') -Raw | ConvertFrom-Json
    if ($calls.fake_input_calls -ne 0 -or $calls.native_input_calls -ne 0) { throw 'An old request was replayed.' }
    foreach ($file in $files) {
        if ((Get-FileHash -LiteralPath (Join-Path $source $file)).Hash -ne $before[$file]) { throw 'The v2 snapshot changed.' }
        if ($file.EndsWith('.dpapi') -and
            (Get-FileHash -LiteralPath (Join-Path $directory $file)).Hash -ne $before[$file]) { throw 'A protected v2 identity changed.' }
    }
    $compareFlags = @(if ($withImage) { '--with-image' })
    & python (Join-Path $PSScriptRoot 'compare_upgrade_ledger.py') `
        (Join-Path $source 'receiver.sqlite3') (Join-Path $directory 'receiver.sqlite3') `
        --output (Join-Path $directory 'ledger-comparison.json') @compareFlags
    if ($LASTEXITCODE) { throw 'The upgraded ledger differs from the original snapshot.' }
    @{
        scope = 'isolated Android in-place upgrade; ADB reverse; loopback WSS; fake input adapter'
        tests = 1
        passed = 1
        fake_input_calls = $calls.fake_input_calls
        native_input_calls = $calls.native_input_calls
        original_pair_authenticated = $true
        draft_and_history_decrypted = $true
        legacy_image_decrypted = $withImage
        source_snapshot_unchanged = $true
        protected_identity_unchanged = $true
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'result.json') -Encoding utf8
    $receiptCount = if ($withImage) { 4 } else { 3 }
    Write-Output "PASS: original pair authenticated and queried $receiptCount v2 receipts. Draft and history decrypted, no input replay. Evidence: $directory"
} finally {
    if ($locked) {
        if ($process -and !$process.HasExited) {
            Set-Content -LiteralPath (Join-Path $directory 'stop') -Value '' -NoNewline
            if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id }
        }
        if ($port -gt 1024 -and $port -le 65535) { & $adb -s $DeviceSerial reverse --remove "tcp:$port" 2>$null | Out-Null }
        $metadataPath = Join-Path $directory 'fixture.json'
        if (Test-Path -LiteralPath $metadataPath) { Remove-Item -LiteralPath $metadataPath }
        $lease.ReleaseMutex()
    }
    $lease.Dispose()
}
