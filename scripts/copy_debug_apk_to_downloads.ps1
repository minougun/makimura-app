# デバッグ APK を「ダウンロード」フォルダにコピーする
# 使い方（プロジェクトルートで）:
#   .\scripts\copy_debug_apk_to_downloads.ps1
#
# 前提: 先に .\gradlew.bat :app:assembleDebug が成功していること

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path -LiteralPath $apk)) {
    Write-Host "APK が見つかりません: $apk" -ForegroundColor Red
    Write-Host "プロジェクトルートで次を実行してから再度お試しください:" -ForegroundColor Yellow
    Write-Host "  .\gradlew.bat :app:assembleDebug" -ForegroundColor Cyan
    exit 1
}

$downloads = Join-Path $env:USERPROFILE "Downloads"
if (-not (Test-Path -LiteralPath $downloads)) {
    New-Item -ItemType Directory -Path $downloads | Out-Null
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$dest = Join-Path $downloads "makimura-app-debug-$stamp.apk"

Copy-Item -LiteralPath $apk -Destination $dest -Force
Write-Host "コピーしました:" -ForegroundColor Green
Write-Host $dest
