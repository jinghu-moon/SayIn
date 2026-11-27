param([string]$DeviceSerial = 'dc39c31d', [switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$package = 'seeyuer.sayin.imagetest'
$fixtureExe = Join-Path $root 'desktop/target/release/examples/android_fixture.exe'
$lease = [Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
if (!$lease.WaitOne(0)) { $lease.Dispose(); throw 'Another SayIn verification is active' }

function Checked([scriptblock]$action) {
    & $action
    if ($LASTEXITCODE -ne 0) { throw "Verification command exited $LASTEXITCODE" }
}
function Rows($directory) {
    $output = & sqlite3 -readonly -json (Join-Path $directory 'receiver.sqlite3') 'SELECT id,state,accept_order,request_rev,reason FROM receipts ORDER BY accept_order'
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the fixture ledger' }
    if ($output) { ($output -join [Environment]::NewLine) | ConvertFrom-Json }
}
function Wait-WhileRunning($desktop, $instrument, $condition) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (& $condition) { return }
        if ($desktop.HasExited -or ($instrument -and $instrument.HasExited) -or $timer.Elapsed.TotalSeconds -gt 20) {
            throw "Fixture did not reach expected state. Evidence: $directory"
        }
        Start-Sleep -Milliseconds 75
    } while ($true)
}
function Signal($directory, $name) {
    New-Item -ItemType File -Path (Join-Path $directory $name) -Force | Out-Null
}
function Invoke-Case($scenario, $method) {
    $tag = [guid]::NewGuid().ToString()
    $directory = Join-Path $root "desktop/target/lan-$scenario-$tag"
    $resultFile = "lan-$scenario-$tag.json"
    $remoteResult = "/sdcard/Android/data/$package/files/$resultFile"
    $desktop = $null
    $instrument = $null
    try {
        New-Item -ItemType Directory -Path $directory | Out-Null
        $desktop = Start-Process -FilePath $fixtureExe -ArgumentList ('"' + $directory + '"'), '--lan', '--port=0', "--$scenario" -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $directory 'desktop-stderr.log') -RedirectStandardOutput (Join-Path $directory 'desktop-stdout.log')
        $fixtureFile = Join-Path $directory 'fixture.json'
        Wait-WhileRunning $desktop $null { Test-Path -LiteralPath $fixtureFile }
        $fixture = Get-Content -LiteralPath $fixtureFile -Raw | ConvertFrom-Json
        if ($fixture.address -notmatch '^\d+\.\d+\.\d+\.\d+$' -or $fixture.address -match '^127\.' -or $fixture.code -notmatch '^\d{8}$') { throw 'Invalid LAN fixture' }
        Checked { & $adb -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP }
        $class = "seeyuer.sayin.data.LanReceiptInstrumentedTest#$method"
        $args = @('-s',$DeviceSerial,'shell','am','instrument','-w','-r','-e','timeout_msec','80000','-e','lanScenario',$scenario,'-e','fixtureCode',$fixture.code,'-e','fixturePort',"$($fixture.port)",'-e','fixtureAddress',$fixture.address,'-e','resultFile',$resultFile,'-e','class',$class,"$package.test/androidx.test.runner.AndroidJUnitRunner")
        $instrument = Start-Process -FilePath $adb -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory 'instrumentation.log') -RedirectStandardError (Join-Path $directory 'instrumentation-stderr.log')
        $instrumentLog = Join-Path $directory 'instrumentation.log'
        Wait-WhileRunning $desktop $instrument { (Get-Content $instrumentLog -Raw) -match 'INSTRUMENTATION_STATUS_CODE: 1' }
        Checked { & $adb -s $DeviceSerial shell am start -W -n "$package/androidx.activity.ComponentActivity" }
        if ($scenario -eq 'paused-queue') {
            Wait-WhileRunning $desktop $instrument { @(Rows $directory | Where-Object state -eq 'accepted').Count -eq 3 }
            $accepted = @(Rows $directory)
            Signal $directory 'pause-queue'
            Wait-WhileRunning $desktop $instrument { @(Rows $directory | Where-Object state -eq 'held').Count -eq 3 }
            $held = @(Rows $directory)
            if (($accepted.id -join ',') -ne ($held.id -join ',') -or @($held | Where-Object reason -ne 'paused').Count) { throw 'Pause changed the identity or order of accepted requests' }
            Signal $directory 'resume-queue'
            Wait-WhileRunning $desktop $instrument {
                $rows = @(Rows $directory)
                $rows.Count -eq 3 -and ($rows.state -join ',') -eq 'held,accepted,held'
            }
            $blocked = @(Rows $directory)
            if (Select-String -LiteralPath (Join-Path $directory 'desktop-stderr.log') -Pattern '^paused_queue_input ') { throw 'Input ran before the first request was released' }
            Wait-WhileRunning $desktop $instrument { @(Rows $directory | Where-Object state -eq 'injected').Count -eq 2 }
            Signal $directory 'expire-queue'
        }
        if (!$instrument.WaitForExit(25000)) { throw "Instrumentation timed out. Evidence: $directory" }
        $testLog = Get-Content (Join-Path $directory 'instrumentation.log') -Raw
        if ($instrument.ExitCode -ne 0 -or $testLog -notmatch 'OK \(1 test\)' -or $testLog -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw "Instrumentation failed. Evidence: $directory" }
        Checked { & $adb -s $DeviceSerial pull $remoteResult (Join-Path $directory 'mobile-result.json') }
        $mobile = Get-Content (Join-Path $directory 'mobile-result.json') -Raw | ConvertFrom-Json
        $terminal = @(Rows $directory)
        $logPath = Join-Path $directory 'desktop-stderr.log'
        if ($scenario -eq 'receipt-budget') {
            $wire = @(Select-String -LiteralPath $logPath -Pattern '^receipt_budget ([a-f0-9-]+) (\d+) (true|false)$' | ForEach-Object {
                $match = $_.Matches[0]
                @{ id = $match.Groups[1].Value; bytes = [int]$match.Groups[2].Value; icon = $match.Groups[3].Value -eq 'true' }
            })
            $exact = @($wire | Where-Object id -eq $mobile.receipts[0].id)
            $over = @($wire | Where-Object id -eq $mobile.receipts[1].id)
            if ($wire.Count -ne 4 -or $exact.Count -ne 2 -or $over.Count -ne 2 -or
                @($exact | Where-Object { $_.bytes -ne 8192 -or !$_.icon }).Count -or
                @($over | Where-Object { $_.bytes -ge 8192 -or $_.icon }).Count -or
                $over[0].bytes -ne $over[1].bytes -or !$mobile.receipts[0].stored_icon -or
                ($mobile.receipts[1].reason_bytes - $mobile.receipts[0].reason_bytes) -ne 1 -or
                ($terminal.id -join ',') -ne ($mobile.receipts.id -join ',') -or ($terminal.state -join ',') -ne 'failed,failed') {
                throw "Receipt budget evidence mismatch: $directory"
            }
            $details = @{ wire = $wire }
        } else {
            $inputIds = @(Select-String -LiteralPath $logPath -Pattern '^paused_queue_input (.+)$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
            if (($terminal.id -join ',') -ne ($mobile.ids -join ',') -or ($terminal.state -join ',') -ne 'injected,injected,expired' -or $terminal[2].reason -ne 'hold_expired' -or
                ($inputIds -join ',') -ne ($mobile.ids[0..1] -join ',')) { throw "Queue order or expiry mismatch: $directory" }
            $details = @{ accepted = $accepted; held = $held; blocked = $blocked; inputs = $inputIds }
        }
        Signal $directory 'stop'
        if (!$desktop.WaitForExit(10000) -or $desktop.ExitCode -ne 0) { throw "Fixture shutdown failed: $directory" }
        @{ scenario = $scenario; real_lan = $true; mobile = $mobile; terminal = $terminal; details = $details } |
            ConvertTo-Json -Depth 8 | Set-Content (Join-Path $directory 'result.json') -Encoding utf8
        Write-Output "PASS $scenario. Evidence: $directory"
    } finally {
        if ($instrument -and !$instrument.HasExited) { $instrument.Kill(); $instrument.WaitForExit() }
        if ($desktop -and !$desktop.HasExited) {
            Signal $directory 'stop'
            if (!$desktop.WaitForExit(10000)) { $desktop.Kill(); $desktop.WaitForExit() }
        }
        & $adb -s $DeviceSerial shell rm -f $remoteResult | Out-Null
    }
}
try {
    if (!$SkipInstall) {
        $apk = Join-Path $root 'android/app/build/outputs/apk/androidTest/imageTest/app-imageTest-androidTest.apk'
        $remoteApk = "/data/local/tmp/sayin-lan-checks-$([guid]::NewGuid()).apk"
        try {
            Checked { & $adb -s $DeviceSerial push $apk $remoteApk }
            $install = & $adb -s $DeviceSerial shell "su -c 'pm install -r -t $remoteApk'"
            if ($LASTEXITCODE -ne 0 -or $install -notcontains 'Success') { throw 'Isolated test APK installation failed' }
        } finally { & $adb -s $DeviceSerial shell rm -f $remoteApk | Out-Null }
    }
    Invoke-Case 'receipt-budget' 'receiptBudget'
    Invoke-Case 'paused-queue' 'pausedQueue'
} finally {
    $lease.ReleaseMutex()
    $lease.Dispose()
}
