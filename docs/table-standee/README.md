# 店頭テーブル用プリント素材

| ファイル | 用途 |
|----------|------|
| `makimura-table-guide.png` | **印刷推奨**（使い方・**iOS/Android のホーム画面に追加**・QR を含む。Pillow + Windows フォント） |
| `makimura-table-guide.svg` | イラレ／Inkscape 用の簡易版（手順ボックスは PNG に含めず URL+QR 中心） |
| `makimura-app-qr.png` | QR のみ単体 |

**Web URL（QR の中身）:** `https://minougun.github.io/makimura-app/`

## 再生成

WSL などでは `/mnt/c/Windows/Fonts/meiryo.ttc` を参照します。  
別のフォントを使う場合:

```bash
export MAKIMURA_TABLE_FONT="/path/to/YourFont.ttf"
python3 docs/table-standee/generate_table_card.py
```

依存:

```bash
python3 -m pip install qrcode pillow --break-system-packages
```
