# PedometerApp

歩数計アプリの実装です。1日単位で以下を計測します。

- 歩数
- 総距離（推定）
- 平均速度（推定）
- 消費カロリー（推定）
- 早歩きの時間/距離
- 走行の時間/距離

さらに以下を実装済みです。

- 「今日 / 履歴 / 設定」の3タブ UI
- グラデーション背景 + ガラス風カードのUIデザイン
- Room による日次履歴保存（端末保存を有効にした場合のみ）
- 身長/性別/歩幅補正 + 実測キャリブレーション
- 体重入力（任意）によるカロリー精度向上
- 履歴の週次/30日集計表示
- 履歴の期間フィルタ（全期間/7日/30日）
- 履歴CSVエクスポート（アプリ専用Documents配下）
- 履歴CSV共有（共有シート起動）
- 履歴CSVの即時オープン（最新CSVを開く）
- 履歴CSVの保存先選択（SAF）
- Step ロジックのユニットテスト
- 履歴集計ロジックのユニットテスト
- Compose UIテスト（タブ表示・遷移）
- Compose UI統合テスト（履歴集計表示・期間フィルタ・CSV出力）
- AndroidTest（履歴CSV出力ファイル作成）
- AndroidTest（ActivityScenario + Service 起動停止）
- SharedPreferences 書き込みの間引き（5秒ごと）
- 通知更新の間引き（2秒/差分閾値）

## 実装メモ

- `TYPE_STEP_COUNTER` と `TYPE_STEP_DETECTOR` を利用
- 早歩き/走行の判定はケイデンス（steps/min）で分類
- 距離は歩幅モデルで推定（設定から調整可能）
- 消費カロリーは体重入力（任意）と移動距離から概算
- 体重未入力時は身長・性別から推定体重を利用
- 日付が変わると日次メトリクスを履歴へ保存してリセット
- 計測は Foreground Service で継続
- 端末再起動/アプリアップデート後に計測再開を試行（`BootReceiver`）

> 注意: GPS と心拍を使わないため、距離・速度・消費カロリーは推定値です。

## 主要ファイル

- `app/src/main/java/com/minou/pedometer/MainActivity.kt`
- `app/src/main/java/com/minou/pedometer/TrackingService.kt`
- `app/src/main/java/com/minou/pedometer/MetricsRepository.kt`
- `app/src/main/java/com/minou/pedometer/Models.kt`
- `app/src/main/java/com/minou/pedometer/StepTrackingEngine.kt`
- `app/src/main/java/com/minou/pedometer/StrideEstimator.kt`
- `app/src/main/java/com/minou/pedometer/HistoryDatabase.kt`
- `app/src/test/java/com/minou/pedometer/StepTrackingEngineTest.kt`

## ビルド

Windows PowerShell 例:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd C:\Users\minou\pedometer-app
.\gradlew.bat :app:assembleDebug
```

テスト:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

完パケ作成 (APK/AAB + docs + SHA256 + アーカイブ):

```bash
./scripts/make_complete_package.sh
```

出力:

- `dist/pedometer_complete_<timestamp>/`
- `dist/pedometer_complete_<timestamp>.zip` または `dist/pedometer_complete_<timestamp>.tar.gz`

APK:

- `app/build/outputs/apk/debug/app-debug.apk`

### 署名と更新インストール

同じ `applicationId` (`com.minou.pedometer`) で更新インストールするには、署名鍵を毎回同一にする必要があります。
署名鍵が異なるAPKを重ねると、インストーラで「更新しますか？」の後に「アプリがインストールされていません」が発生します。

1. `keystore.properties.example` をコピーして `keystore.properties` を作成
2. `storeFile/storePassword/keyAlias/keyPassword` を設定
3. `./gradlew :app:assembleRelease`（または `.\gradlew.bat :app:assembleRelease`）でビルド

`app/build.gradle.kts` は `Release` タスク時に `keystore.properties` が無い場合ビルド失敗にし、署名ブレを防止します。
`versionCode` は `20260306` 以上の単調増加を維持してください（小さい値に戻すとダウングレードで更新不可）。

## Web版 (iOS/Android/PCブラウザ)

`web/` にブラウザ向け実装を追加しています。主な機能:

- Today / History / Settings の3タブUI
- `DeviceMotion` ベースの歩数推定（iOSの権限要求に対応）
- Android版と同等の歩幅・カロリー推定ロジック
- 日付切り替え時の日次履歴アーカイブ
- 期間フィルタ（全期間 / 7日 / 30日）とCSVエクスポート
- 既定はタブ限定保存、必要時のみ端末への永続保存を明示的に有効化
- PWA対応（`manifest.webmanifest` + `sw.js`）

起動例:

```bash
cd /mnt/c/Users/minou/pedometer-app/web
python3 -m http.server 8080
```

ブラウザで `http://localhost:8080` を開きます。

注意:

- iOS Safari は「計測開始」時にモーション許可が必要です。
- センサー権限や対応状況により、端末によっては精度差があります。
- `file://` 直開きでは PWA / センサー権限が制限されるため、HTTP(S)配信で利用してください。
- 既定では設定値と履歴は `sessionStorage` に保持し、タブを閉じると破棄します。
- 端末への永続保存を有効にした場合のみ `localStorage` を使います。
- 共用端末では永続保存をオフのまま使い、必要に応じて「保存データを削除」を実行してください。

## 権限

- `ACTIVITY_RECOGNITION`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_HEALTH`
- `POST_NOTIFICATIONS` (Android 13+)
Android版も同じ方針で、既定では端末への永続保存を行いません。設定画面で「この端末にデータを保存する」を有効にした場合のみ、履歴・歩数メトリクス・体重/天気設定を端末へ保存します。
