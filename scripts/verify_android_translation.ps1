param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [Parameter(Mandatory)][string]$ModelsDirectory,
    [string[]]$ModelIds = @('hy-mt2-1.8b-q4', 'plamo-0.8b-q4', 'plamo-2b-q4'),
    [string]$CorpusFile,
    [ValidateSet('debug', 'release')][string]$BuildType = 'release',
    [switch]$SkipBuild,
    [switch]$CancellationOnly,
    [switch]$UseExistingModels,
    [switch]$UseExistingRootInstall
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$adbPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$catalog = Get-Content -LiteralPath (Join-Path $projectRoot 'android/translation-artifacts.json') -Raw | ConvertFrom-Json
$reportDir = Join-Path $projectRoot "android/local-translation/results/$DeviceSerial-$BuildType"
New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
function Invoke-Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE)" }
}
# Shared named lease plus OS instrumentation check. Never stop another agent's tests.
$lease = [System.Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
$acquired = $false
try {
    $acquired = $lease.WaitOne(0)
    if (!$acquired) { throw 'Device verification lease is held; run serially after the other test finishes.' }
    $activities = & $adbPath -s $DeviceSerial shell dumpsys activity
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect device activities.' }
    if ($activities -match 'mRunningInstrumentation=InstrumentationRecord|Active instrumentation:') {
        throw 'Another instrumentation run is active. Wait for it to finish.'
    }
    if (!$SkipBuild) {
        $variant = (Get-Culture).TextInfo.ToTitleCase($BuildType)
        Invoke-Checked { & (Join-Path $PSScriptRoot 'gradle.ps1') "-PtranslationBenchmarkBuildType=$BuildType" ":translation-benchmark:assemble$variant" ":translation-benchmark:assemble${variant}AndroidTest" }
    }
    $app = Join-Path $projectRoot "android/translation-benchmark/build/outputs/apk/$BuildType/translation-benchmark-$BuildType.apk"
    $test = Join-Path $projectRoot "android/translation-benchmark/build/outputs/apk/androidTest/$BuildType/translation-benchmark-$BuildType-androidTest.apk"
    foreach ($entry in @(@($app, 'app'), @($test, 'test'))) {
        if ($UseExistingRootInstall) {
            $remoteApk = "/data/local/tmp/sayin-translation-$($entry[1]).apk"
            Invoke-Checked { & $adbPath -s $DeviceSerial push $entry[0] $remoteApk }
            $installed = & $adbPath -s $DeviceSerial shell "su -c 'pm install -r -t $remoteApk'"
            if ($LASTEXITCODE -ne 0 -or $installed -notcontains 'Success') { throw 'Isolated benchmark APK install failed.' }
            Invoke-Checked { & $adbPath -s $DeviceSerial shell rm -f $remoteApk }
        } else {
            Invoke-Checked { & $adbPath -s $DeviceSerial install -r -t $entry[0] }
        }
    }
    $remoteModels = '/sdcard/Android/data/seeyuer.sayin.translationbench/files/models'
    Invoke-Checked { & $adbPath -s $DeviceSerial shell mkdir -p $remoteModels }
    $corpusArguments = @()
    if ($CorpusFile) {
        if ($CancellationOnly) { throw 'A custom corpus requires the full translation benchmark.' }
        $corpusPath = (Resolve-Path -LiteralPath $CorpusFile).Path
        Invoke-Checked { & $adbPath -s $DeviceSerial push $corpusPath '/sdcard/Android/data/seeyuer.sayin.translationbench/files/translation-cases.json' }
        $corpusArguments = @('-e', 'externalCorpus', 'true')
    }
    foreach ($id in $ModelIds) {
        $model = @($catalog.models | Where-Object id -EQ $id)
        if ($model.Count -ne 1) { throw "Unknown model ID: $id" }
        $model = $model[0]
        $localModel = Join-Path $ModelsDirectory $model.file
        if ((Get-Item -LiteralPath $localModel).Length -ne $model.bytes -or
            (Get-FileHash -LiteralPath $localModel -Algorithm SHA256).Hash.ToLowerInvariant() -ne $model.sha256) {
            throw "Invalid model: $id"
        }
        if (!$UseExistingModels) { Invoke-Checked { & $adbPath -s $DeviceSerial push $localModel "$remoteModels/$($model.file)" } }
        $testClass = if ($CancellationOnly) { 'TranslationCancellationBenchmark' } else { 'TranslationBenchmark' }
        $prefix = if ($CancellationOnly) { 'cancellation' } else { 'translation' }
        $output = & $adbPath -s $DeviceSerial shell am instrument -w -r -e modelId $id -e class "seeyuer.sayin.translationbench.$testClass" @corpusArguments seeyuer.sayin.translationbench.test/androidx.test.runner.AndroidJUnitRunner
        $output | Set-Content -LiteralPath (Join-Path $reportDir "$prefix-$id-instrumentation.txt") -Encoding utf8
        # Read app-private output through this isolated test package; never use product run-as or data paths.
        if ($CancellationOnly) {
            $report = & $adbPath -s $DeviceSerial shell cat "/sdcard/Android/data/seeyuer.sayin.translationbench/files/cancellation-$id.json"
        } elseif ($BuildType -eq 'debug') {
            $report = & $adbPath -s $DeviceSerial shell run-as seeyuer.sayin.translationbench cat "files/translation-$id.json"
        } elseif ($UseExistingRootInstall) {
            $report = & $adbPath -s $DeviceSerial shell "su -c 'cat /data/user/0/seeyuer.sayin.translationbench/files/translation-$id.json'"
        } else {
            # Tests export only this synthetic benchmark report to their own external app directory.
            $report = & $adbPath -s $DeviceSerial shell cat "/sdcard/Android/data/seeyuer.sayin.translationbench/files/translation-$id.json"
        }
        $reportName = if ($CancellationOnly) { "cancellation-$id.json" } else { "$id.json" }
        $report | Set-Content -LiteralPath (Join-Path $reportDir $reportName) -Encoding utf8
        if (($output -join "`n") -notmatch 'OK \(1 test\)') { $output | Write-Output; throw "Translation verification failed: $id" }
        Write-Output "Translation verification passed: $id"
    }
} finally {
    if ($acquired) { $lease.ReleaseMutex() }
    $lease.Dispose()
}
