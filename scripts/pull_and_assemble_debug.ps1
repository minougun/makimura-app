# git pull (main) -> assembleDebug in one step. Run from anywhere:
#   powershell -ExecutionPolicy Bypass -File C:\Users\minou\makimura-app\scripts\pull_and_assemble_debug.ps1
#
# Optional: copy APK to Downloads after success
#   powershell -ExecutionPolicy Bypass -File .\scripts\pull_and_assemble_debug.ps1 -CopyToDownloads

param(
    [switch]$CopyToDownloads,
    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location -LiteralPath $repoRoot

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "==> git pull origin $Branch" -ForegroundColor Cyan
git pull origin $Branch
if ($LASTEXITCODE -ne 0) {
    Write-Host "git pull failed (exit $LASTEXITCODE)" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "==> .\gradlew.bat :app:assembleDebug --no-daemon" -ForegroundColor Cyan
& (Join-Path $repoRoot "gradlew.bat") ":app:assembleDebug" "--no-daemon"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Gradle build failed (exit $LASTEXITCODE)" -ForegroundColor Red
    exit $LASTEXITCODE
}

$apk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "BUILD OK: $apk" -ForegroundColor Green

if ($CopyToDownloads) {
    $copyScript = Join-Path $PSScriptRoot "copy_debug_apk_to_downloads.ps1"
    if (Test-Path -LiteralPath $copyScript) {
        & powershell -ExecutionPolicy Bypass -File $copyScript
    }
}
