param([string]$First = 'dc39c31d', [string]$Second = 'f3ba305a', [switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
if ($First -eq $Second) { throw 'Use two distinct phones' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$pkg = 'seeyuer.sayin.imagetest'
$tag = [guid]::NewGuid().ToString()
$directory = Join-Path $root "desktop/target/lan-two-phones-$tag"
$sharedId = [guid]::NewGuid().ToString()
$remote = "/sdcard/Android/data/$pkg/files"
$leases = @()
$desktop = $null
$phones = @(
    @{serial=$First; role='first'; run=$null},
    @{serial=$Second; role='second'; run=$null}
)
function Checked([scriptblock]$action) {
    & $action
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE)" }
}
function Until([scriptblock]$condition) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (!(& $condition)) {
        if ($desktop.HasExited -or ($phones | Where-Object { $_.run -and $_.run.HasExited }) -or $timer.Elapsed.TotalSeconds -gt 32) {
            throw "Expected state not reached. Evidence: $directory"
        }
        Start-Sleep -Milliseconds 100
    }
}
function Read-Rows {
    $json = & sqlite3 -readonly -json (Join-Path $directory 'receiver.sqlite3') 'SELECT pair_id,id,state,reason,method FROM receipts ORDER BY accept_order'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read receiver ledger' }
    if ($json) { ($json -join [Environment]::NewLine) | ConvertFrom-Json }
}
function Count-Connections {
    @(Get-NetTCPConnection -OwningProcess $desktop.Id -LocalPort $fixture.port -State Established -ErrorAction SilentlyContinue |
        Where-Object { $_.RemoteAddress -ne '127.0.0.1' }).Count
}
function Start-Phone($phone, $code) {
    if ($code -notmatch '^\d{8}$') { throw 'Invalid invitation' }
    $serial = $phone.serial
    $role = $phone.role
    $api = [int](& $adb -s $serial shell getprop ro.build.version.sdk)
    if ($api -ge 37) { Checked { & $adb -s $serial shell pm grant $pkg android.permission.ACCESS_LOCAL_NETWORK } }
    Checked { & $adb -s $serial shell input keyevent KEYCODE_WAKEUP }
    $args = @('-s',$serial,'shell','am','instrument','-w','-r','-e','timeout_msec','80000','-e','lanScenario','two-phones','-e','phoneRole',$role,'-e','sharedId',$sharedId,'-e','phaseFile',"$tag-$role.signal",'-e','resultFile',"$tag-$role.json",'-e','fixtureCode',$code,'-e','fixtureAddress',$fixture.address,'-e','fixturePort',"$($fixture.port)",'-e','class','seeyuer.sayin.data.TwoPhonesInstrumentedTest#twoPhones',"$pkg.test/androidx.test.runner.AndroidJUnitRunner")
    $phone.run = Start-Process -FilePath $adb -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory "$role.log") -RedirectStandardError (Join-Path $directory "$role-stderr.log")
    $log = Join-Path $directory "$role.log"
    Until { (Test-Path $log) -and (Get-Content $log -Raw) -match 'INSTRUMENTATION_STATUS_CODE: 1' }
    Checked { & $adb -s $serial shell am start -W -n "$pkg/androidx.activity.ComponentActivity" }
}
function Finish-Phone($phone) {
    $role = $phone.role
    if (!$phone.run.WaitForExit(35000) -or $phone.run.ExitCode -ne 0 -or
        (Get-Content (Join-Path $directory "$role.log") -Raw) -notmatch 'OK \(1 test\)') {
        throw "$role instrumentation failed. Evidence: $directory"
    }
    Checked { & $adb -s $phone.serial pull "$remote/$tag-$role.json" (Join-Path $directory "$role-result.json") }
    Get-Content (Join-Path $directory "$role-result.json") -Raw | ConvertFrom-Json
}
try {
    foreach ($serial in @($First,$Second) | Sort-Object) {
        $lease = [Threading.Mutex]::new($false, "Local\SayIn-Android-$serial")
        if (!$lease.WaitOne(0)) { $lease.Dispose(); throw "Device $serial is in use" }
        $leases += $lease
    }
    if (!$SkipInstall) {
        foreach ($phone in $phones) {
            $apk = Join-Path $root 'android/app/build/outputs/apk/androidTest/imageTest/app-imageTest-androidTest.apk'
            $installed = & $adb -s $phone.serial install -r -t $apk 2>&1
            if ($LASTEXITCODE -eq 0 -and $installed -contains 'Success') { continue }
            $path = "/data/local/tmp/sayin-two-$tag.apk"
            try {
                Checked { & $adb -s $phone.serial push $apk $path }
                $installed = & $adb -s $phone.serial shell "su -c 'pm install -r -t $path'"
                if ($LASTEXITCODE -ne 0 -or $installed -notcontains 'Success') { throw "Test install failed on $($phone.serial)" }
            } finally { & $adb -s $phone.serial shell rm -f $path | Out-Null }
        }
    }
    New-Item -ItemType Directory -Path $directory | Out-Null
    $desktop = Start-Process -FilePath (Join-Path $root 'desktop/target/release/examples/android_fixture.exe') -ArgumentList ('"' + $directory + '"'),'--lan','--port=0','--images','--two-phones','--native-text-target' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory 'desktop.log') -RedirectStandardError (Join-Path $directory 'desktop-stderr.log')
    Until { Test-Path (Join-Path $directory 'fixture.json') }
    $fixture = Get-Content (Join-Path $directory 'fixture.json') -Raw | ConvertFrom-Json
    if ($fixture.address -notmatch '^\d+\.\d+\.\d+\.\d+$' -or $fixture.address -match '^127\.') { throw 'Expected physical LAN IPv4' }
    Start-Phone $phones[0] $fixture.code
    Until { @(Read-Rows | Where-Object state -eq 'held').Count -eq 1 -and (Test-Path (Join-Path $directory 'second-code')) }
    Start-Phone $phones[1] (Get-Content (Join-Path $directory 'second-code') -Raw)
    Until { $r = @(Read-Rows); $r.Count -eq 2 -and ($r.state -join ',') -eq 'held,injected' }
    $overlap = @(Read-Rows)
    $concurrentConnections = Count-Connections
    if ($overlap[0].id -ne $sharedId -or $overlap[1].id -ne $sharedId -or $overlap[0].pair_id -eq $overlap[1].pair_id -or
        $overlap[0].reason -ne 'receiver_focused' -or $concurrentConnections -ne 2) { throw 'Pair isolation or two live connections not observed' }
    Checked { & $adb -s $First shell run-as $pkg touch "files/$tag-first.signal" }
    $firstReport = @(Finish-Phone $phones[0])[-1]
    $disconnectTimer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $remainingConnections = Count-Connections
        if ($remainingConnections -eq 1) { break }
        if ($desktop.HasExited -or $phones[1].run.HasExited -or $disconnectTimer.Elapsed.TotalSeconds -gt 8) {
            throw 'Expected the first phone to disconnect while the second stayed connected'
        }
        Start-Sleep -Milliseconds 100
    } while ($true)
    Checked { & $adb -s $Second shell run-as $pkg touch "files/$tag-second.signal" }
    $secondReport = @(Finish-Phone $phones[1])[-1]
    $rows = @(Read-Rows)
    $native = Get-Content (Join-Path $directory 'target/observed.json') -Raw | ConvertFrom-Json
    $calls = Get-Content (Join-Path $directory 'calls.json') -Raw | ConvertFrom-Json
    if ($rows.Count -ne 3 -or ($rows.state -join ',') -ne 'cancelled,injected,injected' -or $rows[2].pair_id -ne $rows[1].pair_id -or
        $rows[0].pair_id -ne $firstReport.pair -or $rows[1].pair_id -ne $secondReport.pair -or
        !$firstReport.query_completed -or !$secondReport.query_completed -or
        $native.observed_texts -ne 2 -or $calls.native_input_calls -ne 2 -or $calls.fake_input_calls -ne 0) { throw 'Native delivery or pair isolation mismatch' }
    New-Item -ItemType File -Path (Join-Path $directory 'stop') | Out-Null
    if (!$desktop.WaitForExit(12000) -or $desktop.ExitCode -ne 0) { throw 'Receiver failed to shut down' }
    @{real_lan=$true; first=$firstReport; second=$secondReport; overlap=$overlap; terminal=$rows; native_inputs=$calls.native_input_calls;
        concurrent_connections=$concurrentConnections; connections_after_first_exit=$remainingConnections} |
        ConvertTo-Json -Depth 6 | Set-Content (Join-Path $directory 'result.json') -Encoding utf8
    Write-Output "PASS two physical phones. Evidence: $directory"
} finally {
    foreach ($phone in $phones) {
        if ($phone.run -and !$phone.run.HasExited) { & $adb -s $phone.serial shell am force-stop $pkg | Out-Null; if (!$phone.run.WaitForExit(3000)) { $phone.run.Kill(); $phone.run.WaitForExit() } }
        & $adb -s $phone.serial shell run-as $pkg rm -f "files/$tag-$($phone.role).signal" | Out-Null
        & $adb -s $phone.serial shell rm -f "$remote/$tag-$($phone.role).json" | Out-Null
    }
    if ($desktop -and !$desktop.HasExited) {
        New-Item -ItemType File -Path (Join-Path $directory 'stop') -Force | Out-Null
        if (!$desktop.WaitForExit(12000)) { $desktop.Kill(); $desktop.WaitForExit() }
    }
    foreach ($name in 'fixture.json','second-code') {
        $file = Join-Path $directory $name
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
    foreach ($lease in $leases) { $lease.ReleaseMutex(); $lease.Dispose() }
}
