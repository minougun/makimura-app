# PedometerApp Context

## Goal
1日単位で歩数、平均速度、早歩き/走行の時間距離を計測する Android アプリ。

## Current status
- Foreground Service + Step センサーで計測
- Compose で「今日 / 履歴 / 設定」タブを実装
- Room で日次履歴を保存し、履歴表示を実装
- 履歴タブに週次/30日集計、期間フィルタ、CSVエクスポートを実装
- 履歴CSV共有（共有シート）を実装
- 履歴CSVの保存先選択（SAF）を実装
- 身長/性別/補正係数ベースの歩幅推定を実装
- 実測値から補正係数を再計算する UI を実装
- `StepTrackingEngine` のユニットテストを実装
- `HistoryAnalytics` のユニットテストを実装
- `MainActivity` のUIテストを実装
- `HistoryCsvExporter` のAndroidTestを実装
- 再起動・更新後の計測再開トリガー（`BootReceiver`）を実装

## Important files
- `app/src/main/java/com/minou/pedometer/MainActivity.kt`
- `app/src/main/java/com/minou/pedometer/TrackingService.kt`
- `app/src/main/java/com/minou/pedometer/MetricsRepository.kt`
- `app/src/main/java/com/minou/pedometer/Models.kt`
- `app/src/main/java/com/minou/pedometer/StepTrackingEngine.kt`
- `app/src/main/java/com/minou/pedometer/StrideEstimator.kt`
- `app/src/main/java/com/minou/pedometer/HistoryDatabase.kt`
- `app/src/test/java/com/minou/pedometer/StepTrackingEngineTest.kt`

## Notes
- 距離と速度は歩幅モデルによる推定値
- 次の拡張は `CLAUDE_CODE_HANDOFF.md` の優先順で進める
