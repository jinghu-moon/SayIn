param([ValidateRange(1, 100)][int]$Iterations = 3)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$java = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin\java.exe'
$javac = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin\javac.exe'
$zxingDirectory = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.google.zxing\core\3.4.1'
$zxing = @(Get-ChildItem -LiteralPath $zxingDirectory -Recurse -File -Filter 'core-3.4.1.jar')
if ($zxing.Count -ne 1) { throw 'Build the Android app first to resolve its pinned ZXing core.' }
$output = Join-Path $projectRoot 'desktop\target\qr-verification'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& $javac --class-path $zxing[0].FullName -d $output (Join-Path $PSScriptRoot 'verification\VerifyPairingQr.java')
if ($LASTEXITCODE -ne 0) { throw 'QR decoder verification harness failed to compile.' }
for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    $frames = Join-Path $output ('run-{0:D2}' -f $iteration)
    Push-Location (Join-Path $projectRoot 'desktop')
    try {
        & cargo run --example verify_pairing_qr --locked -- $frames
        if ($LASTEXITCODE -ne 0) { throw 'Native QR render verification failed.' }
        & $java --class-path ($zxing[0].FullName + ';' + $output) VerifyPairingQr $frames
        if ($LASTEXITCODE -ne 0) { throw 'ZXing did not decode the exact invitation from every native frame.' }
    } finally {
        Pop-Location
        # Temporary service is stopped before decoding. Do not retain its secret
        # payload, even when decoding fails. PNGs are ignored local artifacts.
        $expected = Join-Path $frames 'expected-qr.txt'
        if (Test-Path -LiteralPath $expected) { Remove-Item -LiteralPath $expected }
    }
}
Write-Output "Verified $Iterations independent invitations / $($Iterations * 6) native frames."
