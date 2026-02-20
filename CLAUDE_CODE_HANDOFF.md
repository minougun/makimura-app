# Claude Code 連携メモ

Codex 側で以下は実装済みです。

- StepTrackingEngine へのロジック分離
- ユニットテスト追加
- Room 日次履歴保存
- 「今日 / 履歴 / 設定」UI
- グラデーション背景 + ガラス風カードUI
- 歩幅推定（身長・性別・補正）
- 消費カロリー推定（体重入力があれば優先、未入力時は推定体重 + 距離）
- 実測距離/歩数からの補正係数再計算
- 書き込み間引き（5秒）
- 週次/30日集計・期間フィルタと履歴CSVエクスポート
- 履歴CSV共有（共有シート）
- 履歴CSV保存先選択（SAF）
- 履歴CSVの即時オープン導線（最新CSVを開く）
- BOOT_COMPLETED / MY_PACKAGE_REPLACED での再開トリガー
- 通知更新の間引き（2秒/距離差分）
- BootReceiver の公開制限（`exported=false`）
- UIテスト（MainActivityタブ遷移）
- UI統合テスト（履歴集計表示・期間フィルタ・CSV出力）
- AndroidTest（履歴CSV出力ファイル作成）
- AndroidTest（ActivityScenario + Service 起動停止）
- 完パケ生成スクリプト（`scripts/make_complete_package.sh`）

## 次に依頼する順（推奨）

1. バックグラウンド運用の更なる最適化
2. 長時間運用の異常系強化（例外復帰）
3. テスト拡張（統合/E2E）
4. 設定/履歴のUX改善

## Claude に渡すプロンプト例

### 1) バッテリー最適化

```text
TrackingService の電力消費をさらに最適化してください。
- センサーイベント処理の軽量化
- 画面OFF時の更新頻度制御
- 不要な通知更新の抑制
効果を定量的に説明してください。
```

### 2) 異常系強化

```text
TrackingService の異常系を強化してください。
- センサー登録失敗時の再試行
- 予期しない例外時の状態破損防止
- 復帰時に二重起動しないガード
```

### 3) 統合テスト

```text
このプロジェクトに統合テストを追加してください。
- ActivityScenario + Service 起動停止の確認
- 履歴画面の集計値表示検証
- CSV出力の成功フロー検証（ファイル存在）
CIでの安定実行を重視してください。
```

### 4) UX改善

```text
設定/履歴画面のUXを改善してください。
- 履歴の絞り込み（日/週/月）
- 設定値のバリデーションメッセージ改善
- CSV出力後の導線（保存先を開く/共有）を明確化
```

## 受け入れ条件

- `./gradlew.bat :app:assembleDebug` が成功する
- `./gradlew.bat :app:testDebugUnitTest` が成功する
- センサー非対応端末でクラッシュしない
- 日付切替時に前日の履歴が保存される

## 完パケ生成

```bash
./scripts/make_complete_package.sh
```

生成物:

- `dist/pedometer_complete_<timestamp>/`
- `dist/pedometer_complete_<timestamp>.zip` または `dist/pedometer_complete_<timestamp>.tar.gz`
