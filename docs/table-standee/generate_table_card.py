#!/usr/bin/env python3
"""店頭テーブル用: QR PNG + 埋め込み用 base64 を生成し、SVG を出力する。"""
from __future__ import annotations

import base64
from io import BytesIO
from pathlib import Path

import qrcode

WEB_URL = "https://minougun.github.io/makimura-app/"
OUT_DIR = Path(__file__).resolve().parent


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=12,
        border=2,
    )
    qr.add_data(WEB_URL)
    qr.make(fit=True)
    img = qr.make_image(fill_color="#3d2b1f", back_color="#ffffff").convert("RGB")

    png_path = OUT_DIR / "makimura-app-qr.png"
    img.save(png_path, format="PNG", optimize=True)

    buf = BytesIO()
    img.save(buf, format="PNG")
    b64 = base64.standard_b64encode(buf.getvalue()).decode("ascii")

    svg_path = OUT_DIR / "makimura-table-guide.svg"
    svg_path.write_text(_svg_template(b64), encoding="utf-8")

    print(f"Wrote {png_path}")
    print(f"Wrote {svg_path}")

    try:
        import cairosvg

        png_full = OUT_DIR / "makimura-table-guide.png"
        cairosvg.svg2png(url=str(svg_path), write_to=str(png_full), scale=2)
        print(f"Wrote {png_full}")
    except Exception as exc:  # noqa: BLE001
        print(f"Skip PNG raster (install cairosvg + cairo if needed): {exc}")


def _svg_template(qr_base64: str) -> str:
    # viewBox: A系比率に近い縦長テーブルカード
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
     width="1200" height="1800" viewBox="0 0 1200 1800">
  <defs>
    <linearGradient id="hdr" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#8b1a1a"/>
      <stop offset="100%" style="stop-color:#a0522d"/>
    </linearGradient>
  </defs>
  <rect width="1200" height="1800" fill="#fdf6ec"/>
  <rect width="1200" height="200" fill="url(#hdr)"/>
  <text x="600" y="95" text-anchor="middle" fill="#ffffff" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif"
        font-size="56" font-weight="900" letter-spacing="6">麺家まきむら</text>
  <text x="600" y="155" text-anchor="middle" fill="#f5e6dc" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif"
        font-size="22" font-weight="500">気分 × 運動量 × 天気で、今日の一杯をご提案</text>

  <text x="600" y="270" text-anchor="middle" fill="#3d2b1f" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif"
        font-size="34" font-weight="700">スマホで「今日のおすすめ」を試してみよう</text>

  <!-- 手順アイコン風（簡易図形） -->
  <g transform="translate(80, 320)">
    <rect x="0" y="0" width="1000" height="220" rx="20" fill="#fff7eb" stroke="#e8dcc8" stroke-width="3"/>
    <circle cx="70" cy="110" r="44" fill="#8b1a1a"/>
    <text x="70" y="125" text-anchor="middle" fill="#fff" font-family="sans-serif" font-size="40" font-weight="700">1</text>
    <text x="150" y="85" fill="#3d2b1f" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="28" font-weight="700">QRコードを読み取る</text>
    <text x="150" y="130" fill="#666" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="22">カメラアプリやQRリーダーで、下のコードをスキャンしてWebを開いてください。</text>
    <text x="150" y="175" fill="#666" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="20">（インストール不要・ブラウザで利用できます）</text>
  </g>

  <g transform="translate(80, 570)">
    <rect x="0" y="0" width="1000" height="200" rx="20" fill="#fff7eb" stroke="#e8dcc8" stroke-width="3"/>
    <circle cx="70" cy="100" r="44" fill="#8b1a1a"/>
    <text x="70" y="115" text-anchor="middle" fill="#fff" font-family="sans-serif" font-size="40" font-weight="700">2</text>
    <text x="150" y="75" fill="#3d2b1f" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="28" font-weight="700">ホームで好みを選ぶ（任意）</text>
    <text x="150" y="120" fill="#666" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="22">空腹度・気分・トッピングの除外などをタップすると、おすすめが変わります。</text>
    <text x="150" y="160" fill="#666" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="20">歩数は「運動」タブから計測できます（許可が必要な場合があります）。</text>
  </g>

  <g transform="translate(80, 800)">
    <rect x="0" y="0" width="1000" height="180" rx="20" fill="#fff7eb" stroke="#e8dcc8" stroke-width="3"/>
    <circle cx="70" cy="90" r="44" fill="#8b1a1a"/>
    <text x="70" y="105" text-anchor="middle" fill="#fff" font-family="sans-serif" font-size="40" font-weight="700">3</text>
    <text x="150" y="70" fill="#3d2b1f" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="28" font-weight="700">ご注文はスタッフへ</text>
    <text x="150" y="115" fill="#666" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="22">画面はあくまで参考提案です。実際のメニュー・在庫に合わせ、お近くのスタッフにご注文ください。</text>
  </g>

  <text x="600" y="1080" text-anchor="middle" fill="#3d2b1f" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif"
        font-size="26" font-weight="700">Webアプリ URL（QRで開きます）</text>
  <text x="600" y="1125" text-anchor="middle" fill="#8b1a1a" font-family="Consolas, monospace" font-size="24">{WEB_URL}</text>

  <rect x="420" y="1160" width="360" height="360" fill="#ffffff" stroke="#e8dcc8" stroke-width="4" rx="12"/>
  <image href="data:image/png;base64,{qr_base64}" x="436" y="1176" width="328" height="328"/>

  <text x="600" y="1600" text-anchor="middle" fill="#999" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="18">
    共有の端末・お子さまの利用の際は、個人情報の取り扱いにご注意ください。
  </text>
</svg>
'''


if __name__ == "__main__":
    main()
