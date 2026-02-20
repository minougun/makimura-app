# Claude Code 連携メモ (Web版)

## 現在の実装

`web/` に iOS/Android/PC ブラウザ向けの歩数計を実装済みです。

- `web/index.html`: 3タブUI (Today / History / Settings)
- `web/styles.css`: モバイル対応レイアウト + グラデーションUI
- `web/app.js`: 歩数推定ロジック、履歴、CSV、設定、永続化
- `web/manifest.webmanifest`, `web/sw.js`: PWA対応

### ロジック準拠

Android 版の以下ロジックをWebに移植済み:

- 歩幅推定 (`height + sex + strideScale`)
- ケイデンス分類 (`WALKING / BRISK / RUNNING`)
- カロリー推定（体重入力優先、未入力時は推定BMI）
- 7日/30日集計、CSV列形式

## 実行

```bash
cd /mnt/c/Users/minou/pedometer-app/web
python3 -m http.server 8080
```

`http://localhost:8080` を開く。

## Claude に次依頼する優先順

1. センサー精度改善（偽陽性/偽陰性の低減）
2. テスト導入（ロジックをモジュール分離してユニットテスト化）
3. IndexedDB への履歴保存移行（大量履歴対応）
4. ホーム画面インストール導線とオフラインUX改善

## Claude 依頼プロンプト例

### 1) 歩数検出精度改善

```text
web/app.js の DeviceMotion 歩数検出を改善してください。
- 手振れや端末揺れでの誤検出を減らす
- 歩行開始直後の検出遅延を減らす
- iOS Safari と Android Chrome で同じ閾値でも安定するよう調整
閾値設計の理由をコメントで残してください。
```

### 2) テスト導入

```text
歩数計算ロジックを pure function に分離して、
- 歩幅推定
- ケイデンス分類
- カロリー推定
- CSV生成
のユニットテストを追加してください。
テスト実行コマンドも README に追記してください。
```

### 3) 永続化強化

```text
履歴保存を LocalStorage から IndexedDB に移行してください。
- 既存 LocalStorage データの移行処理
- 30日/365日など条件付きクエリ
- CSV出力時の読み出しを非同期化
```

## 受け入れ条件

- iOS Safari で権限許可後に歩数が増える
- Android Chrome でも同様に計測できる
- 日付変更で前日データが履歴へ退避される
- CSVエクスポートでヘッダー/列順が崩れない
