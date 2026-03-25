#!/usr/bin/env python3
"""
店頭テーブル用カードを生成する。
- makimura-app-qr.png … QR のみ
- makimura-table-guide.svg … 編集用ベクター（ブラウザ印刷向け）
- makimura-table-guide.png … **Pillow + Windows 日本語フォント**で全文描画（空欄防止）
"""
from __future__ import annotations

import base64
import os
import sys
from io import BytesIO
from pathlib import Path

import qrcode
from PIL import Image, ImageDraw, ImageFont

WEB_URL = "https://minougun.github.io/makimura-app/"
OUT_DIR = Path(__file__).resolve().parent

# WSL から Windows フォント（実店舗用 PNG はここが確実）
_WIN_FONT_CANDIDATES = [
    Path("/mnt/c/Windows/Fonts/meiryo.ttc"),
    Path("/mnt/c/Windows/Fonts/meiryob.ttc"),
    Path("/mnt/c/Windows/Fonts/YuGothM.ttc"),
    Path("/mnt/c/Windows/Fonts/BIZ-UDGothicR.ttc"),
    Path("C:/Windows/Fonts/meiryo.ttc"),
]


def _load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    env = os.environ.get("MAKIMURA_TABLE_FONT")
    paths = [Path(env)] if env else []
    paths += _WIN_FONT_CANDIDATES
    if bold:
        paths = [
            Path("/mnt/c/Windows/Fonts/meiryob.ttc"),
            Path("C:/Windows/Fonts/meiryob.ttc"),
            *paths,
        ]
    for p in paths:
        if not p or not p.is_file():
            continue
        try:
            return ImageFont.truetype(str(p), size=size, index=0)
        except OSError:
            continue
    print("ERROR: 日本語フォントが見つかりません。環境変数 MAKIMURA_TABLE_FONT に .ttf/.ttc のパスを指定してください。", file=sys.stderr)
    sys.exit(1)


def _wrap_text(text: str, font: ImageFont.FreeTypeFont, draw: ImageDraw.ImageDraw, max_width: int) -> list[str]:
    if not text.strip():
        return []
    lines: list[str] = []
    current = ""
    for ch in text:
        test = current + ch
        bbox = draw.textbbox((0, 0), test, font=font)
        if bbox[2] - bbox[0] <= max_width:
            current = test
        else:
            if current:
                lines.append(current)
            current = ch
    if current:
        lines.append(current)
    return lines


def _draw_gradient_header(img: Image.Image, y0: int, h: int) -> None:
    px = img.load()
    w, _ = img.size
    for y in range(y0, y0 + h):
        t = (y - y0) / max(h - 1, 1)
        r = int(139 + (160 - 139) * t)
        g = int(26 + (82 - 26) * t)
        b = int(26 + (45 - 26) * t)
        for x in range(w):
            px[x, y] = (r, g, b)


def _paste_qr(base: Image.Image, qr_img: Image.Image, dest_center_x: int, dest_top_y: int, box: int) -> None:
    q = qr_img.resize((box, box), Image.Resampling.LANCZOS)
    x = dest_center_x - box // 2
    base.paste(q, (x, dest_top_y))


def render_table_guide_png(qr_img: Image.Image) -> None:
    W, H = 2400, 3600
    M = 160
    text_w = W - 2 * M
    inner_left = M + 280  # 番号丸の右から本文

    img = Image.new("RGB", (W, H), "#fdf6ec")
    draw = ImageDraw.Draw(img)

    f_title = _load_font(108, bold=True)
    f_sub = _load_font(40)
    f_lead = _load_font(64, bold=True)
    f_step = _load_font(52, bold=True)
    f_body = _load_font(40)
    f_small = _load_font(36)
    f_url = _load_font(36)
    f_foot = _load_font(32)
    f_num = _load_font(72, bold=True)

    _draw_gradient_header(img, 0, 400)

    draw.text((W // 2, 150), "麺家まきむら", font=f_title, fill="#ffffff", anchor="mm")
    draw.text(
        (W // 2, 275),
        "気分 × 運動量 × 天気で、今日の一杯をご提案",
        font=f_sub,
        fill="#f5e6dc",
        anchor="mm",
    )

    y = 460
    draw.text((W // 2, y), "スマホで「今日のおすすめ」を試してみよう", font=f_lead, fill="#3d2b1f", anchor="mt")
    _bb = draw.textbbox((W // 2, y), "スマホで「今日のおすすめ」を試してみよう", font=f_lead, anchor="mt")
    y = _bb[3] + 48

    steps: list[tuple[str, list[str]]] = [
        (
            "1",
            [
                "QRコードを読み取る",
                "カメラアプリやQRリーダーで、下のコードをスキャンしてWebを開いてください。",
                "（インストール不要・ブラウザで利用できます）",
            ],
        ),
        (
            "2",
            [
                "ホームで好みを選ぶ（任意）",
                "空腹度・気分・トッピングの除外などをタップすると、おすすめが変わります。",
                "歩数は「運動」タブから計測できます（許可が必要な場合があります）。",
            ],
        ),
        (
            "3",
            [
                "ご注文はスタッフへ",
                "画面はあくまで参考提案です。実際のメニュー・在庫に合わせ、お近くのスタッフにご注文ください。",
            ],
        ),
    ]

    for num, parts in steps:
        title = parts[0]
        bodies = parts[1:]
        # カード高さを概算
        body_lines: list[tuple[ImageFont.FreeTypeFont, str]] = []
        for i, para in enumerate(bodies):
            font = f_body if i == 0 else f_small
            for line in _wrap_text(para, font, draw, text_w - (inner_left - M)):
                body_lines.append((font, line))
        title_lines = _wrap_text(title, f_step, draw, text_w - (inner_left - M))
        card_h = 80 + len(title_lines) * 58 + len(body_lines) * 52 + 60
        card_h = max(card_h, 220)

        draw.rounded_rectangle(
            (M, y, W - M, y + card_h),
            radius=36,
            fill="#fff7eb",
            outline="#e8dcc8",
            width=4,
        )
        cy = y + card_h // 2
        draw.ellipse((M + 60, cy - 88, M + 60 + 176, cy + 88), fill="#8b1a1a")
        draw.text((M + 60 + 88, cy), num, font=f_num, fill="#ffffff", anchor="mm")

        ty = y + 50
        for tl in title_lines:
            draw.text((inner_left, ty), tl, font=f_step, fill="#3d2b1f")
            bbox = draw.textbbox((inner_left, ty), tl, font=f_step)
            ty = bbox[3] + 12
        for font, line in body_lines:
            draw.text((inner_left, ty), line, font=font, fill="#555555")
            bbox = draw.textbbox((inner_left, ty), line, font=font)
            ty = bbox[3] + 10

        y += card_h + 40

    y += 20
    draw.text((W // 2, y), "Webアプリ URL（QRで開きます）", font=f_step, fill="#3d2b1f", anchor="mt")
    _bb = draw.textbbox((W // 2, y), "Webアプリ URL（QRで開きます）", font=f_step, anchor="mt")
    y = _bb[3] + 36
    draw.text((W // 2, y), WEB_URL, font=f_url, fill="#8b1a1a", anchor="mt")
    _bb = draw.textbbox((W // 2, y), WEB_URL, font=f_url, anchor="mt")
    y = _bb[3] + 56

    qr_box = 680
    frame_pad = 16
    fx0 = W // 2 - qr_box // 2 - frame_pad
    fy0 = y
    draw.rounded_rectangle(
        (fx0, fy0, fx0 + qr_box + 2 * frame_pad, fy0 + qr_box + 2 * frame_pad),
        radius=20,
        fill="#ffffff",
        outline="#e8dcc8",
        width=6,
    )
    inner_y = fy0 + frame_pad
    _paste_qr(img, qr_img, W // 2, inner_y, qr_box)
    y = fy0 + qr_box + 2 * frame_pad + 100

    foot = "共有の端末・お子さまの利用の際は、個人情報の取り扱いにご注意ください。"
    for line in _wrap_text(foot, f_foot, draw, text_w):
        draw.text((W // 2, y), line, font=f_foot, fill="#888888", anchor="mt")
        bbox = draw.textbbox((W // 2, y), line, font=f_foot, anchor="mt")
        y = bbox[3] + 16

    out = OUT_DIR / "makimura-table-guide.png"
    img.save(out, format="PNG", optimize=True)
    print(f"Wrote {out}")


def _svg_template(qr_base64: str) -> str:
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
  <text x="600" y="1600" text-anchor="middle" fill="#999" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif" font-size="18">
    共有の端末・お子さまの利用の際は、個人情報の取り扱いにご注意ください。
  </text>
  <text x="600" y="1080" text-anchor="middle" fill="#3d2b1f" font-family="Noto Sans JP, Hiragino Sans, Yu Gothic, Meiryo, sans-serif"
        font-size="26" font-weight="700">Webアプリ URL（QRで開きます）</text>
  <text x="600" y="1125" text-anchor="middle" fill="#8b1a1a" font-family="Consolas, monospace" font-size="24">{WEB_URL}</text>
  <rect x="420" y="1160" width="360" height="360" fill="#ffffff" stroke="#e8dcc8" stroke-width="4" rx="12"/>
  <image href="data:image/png;base64,{qr_base64}" x="436" y="1176" width="328" height="328"/>
</svg>
'''


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=14,
        border=2,
    )
    qr.add_data(WEB_URL)
    qr.make(fit=True)
    qr_pil = qr.make_image(fill_color="#3d2b1f", back_color="#ffffff").convert("RGB")

    (OUT_DIR / "makimura-app-qr.png").write_bytes(_png_bytes(qr_pil))
    print(f"Wrote {OUT_DIR / 'makimura-app-qr.png'}")

    b64 = base64.standard_b64encode(_png_bytes(qr_pil)).decode("ascii")
    (OUT_DIR / "makimura-table-guide.svg").write_text(_svg_template(b64), encoding="utf-8")
    print(f"Wrote {OUT_DIR / 'makimura-table-guide.svg'}")

    render_table_guide_png(qr_pil)


def _png_bytes(im: Image.Image) -> bytes:
    buf = BytesIO()
    im.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


if __name__ == "__main__":
    main()
