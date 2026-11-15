param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [Parameter(Mandatory)][string]$ModelsDirectory,
    [ValidateSet('debug', 'release')][string]$BuildType = 'release',
    [switch]$SkipBuild,
    [switch]$DownloadModel,
    [switch]$UseExistingModel,
    [switch]$IncludeSessionRegression,
    [switch]$UseExistingRootInstall
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$aapt = Join-Path $sdk 'build-tools/37.0.0/aapt2.exe'
$applicationId = 'seeyuer.sayin.translationverification'
$lease = [System.Threading.Mutex]::new($false, "Local\SayIn-Android-$DeviceSerial")
$acquired = $false
function Invoke-Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE)" }
}
try {
    if ($BuildType -eq 'release' -and !$UseExistingRootInstall) { throw 'External Release fixture needs the explicitly authorized root install to prepare only its isolated private model; ordinary product use never needs root.' }
    if ($DownloadModel -and $BuildType -eq 'release') { throw 'Download scenario uses the debug native harness; Release uses the platform UI harness with a verified local weight.' }
    $acquired = $lease.WaitOne(0)
    if (!$acquired) { throw 'Another SayIn device verification is active; run serially.' }
    $activities = (& $adb -s $DeviceSerial shell dumpsys activity) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $activities -match 'mRunningInstrumentation=InstrumentationRecord|Active instrumentation:') {
        throw 'Cannot obtain an idle instrumentation device.'
    }
    if (!$SkipBuild) {
        $variant = (Get-Culture).TextInfo.ToTitleCase($BuildType)
        $tasks = @(':app:assemble' + $variant)
        if ($BuildType -eq 'release') {
            $tasks += @(':translation-benchmark:assembleDebug', ':translation-benchmark:assembleDebugAndroidTest')
        } else { $tasks += ":app:assemble${variant}AndroidTest" }
        Invoke-Checked { & (Join-Path $PSScriptRoot 'gradle.ps1') '-PsayinVerification=true' "-PsayinVerificationBuildType=$BuildType" @tasks }
    }
    $app = Join-Path $projectRoot "android/app/build/outputs/apk/$BuildType/app-$BuildType.apk"
    $test = Join-Path $projectRoot "android/app/build/outputs/apk/androidTest/$BuildType/app-$BuildType-androidTest.apk"
    $testPackage = "$applicationId.test"
    if ($BuildType -eq 'release') {
        $test = Join-Path $projectRoot 'android/translation-benchmark/build/outputs/apk/androidTest/debug/translation-benchmark-debug-androidTest.apk'
        $testPackage = 'seeyuer.sayin.translationbench.test'
    }
    foreach ($entry in @(@($app, $applicationId), @($test, $testPackage))) {
        $metadata = (& $aapt dump badging $entry[0]) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $metadata -notmatch ("package: name='" + [regex]::Escape($entry[1]) + "'")) {
            throw 'Refusing an APK outside the isolated verification package.'
        }
    }
    $catalog = Get-Content (Join-Path $projectRoot 'android/translation-artifacts.json') -Raw | ConvertFrom-Json
    $model = @($catalog.models | Where-Object id -EQ $catalog.defaultModel)[0]
    $modelPath = Join-Path $ModelsDirectory $model.file
    if ((Get-Item -LiteralPath $modelPath).Length -ne $model.bytes -or (Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $model.sha256) {
        throw 'Default model integrity check failed.'
    }
    $installations = @(@($app, 'app'), @($test, 'test'))
    if ($BuildType -eq 'release') {
        $driver = Join-Path $projectRoot 'android/translation-benchmark/build/outputs/apk/debug/translation-benchmark-debug.apk'
        $metadata = (& $aapt dump badging $driver) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $metadata -notmatch "package: name='seeyuer.sayin.translationbench'") { throw 'Invalid isolated UI driver APK.' }
        $installations += ,@($driver, 'driver')
    }
    foreach ($entry in $installations) {
        if ($UseExistingRootInstall) {
            $remote = "/data/local/tmp/sayin-translation-product-$($entry[1]).apk"
            Invoke-Checked { & $adb -s $DeviceSerial push $entry[0] $remote }
            $output = & $adb -s $DeviceSerial shell "su -c 'pm install -r -t $remote'"
            if ($LASTEXITCODE -ne 0 -or $output -notcontains 'Success') { throw 'Isolated product verification install failed.' }
            Invoke-Checked { & $adb -s $DeviceSerial shell rm -f $remote }
        } else { Invoke-Checked { & $adb -s $DeviceSerial install -r -t $entry[0] } }
    }
    $remoteDirectory = "/sdcard/Android/data/$applicationId/files"
    if (!$DownloadModel -and !$UseExistingModel) {
        Invoke-Checked { & $adb -s $DeviceSerial shell mkdir -p "$remoteDirectory/models" }
        Invoke-Checked { & $adb -s $DeviceSerial push $modelPath "$remoteDirectory/models/$($model.file)" }
    }
    # Only generated silence and synthetic text. No microphone, cloud ASR or LAN fixture.
    if ($BuildType -eq 'release') {
        # Developer fixture only: no production import or root dependency. Both private paths belong to the isolated package.
        $privateDirectory = "/data/user/0/$applicationId/no_backup/translation"
        Invoke-Checked { & $adb -s $DeviceSerial shell "su -c 'mkdir -p $privateDirectory'" }
        $modelUid = (& $adb -s $DeviceSerial shell "su -c 'stat -c %u /data/user/0/$applicationId'").Trim()
        if ($modelUid -notmatch '^\d+$') { throw 'Cannot resolve isolated app UID.' }
        $pending = "$privateDirectory/fixture.pending"
        Invoke-Checked { & $adb -s $DeviceSerial shell "su -c 'cp $remoteDirectory/models/$($model.file) $pending'" }
        $remoteHash = (& $adb -s $DeviceSerial shell "su -c 'sha256sum $pending'") -join ''
        if ($LASTEXITCODE -ne 0 -or $remoteHash -notmatch ('^' + $model.sha256 + '\s')) { throw 'Isolated private model integrity failed.' }
        Invoke-Checked { & $adb -s $DeviceSerial shell "su -c 'chown $($modelUid):$modelUid $privateDirectory $pending'" }
        Invoke-Checked { & $adb -s $DeviceSerial shell "su -c 'mv $pending $privateDirectory/$($model.file)'" }
        Invoke-Checked { & $adb -s $DeviceSerial shell "su -c 'restorecon -RF $privateDirectory'" }
        $remoteDirectory = '/sdcard/Android/data/seeyuer.sayin.translationbench/files'
        $output = & $adb -s $DeviceSerial shell am instrument -w -r -e class seeyuer.sayin.translationbench.TranslationReleaseSmoke seeyuer.sayin.translationbench.test/androidx.test.runner.AndroidJUnitRunner
    } else {
        $classes = 'seeyuer.sayin.ui.TranslationNativeInstrumentedTest'
        if ($IncludeSessionRegression) { $classes += ',seeyuer.sayin.ui.TranslationSessionInstrumentedTest,seeyuer.sayin.ui.TranslationUiInstrumentedTest' }
        $output = & $adb -s $DeviceSerial shell am instrument -w -r -e timeout_msec 600000 -e localTranslation true -e downloadTranslation ([string][bool]$DownloadModel).ToLowerInvariant() -e class $classes "$applicationId.test/androidx.test.runner.AndroidJUnitRunner"
    }
    $mode = if ($DownloadModel) { 'download' } else { 'import' }
    $directory = Join-Path $projectRoot "android/local-translation/results/$DeviceSerial-product-$BuildType-$mode"
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $output | Set-Content (Join-Path $directory 'instrumentation.txt') -Encoding utf8
    $pattern = if ($BuildType -eq 'debug' -and $IncludeSessionRegression) { 'OK \(6 tests\)' } else { 'OK \(1 test\)' }
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch $pattern) { $output | Write-Output; throw 'Product JNI verification failed.' }
    Invoke-Checked { & $adb -s $DeviceSerial pull "$remoteDirectory/product-native-report.json" (Join-Path $directory 'product-native-report.json') }
    Write-Output "Isolated product ${BuildType}: real translation JNI and co-resident ASR verification passed."
} finally {
    if ($acquired) { $lease.ReleaseMutex() }
    $lease.Dispose()
}
