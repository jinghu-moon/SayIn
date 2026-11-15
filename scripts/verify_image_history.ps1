param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $projectRoot 'desktop/target' }
if (!$SkipBuild) {
    Push-Location (Join-Path $projectRoot 'desktop')
    try {
        cargo build -p voicelink-desktop --example verify_history_images --locked
        if ($LASTEXITCODE -ne 0) { throw 'History fixture build failed' }
    } finally { Pop-Location }
}
$directory = Join-Path $target ('history-soak-' + [guid]::NewGuid())
$process = $null
$samples = [System.Collections.Generic.List[object]]::new()
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    # Visible disposable native D3D window. No cross-application automation,
    # clipboard, listener or daily data; events stay inside its own GPUI window.
    $process = Start-Process -FilePath (Join-Path $target 'debug/examples/verify_history_images.exe') -ArgumentList @("`"$directory`"",'--soak') -PassThru -RedirectStandardOutput ($directory+'.log') -RedirectStandardError ($directory+'.stderr.log')
    while (!$process.HasExited) {
        if ($timer.Elapsed.TotalSeconds -gt 360) { throw 'Native history soak timed out' }
        $process.Refresh()
        $phase = $null
        if (Test-Path (Join-Path $directory 'phase.json')) {
            try { $phase = Get-Content (Join-Path $directory 'phase.json') -Raw | ConvertFrom-Json } catch { } # atomicity is not needed for telemetry
        }
        $samples.Add(@{elapsed_seconds=$timer.Elapsed.TotalSeconds; working_set=$process.WorkingSet64; private_bytes=$process.PrivateMemorySize64; phase=$phase})
        Start-Sleep -Milliseconds 200
    }
    @{exit_code=$process.ExitCode; samples=$samples; peak_working_set=$process.PeakWorkingSet64; duration_seconds=$timer.Elapsed.TotalSeconds} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $directory 'memory.json')
    if ($process.ExitCode -ne 0) { throw "Native history fixture failed; see $directory.stderr.log" }
    $result = Get-Content (Join-Path $directory 'soak-result.json') -Raw | ConvertFrom-Json
    if ($result.records -ne 30 -or @($result.rounds).Count -ne 60) { throw 'The full page and 60 actual dialog cycles are required' }
    function Median($values) {
        $sorted = @($values | Sort-Object)
        if (!$sorted.Count) { throw 'Missing native closed-dialog memory samples' }
        ($sorted[[int][Math]::Floor(($sorted.Count-1)/2)] + $sorted[[int][Math]::Floor($sorted.Count/2)]) / 2.0
    }
    $closed = @($samples | Where-Object { $_.phase -and $_.phase.phase -eq 'closed' })
    $early = Median @($closed | Where-Object { $_.phase.iteration -le 10 } | ForEach-Object private_bytes)
    $late = Median @($closed | Where-Object { $_.phase.iteration -gt 50 } | ForEach-Object private_bytes)
    # This fixed-workload guard catches the observed >500MiB original retention.
    # It is not a process/VRAM limit or a universal allocator guarantee.
    $growthLimit = 64L*1024*1024
    @{early_closed_median_private_bytes=$early; late_closed_median_private_bytes=$late; growth_bytes=($late-$early); growth_limit_bytes=$growthLimit} | ConvertTo-Json | Set-Content (Join-Path $directory 'memory-guard.json')
    if ($late-$early -gt $growthLimit) { throw "Original preview memory grew beyond the finite-soak regression guard; see $directory" }
    Write-Output "PASS: 30 records, 60 native dialog cycles; process CPU memory samples (not VRAM): $directory"
} finally {
    if ($process -and !$process.HasExited) { Stop-Process -Id $process.Id }
}
