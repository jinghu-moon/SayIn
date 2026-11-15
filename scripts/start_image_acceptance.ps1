param([string]$DeviceSerial = 'dc39c31d')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $root 'desktop/target' }
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$metadata = Get-Content (Join-Path $root 'android/app/build/outputs/apk/imageTest/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'seeyuer.sayin.imagetest') { throw 'An isolated imageTest build is required' }
& $adb -s $DeviceSerial shell pm path seeyuer.sayin.imagetest | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Install the isolated imageTest build first; never install over the daily app' }
$directory = Join-Path $target ('image-manual-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $directory | Out-Null
$process = $null
try {
    $process = Start-Process -FilePath (Join-Path $target 'debug/examples/android_fixture.exe') -ArgumentList @("`"$directory`"",'--images','--lan','--port=0','--benchmark','--manual-native') -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $directory 'desktop-stderr.log') -RedirectStandardOutput (Join-Path $directory 'desktop-stdout.log')
    $path = Join-Path $directory 'fixture.json'
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while (!(Test-Path -LiteralPath $path)) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Manual fixture could not start' }
        Start-Sleep -Milliseconds 50
    }
    $fixture = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($fixture.code -notmatch '^\d{8}$' -or $fixture.port -lt 1025) { throw 'Invalid isolated fixture metadata' }
    # Human selects a harmless image and explicitly sends it. No automation
    # drives a target application or submits generated/paid/cloud requests.
    & $adb -s $DeviceSerial shell am start -n seeyuer.sayin.imagetest/seeyuer.sayin.MainActivity | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not open the isolated phone app' }
    [pscustomobject]@{ address=$fixture.address; port=$fixture.port; code=$fixture.code; pairing_code_valid_for_seconds=120; service_lifetime_seconds=600; process_id=$process.Id; evidence_directory=$directory; stop_file=(Join-Path $directory 'stop'); scope='Independent phone UID / host identity / ledger / random LAN port. Only explicit phone requests use production NativeInput; no automatic submissions.' }
} catch {
    if ($process -and !$process.HasExited) {
        Set-Content -LiteralPath (Join-Path $directory 'stop') -Value '' -NoNewline
        if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id }
    }
    throw
}
