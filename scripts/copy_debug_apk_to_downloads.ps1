# Copy debug APK to Downloads (English output avoids PowerShell mojibake on Windows)
# Run from repo root:
#   powershell -ExecutionPolicy Bypass -File .\scripts\copy_debug_apk_to_downloads.ps1

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$defaultApk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"

function Find-DebugApk {
    param([string]$Root)
    if (Test-Path -LiteralPath $defaultApk) {
        return $defaultApk
    }
    $debugDir = Join-Path $Root "app\build\outputs\apk\debug"
    if (Test-Path -LiteralPath $debugDir) {
        $any = Get-ChildItem -LiteralPath $debugDir -Filter "*.apk" -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($any) { return $any.FullName }
    }
    $outputs = Join-Path $Root "app\build\outputs"
    if (-not (Test-Path -LiteralPath $outputs)) { return $null }
    $found = Get-ChildItem -LiteralPath $outputs -Filter "*.apk" -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending
    if ($found) { return $found[0].FullName }
    return $null
}

$apk = Find-DebugApk -Root $repoRoot

if (-not $apk) {
    Write-Host "APK not found." -ForegroundColor Red
    Write-Host "Expected: $defaultApk" -ForegroundColor Yellow
    Write-Host "Build first from repo root:" -ForegroundColor Yellow
    Write-Host "  .\gradlew.bat :app:assembleDebug" -ForegroundColor Cyan
    $buildRoot = Join-Path $repoRoot "app\build"
    if (Test-Path -LiteralPath $buildRoot) {
        Write-Host "`nExisting under app\build (apk only):" -ForegroundColor DarkGray
        Get-ChildItem -LiteralPath $buildRoot -Filter "*.apk" -Recurse -File -ErrorAction SilentlyContinue |
            ForEach-Object { Write-Host "  $($_.FullName)" }
    } else {
        Write-Host "`nFolder missing (build not run yet): $buildRoot" -ForegroundColor DarkGray
    }
    exit 1
}

$downloads = Join-Path $env:USERPROFILE "Downloads"
if (-not (Test-Path -LiteralPath $downloads)) {
    New-Item -ItemType Directory -Path $downloads | Out-Null
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$dest = Join-Path $downloads "makimura-app-debug-$stamp.apk"

Copy-Item -LiteralPath $apk -Destination $dest -Force
Write-Host "Copied OK:" -ForegroundColor Green
Write-Host $dest
Write-Host "(source: $apk)" -ForegroundColor DarkGray
