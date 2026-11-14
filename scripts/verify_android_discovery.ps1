param([string]$DeviceSerial = 'dc39c31d', [switch]$Lan, [switch]$Revoked, [switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$directory = Join-Path $root ('desktop/target/discovery-fixture-' + [guid]::NewGuid())
$process = $null
$ports = @()
function Checked([scriptblock]$command) { & $command; if ($LASTEXITCODE -ne 0) { throw 'Verification command failed' } }
try {
    if (!$SkipInstall) {
        # Build first; never mutate an APK while adb is copying it.
        foreach ($item in @(@('debug/app-debug.apk','/data/local/tmp/sayin-discovery.apk'), @('androidTest/debug/app-debug-androidTest.apk','/data/local/tmp/sayin-discovery-test.apk'))) {
            Checked { & $adb -s $DeviceSerial push (Join-Path $root ('android/app/build/outputs/apk/' + $item[0])) $item[1] }
            $result = & $adb -s $DeviceSerial shell "su -c 'pm install -r -t $($item[1])'"
            if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') { throw 'Existing authorized root install failed' }
        }
    }
    $mode = if ($Lan) { 'lan' } else { 'loopback' }
    $scenario = if ($Revoked) { 'revoked' } else { 'recover' }
    $process = Start-Process -FilePath (Join-Path $root 'desktop/target/debug/examples/discovery_fixture.exe') -ArgumentList @("`"$directory`"",$mode,$scenario) -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $root 'android/discovery-fixture-error.log')
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
        'seeyuer.sayin.test/androidx.test.runner.AndroidJUnitRunner')
    $log = Join-Path $root "android/discovery-$mode-$scenario.log"
    & $adb @arguments > $log 2>&1
    $output = Get-Content -LiteralPath $log -Raw
    if ($LASTEXITCODE -ne 0 -or $output -notmatch 'OK \(1 test\)' -or $output -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw "Discovery instrumentation failed; see $log" }
    Start-Sleep -Milliseconds 150
    $result = Get-Content -LiteralPath (Join-Path $directory 'result.json') -Raw | ConvertFrom-Json
    if (!$result.migrated -or $result.fake_input_calls -ne 1 -or !$result.paused) { throw 'Migration/no-replay/paused fixture assertions failed' }
    @{ mode=$mode; revoked=[bool]$Revoked; instrumented_tests=1; migrated=$result.migrated; fake_input_calls=$result.fake_input_calls; paused=$result.paused; real_lan=[bool]$Lan } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "android/discovery-$mode-$scenario-result.json") -Encoding utf8
    Write-Output "Discovery verification passed: $mode/$scenario; one fake input, no replay, pause retained."
} finally {
    if ($process -and !$process.HasExited) {
        Set-Content -LiteralPath (Join-Path $directory 'stop') -Value '' -NoNewline
        if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id }
    }
    foreach ($port in $ports) { & $adb -s $DeviceSerial reverse --remove "tcp:$port" 2>$null | Out-Null }
    if (Test-Path -LiteralPath (Join-Path $directory 'fixture.json')) { Remove-Item -LiteralPath (Join-Path $directory 'fixture.json') }
    & $adb -s $DeviceSerial shell rm -f /data/local/tmp/sayin-discovery.apk /data/local/tmp/sayin-discovery-test.apk | Out-Null
}
