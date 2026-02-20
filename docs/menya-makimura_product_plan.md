# 麺家まきむら アプリ企画（MVP）

## 1. プロダクトの核
- 自分でトッピングを選べるラーメン注文体験。
- 当日の運動量（歩数・運動時間・消費カロリー）を使い、今日向けのおすすめを提案。
- 初期はオフライン優先、個人情報は最小限。

## 2. MVP機能（優先順）
1. トッピング選択 + 合計金額のリアルタイム表示
2. 今日の運動データ表示（既存万歩計機能を再利用）
3. 「今日のおすすめ」3パターン提案（ライト/バランス/ご褒美）
4. 注文内容をローカル履歴保存（直近30件）
5. 体重設定（既存機能を利用）でカロリー推定を補正

## 3. おすすめロジック（実装しやすい版）
### A. 段階制（MVP採用）
- `steps < 4,000`: ライト提案（例: ラーメン + ねぎ）
- `4,000 <= steps < 10,000`: バランス提案（例: ラーメン + 煮卵 + メンマ）
- `steps >= 10,000`: ご褒美提案（例: ラーメン + 替玉 + ミニチャーシュー丼）
- 天候補正:
  - `temp <= 8` または `雨/雪`: ニンニク・キムチを追加
  - `temp >= 28`: ねぎ・コーン・煮卵を優先、重めサイドを明太子ご飯へ置換

### B. スコア制（v1.1以降）
- `activityScore = steps/8000*0.5 + activeMinutes/30*0.3 + calories/400*0.2`
- スコア帯で提案強度を調整。

## 4. 画面構成（最小）
1. Home
   - 今日の運動サマリーカード
   - 今日のおすすめカード（3案）
2. Menu Builder
   - 商品一覧（カテゴリ: ラーメン/トッピング/ご飯/ドリンク）
   - 合計金額、選択内容
3. Confirm
   - 注文確定（アプリ内記録）
4. History
   - 過去注文とその日の運動量
5. Settings
   - 体重、提案モード（段階制/スコア制）
   - 都市名で天気・気温を自動取得（Open-Meteo）

## 5. データモデル案（Kotlin）
```kotlin
data class MenuItem(
    val id: String,
    val name: String,
    val priceYen: Int,
    val category: MenuCategory,
    val sortOrder: Int
)

enum class MenuCategory { RAMEN, TOPPING, RICE, DRINK }

data class DailyActivity(
    val date: String, // yyyy-MM-dd
    val steps: Int,
    val activeMinutes: Int,
    val caloriesKcal: Double
)

data class Recommendation(
    val date: String,
    val tier: RecommendationTier,
    val itemIds: List<String>,
    val reason: String
)

enum class RecommendationTier { LIGHT, BALANCE, REWARD }

data class DraftOrder(
    val selectedItemIds: Set<String>,
    val totalYen: Int
)
```

## 6. UIデザイン方向（かっこよさ重視）
- テーマ: 券売機 + 暖簾 + 湯気を感じる和モダン。
- カラー案:
  - 背景: `#F7F2E8`
  - 文字: `#1E1A16`
  - アクセント赤: `#C73A27`
  - 補助緑: `#4E6B4C`
  - 強調金: `#D29A2E`
- 動き:
  - 初回表示で商品カードを段差フェードイン
  - 合計金額更新時に短いカウントアップ

## 7. 4週間ロードマップ
1. Week 1: Menu Builder / 合計計算 / 静的メニューデータ実装
2. Week 2: 既存運動データ接続 / Homeサマリー / おすすめ段階制実装
3. Week 3: 履歴保存 / デザイン実装 / アニメーション調整
4. Week 4: テスト（計算・提案・永続化）/ APK配布準備

## 8. Claude Codeで出た差別化案（候補）
- ローカルスタンプカード
- 「いつもの」プリセット注文
- 混雑傾向ヒートマップ（過去傾向の静的表示）
- 食べ比べ手帳（ローカル日記）
