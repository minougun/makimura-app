# 店頭テーブル用プリント素材

| ファイル | 用途 |
|----------|------|
| `makimura-table-guide.png` | **そのまま印刷推奨**（使い方＋QR 一体、2400×3600px 相当のラスタ） |
| `makimura-table-guide.svg` | イラレ／Inkscape で編集・任意サイズ印刷 |
| `makimura-app-qr.png` | QR のみ単体（別デザインに貼り込み用） |

**Web URL（QR の中身）:** `https://minougun.github.io/makimura-app/`

## 再生成

```bash
python3 docs/table-standee/generate_table_card.py
```

一体 PNG を出すには `cairosvg` と Cairo が必要です（未導入時は SVG と QR のみ出力）。

```bash
python3 -m pip install qrcode cairosvg --break-system-packages
```
