param([string]$DeviceSerial = 'dc39c31d', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$project54 = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb54 = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
function Checked54([scriptblock]$Action) { & $Action; if ($LASTEXITCODE -ne 0) { throw 'Command failed' } }
if (!$SkipBuild) {
    Checked54 { & "$PSScriptRoot/gradle.ps1" '-Psayin.instrumentationBuildType=imageTest' :app:assembleImageTest :app:assembleImageTestAndroidTest }
    Push-Location "$project54/desktop"
    try { Checked54 { cargo build -p voicelink-transport --example android_fixture --locked } } finally { Pop-Location }
}
$run54 = Join-Path $project54 "desktop/target/foreground-display-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $run54 | Out-Null
$lease54 = [System.Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
$acquired54 = $false
$fixture54 = $null
$helpers54 = @()
$instrument54 = $null
$port54 = $null
try {
    $acquired54 = $lease54.WaitOne(0)
    if (!$acquired54) { throw 'Device test already active' }
    $metadata54 = Get-Content "$project54/android/app/build/outputs/apk/imageTest/output-metadata.json" -Raw | ConvertFrom-Json
    if ($metadata54.applicationId -ne 'seeyuer.sayin.imagetest') { throw 'Isolated APK required' }
    Checked54 { & $adb54 -s $DeviceSerial install -r -t "$project54/android/app/build/outputs/apk/imageTest/app-imageTest.apk" }
    Checked54 { & $adb54 -s $DeviceSerial install -r -t "$project54/android/app/build/outputs/apk/androidTest/imageTest/app-imageTest-androidTest.apk" }
    # Disposable native windows belong to two queryable Windows executables with
    # different version-resource names. Never focus, type into or close user apps.
    $helperCode54 = @'
param([string]$Output)
Add-Type -AssemblyName System.Windows.Forms
$form54 = [System.Windows.Forms.Form]::new()
$form54.Text = 'SayIn foreground display verification'
$form54.Width = 420; $form54.Height = 160
$label54 = [System.Windows.Forms.Label]::new()
$label54.Text = 'Disposable foreground test window. No input is sent.'
$label54.Dock = 'Fill'; $form54.Controls.Add($label54)
$timer54 = [System.Windows.Forms.Timer]::new(); $timer54.Interval = 100
$timer54.Add_Tick({ if (Test-Path -LiteralPath "$Output.stop") { $form54.Close() } })
$form54.Add_Shown({
    [IO.File]::WriteAllText($Output, $form54.Handle.ToInt64().ToString())
    $timer54.Start()
})
[System.Windows.Forms.Application]::Run($form54)
'@
    Set-Content -LiteralPath "$run54/window.ps1" -Value $helperCode54 -Encoding utf8
    $binaries54 = @("$env:WINDIR/System32/WindowsPowerShell/v1.0/powershell.exe", (Get-Command pwsh).Source)
    $windows54 = @()
    for ($index54 = 0; $index54 -lt 2; $index54++) {
        $file54 = "$run54/window-$index54"
        $helpers54 += Start-Process -FilePath $binaries54[$index54] -ArgumentList @('-NoProfile','-STA','-ExecutionPolicy','Bypass','-File',"`"$run54/window.ps1`"",'-Output',"`"$file54`"") -WindowStyle Hidden -PassThru
        $until54 = [DateTime]::UtcNow.AddSeconds(12)
        while (!(Test-Path -LiteralPath $file54)) {
            if ([DateTime]::UtcNow -gt $until54) { throw 'Disposable window did not start' }
            Start-Sleep -Milliseconds 50
        }
        $version54 = [Diagnostics.FileVersionInfo]::GetVersionInfo($binaries54[$index54])
        $name54 = $version54.FileDescription
        if ($name54.StartsWith($version54.ProductName, [StringComparison]::OrdinalIgnoreCase)) { $name54 = $version54.ProductName }
        $windows54 += @{ hwnd = [long](Get-Content -LiteralPath $file54 -Raw); name = $name54 }
    }
    if ($windows54[0].name -eq $windows54[1].name) { throw 'Two distinct application metadata names required' }
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class SayInForegroundProbe54 {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, IntPtr pid);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint first, uint second, bool attach);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr window, int command);
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr window);
    public static bool Focus(IntPtr window) {
        uint own = GetCurrentThreadId(), foreground = GetWindowThreadProcessId(GetForegroundWindow(), IntPtr.Zero);
        bool attached = own != foreground && AttachThreadInput(own, foreground, true);
        try {
            ShowWindow(window, 9); BringWindowToTop(window); SetForegroundWindow(window);
            // Cross-thread activation can be queued. Observe the native result
            // rather than mistaking an immediate read for a rejected request.
            for (int attempt = 0; attempt < 30; attempt++) {
                if (GetForegroundWindow() == window) return true;
                System.Threading.Thread.Sleep(10);
            }
            return false;
        }
        finally { if (attached) AttachThreadInput(own, foreground, false); }
    }
}
'@
    if (![SayInForegroundProbe54]::Focus([IntPtr]$windows54[0].hwnd)) { throw 'Could not focus disposable window' }
    $fixture54 = Start-Process -FilePath "$project54/desktop/target/debug/examples/android_fixture.exe" -ArgumentList @("`"$run54/fixture`"",'--port=0') -WindowStyle Hidden -PassThru
    $until54 = [DateTime]::UtcNow.AddSeconds(10)
    while (!(Test-Path -LiteralPath "$run54/fixture/fixture.json")) {
        if ([DateTime]::UtcNow -gt $until54) { throw 'Fixture did not start' }
        Start-Sleep -Milliseconds 50
    }
    $config54 = Get-Content "$run54/fixture/fixture.json" -Raw | ConvertFrom-Json
    $port54 = $config54.port
    Checked54 { & $adb54 -s $DeviceSerial reverse "tcp:$port54" "tcp:$port54" }
    # Estimate phone/PC wall-clock offset; report half-RTT uncertainty as part of evidence.
    $clocks54 = @()
    for ($index54 = 0; $index54 -lt 7; $index54++) {
        $before54 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $phone54 = [long](& $adb54 -s $DeviceSerial shell date '+%s%3N')
        $after54 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $clocks54 += @{ offset_ms = $phone54 - ($before54 + $after54) / 2; half_rtt_ms = ($after54 - $before54) / 2 }
    }
    $clock54 = $clocks54 | Sort-Object half_rtt_ms | Select-Object -First 1
    $args54 = @('-s',$DeviceSerial,'shell','am','instrument','-w','-r','-e','class','seeyuer.sayin.ui.ForegroundLiveInstrumentedTest','-e','foregroundLive','true','-e','fixturePort',$port54,'-e','fixtureCode',$config54.code,'seeyuer.sayin.imagetest.test/androidx.test.runner.AndroidJUnitRunner')
    $instrument54 = Start-Process -FilePath $adb54 -ArgumentList $args54 -RedirectStandardOutput "$run54/instrumentation.log" -RedirectStandardError "$run54/adb-error.log" -WindowStyle Hidden -PassThru
    $until54 = [DateTime]::UtcNow.AddSeconds(40)
    do {
        $ready54 = & $adb54 -s $DeviceSerial shell run-as seeyuer.sayin.imagetest cat files/ui-verification/54-foreground-ready 2>$null
        if ([DateTime]::UtcNow -gt $until54 -or $instrument54.HasExited) { throw 'Phone did not connect' }
        Start-Sleep -Milliseconds 100
    } while ($ready54 -ne 'ready')
    # Fixed foreground for 18s measures idle heartbeats without name changes.
    Start-Sleep -Seconds 20
    $switches54 = @()
    for ($index54 = 0; $index54 -lt 20; $index54++) {
        $window54 = $windows54[($index54 + 1) % 2]
        $time54 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        if (![SayInForegroundProbe54]::Focus([IntPtr]$window54.hwnd)) { throw 'Foreground switch rejected' }
        if ([SayInForegroundProbe54]::GetForegroundWindow().ToInt64() -ne $window54.hwnd) { throw 'Foreground switch not observed' }
        $switches54 += @{ name = $window54.name; switched_at_ms = $time54 }
        Start-Sleep -Milliseconds 1500
    }
    $switches54 | ConvertTo-Json -Depth 4 | Set-Content "$run54/switches.json" -Encoding utf8
    if (!$instrument54.WaitForExit(30000)) { throw 'Phone instrumentation timeout' }
    $result54 = Get-Content "$run54/instrumentation.log" -Raw
    if ($result54 -notmatch 'OK \(1 test\)' -or $result54 -match 'FAILURES!!!|Process crashed') { throw 'Foreground test failed' }
    $observed54 = (& $adb54 -s $DeviceSerial shell run-as seeyuer.sayin.imagetest cat files/ui-verification/54-foreground-live.json) | ConvertFrom-Json
    $idle54 = (& $adb54 -s $DeviceSerial shell run-as seeyuer.sayin.imagetest cat files/ui-verification/54-foreground-idle.json) | ConvertFrom-Json
    $observed54 | ConvertTo-Json -Depth 6 | Set-Content "$run54/observations.json" -Encoding utf8
    @{clock=$clock54;idle=$idle54} | ConvertTo-Json -Depth 4 | Set-Content "$run54/clock-idle.json" -Encoding utf8
    $latencies54 = @()
    foreach ($switch54 in $switches54) {
        $phoneTime54 = $switch54.switched_at_ms + $clock54.offset_ms
        $observation54 = $observed54 | Where-Object { $_.name -eq $switch54.name -and $_.displayed_at_ms -ge $phoneTime54 } | Sort-Object displayed_at_ms | Select-Object -First 1
        if (!$observation54 -or $observation54.displayed_at_ms - $phoneTime54 -gt 1400) { throw 'Missing or late foreground display' }
        if (!$observation54.icon_present -or $null -eq $observation54.icon_pixels_hash) { throw 'Native application icon did not reach phone Compose' }
        $latencies54 += [math]::Round($observation54.displayed_at_ms - $phoneTime54, 1)
    }
    $sorted54 = @($latencies54 | Sort-Object)
    $calls54 = Get-Content "$run54/fixture/calls.json" -Raw | ConvertFrom-Json
    if ($calls54.fake_input_calls -ne 0) { throw 'Display must not submit input' }
    $iconHashes54 = @($observed54 | Where-Object icon_present | Select-Object -ExpandProperty icon_pixels_hash -Unique)
    if ($iconHashes54.Count -lt 2) { throw 'Two native applications must display different real icons' }
    $evidence54 = @{ scope='Windows native foreground -> pinned TLS via adb reverse -> Redmi K40 Compose frame; isolated identities; no input'; samples=20; latencies_ms=$latencies54; p50_ms=$sorted54[9]; p95_ms=$sorted54[18]; clock=$clock54; idle=$idle54; switches=$switches54; observations=$observed54 }
    $evidence54 | ConvertTo-Json -Depth 8 | Set-Content "$run54/result.json" -Encoding utf8
    Write-Output "PASS: foreground display; P50=$($sorted54[9])ms P95=$($sorted54[18])ms. Evidence: $run54/result.json"
} finally {
    for ($index54 = 0; $index54 -lt $helpers54.Count; $index54++) { Set-Content -LiteralPath "$run54/window-$index54.stop" -Value '' }
    foreach ($helper54 in $helpers54) { if (!$helper54.WaitForExit(5000)) { Stop-Process -Id $helper54.Id } }
    if ($fixture54 -and !$fixture54.HasExited) { Set-Content "$run54/fixture/stop" ''; if (!$fixture54.WaitForExit(5000)) { Stop-Process -Id $fixture54.Id } }
    if ($instrument54 -and !$instrument54.HasExited) { & $adb54 -s $DeviceSerial shell am force-stop seeyuer.sayin.imagetest; Stop-Process -Id $instrument54.Id }
    if ($port54) { & $adb54 -s $DeviceSerial reverse --remove "tcp:$port54" | Out-Null }
    if ($acquired54) { $lease54.ReleaseMutex() }
    $lease54.Dispose()
}
