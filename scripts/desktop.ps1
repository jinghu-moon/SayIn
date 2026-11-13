param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CargoArguments
)
$ErrorActionPreference = 'Stop'
$desktopRoot = Join-Path $PSScriptRoot '..\desktop'
Push-Location $desktopRoot
try {
    if (-not $CargoArguments) { $CargoArguments = @('run', '--locked') }
    & cargo @CargoArguments
    if ($LASTEXITCODE -ne 0) { throw "Cargo failed with exit code $LASTEXITCODE" }
}
finally { Pop-Location }
