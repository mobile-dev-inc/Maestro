$ErrorActionPreference = 'Stop'

.\gradlew :maestro-cli:installDist
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$maestroCmd = Get-Command maestro -ErrorAction SilentlyContinue
if (-not $maestroCmd) {
    Write-Error "maestro not found on PATH. Install maestro before using this script."
    exit 1
}

$installRoot = Split-Path (Split-Path $maestroCmd.Source -Parent) -Parent

Remove-Item -Recurse -Force "$installRoot\bin"
Remove-Item -Recurse -Force "$installRoot\lib"

Copy-Item -Recurse ".\maestro-cli\build\install\maestro\bin" "$installRoot\bin"
Copy-Item -Recurse ".\maestro-cli\build\install\maestro\lib" "$installRoot\lib"

Write-Host "Installed maestro to $installRoot"
