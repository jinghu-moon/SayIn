# Run this project's wrapper with the confirmed JDK, without changing global Java settings.
$ErrorActionPreference = 'Stop'
$projectJdkPath = if ($env:VOICELINK_JDK_HOME) { $env:VOICELINK_JDK_HOME } else {
    'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot'
}
$projectJdkRelease = Join-Path $projectJdkPath 'release'
if (!(Test-Path -LiteralPath $projectJdkRelease) -or
    !(Select-String -LiteralPath $projectJdkRelease -SimpleMatch 'JAVA_RUNTIME_VERSION="25.0.5+8-LTS"' -Quiet)) {
    throw 'VoiceLink requires Temurin 25.0.5+8 LTS. Set VOICELINK_JDK_HOME to its installation directory.'
}
$previousProjectJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $projectJdkPath
    & (Join-Path $PSScriptRoot '..\android\gradlew.bat') '-p' (Join-Path $PSScriptRoot '..\android') @args
    $projectGradleExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousProjectJavaHome
}
exit $projectGradleExitCode
