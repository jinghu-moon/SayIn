param([string]$DeviceSerial = 'dc39c31d', [switch]$Lan, [switch]$Revoked, [switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$targetDirectory = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $root 'desktop/target' }
$tag = [guid]::NewGuid().ToString()
$directory = Join-Path $targetDirectory ('discovery-fixture-' + $tag)
$process = $null
$ports = @()
$remoteApks = @()
function Checked([scriptblock]$command) { & $command; if ($LASTEXITCODE -ne 0) { throw 'Verification command failed' } }
try {
    $metadata = Get-Content (Join-Path $root 'android/app/build/outputs/apk/imageTest/output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'seeyuer.sayin.imagetest') { throw 'Discovery verification requires the isolated imageTest package' }
    if (!$SkipInstall) {
        # Build first; never mutate an APK while adb is copying it.
        foreach ($item in @(@('imageTest/app-imageTest.apk','app'), @('androidTest/imageTest/app-imageTest-androidTest.apk','test'))) {
            $remote = "/data/local/tmp/sayin-discovery-$tag-$($item[1]).apk"; $remoteApks += $remote
            Checked { & $adb -s $DeviceSerial push (Join-Path $root ('android/app/build/outputs/apk/' + $item[0])) $remote }
            $result = & $adb -s $DeviceSerial shell "su -c 'pm install -r -t $remote'"
            if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') { throw 'Existing authorized root install failed' }
        }
    }
    $mode = if ($Lan) { 'lan' } else { 'loopback' }
    $scenario = if ($Revoked) { 'revoked' } else { 'recover' }
    New-Item -ItemType Directory -Path $directory | Out-Null
    $process = Start-Process -FilePath (Join-Path $targetDirectory 'debug/examples/discovery_fixture.exe') -ArgumentList @("`"$directory`"",$mode,$scenario) -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $directory 'desktop-stderr.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while (!(Test-Path -LiteralPath (Join-Path $directory 'fixture.json'))) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Discovery fixture did not start' }
        Start-Sleep -Milliseconds 100
    }
    $fixture = Get-Content -LiteralPath (Join-Path $directory 'fixture.json') -Raw | ConvertFrom-Json
    if (!$Lan) {
        $ports = @($fixture.port,$fixture.new_port,$fixture.wrong_port)
        foreach ($port in $ports) { Checked { & $adb -s $DeviceSerial reverse "tcp:$port" "tcp:$port" } }
    }
    # LAN intentionally has no adb reverse, tunnel, firewall or network changes.
    $arguments = @('-s',$DeviceSerial,'shell','am','instrument','-w','-r',
        '-e','class','seeyuer.sayin.data.DiscoveryInstrumentedTest',
        '-e','discoveryAddress',$fixture.address,'-e','discoveryPort',"$($fixture.port)",
        '-e','discoveryCode',$fixture.code,'-e','discoveryNewAddress',$fixture.new_address,
        '-e','discoveryNewPort',"$($fixture.new_port)",'-e','discoveryWrongPort',"$($fixture.wrong_port)",
        '-e','discoveryLan',([string][bool]$Lan).ToLowerInvariant(),'-e','discoveryRevoked',([string][bool]$Revoked).ToLowerInvariant(),
        'seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner')
    $log = Join-Path $directory 'instrumentation.log'
    & $adb @arguments > $log 2>&1
    $output = Get-Content -LiteralPath $log -Raw
    if ($LASTEXITCODE -ne 0 -or $output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[234]') { throw "Discovery instrumentation failed; see $log" }
    Start-Sleep -Milliseconds 150
    $result = Get-Content -LiteralPath (Join-Path $directory 'result.json') -Raw | ConvertFrom-Json
    if (!$result.migrated -or $result.fake_input_calls -ne 1 -or !$result.paused) { throw 'Migration/no-replay/paused fixture assertions failed' }
    @{ mode=$mode; revoked=[bool]$Revoked; instrumented_tests=1; migrated=$result.migrated; fake_input_calls=$result.fake_input_calls; paused=$result.paused; real_lan=[bool]$Lan } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'verification.json') -Encoding utf8
    Write-Output "Discovery verification passed: $mode/$scenario; one fake input, no replay, pause retained."
} finally {
    if ($process -and !$process.HasExited) {
        Set-Content -LiteralPath (Join-Path $directory 'stop') -Value '' -NoNewline
        if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id }
    }
    foreach ($port in $ports) { & $adb -s $DeviceSerial reverse --remove "tcp:$port" 2>$null | Out-Null }
    if (Test-Path -LiteralPath (Join-Path $directory 'fixture.json')) { Remove-Item -LiteralPath (Join-Path $directory 'fixture.json') }
    foreach ($remote in $remoteApks) { & $adb -s $DeviceSerial shell rm -f $remote | Out-Null }
}
