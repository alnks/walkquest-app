# -*- coding: utf-8 -*-
"""Склейка сравнительных картинок «макет ↔ реализация» для сверки экранов.

СЛЕВА — отрендеренный макет (compare/_mock/<id>-<theme>.png),
СПРАВА — скриншот приложения (_qa/emulator-run/compare-verify/<id>-<theme>.png).
Результат: compare/<id>.png
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
CMP = ROOT / "_bmad-output/initiative-walkquest/ux-walkquest/compare"
MOCK = CMP / "_mock"
APP = ROOT / "_qa/emulator-run/compare-verify"

# (id, [(theme, mock_exists, app_exists)], заголовок)
SCREENS = [
    ("01-map-home", [("dark", True, True), ("light", True, True)], "01 · Главная карта"),
    ("02-map-walk", [("dark", True, True)], "02 · Прогулка (запись)"),
    ("02p-map-walk-paused", [("dark", True, True)], "02п · Прогулка (пауза)"),
    ("03-walk-final", [("dark", True, True)], "03 · Финал прогулки"),
    ("04-onboarding", [("dark", True, True)], "04 · Онбординг"),
    ("05-progress-stats", [("dark", True, True), ("light", True, True)], "05 · Прогресс · Статистика"),
    ("06-progress-achievements", [("dark", True, True)], "06 · Прогресс · Достижения"),
    ("07-progress-quests", [("dark", True, True)], "07 · Прогресс · Задания"),
    ("08-rating", [("dark", True, True), ("light", True, True)], "08 · Рейтинг (первый вход)"),
    ("09-profile", [("dark", True, True), ("light", True, True)], "09 · Профиль"),
    ("10-regions", [("dark", True, True)], "10 · Мои регионы"),
    ("11-settings", [("dark", True, True), ("light", True, True)], "11 · Настройки"),
    ("12-poi-card", [("dark", True, True)], "12 · Карточка POI"),
]

THEME_LABEL = {"dark": "Тёмная тема", "light": "Светлая тема"}
BG = (16, 13, 24)
PANEL = (30, 24, 45)
FG = (239, 234, 247)
DIM = (141, 131, 166)

FONT = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 26)
FONT_B = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 34)
FONT_S = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 22)

PAD = 28
COL_GAP = 40


def text_w(draw, s, font):
    return draw.textbbox((0, 0), s, font=font)[2]


def load_app(path, width):
    im = Image.open(path).convert("RGB")
    h = round(im.height * width / im.width)
    return im.resize((width, h), Image.LANCZOS)


def main():
    for sid, themes, title in SCREENS:
        # готовим изображения строк
        rows = []
        for theme, has_mock, has_app in themes:
            mock_path = MOCK / f"{sid}-{theme}.png"
            app_path = APP / f"{sid}-{theme}.png"
            if not (mock_path.exists() and app_path.exists()):
                print(f"SKIP {sid}-{theme}: нет файла")
                continue
            mock = Image.open(mock_path).convert("RGB")
            app = load_app(app_path, mock.width)
            rows.append((theme, mock, app))
        if not rows:
            continue
        col_w = max(max(r[1].width, r[2].width) for r in rows)
        # размеры
        head_h = 84
        theme_h = 44
        label_h = 40
        row_hs = [max(r[1].height, r[2].height) for r in rows]
        w = PAD * 2 + col_w * 2 + COL_GAP
        h = head_h + sum(theme_h + label_h + rh + PAD for rh in row_hs) + PAD
        canvas = Image.new("RGB", (w, h), BG)
        d = ImageDraw.Draw(canvas)
        d.text((PAD, 26), title, font=FONT_B, fill=FG)
        y = head_h
        for (theme, mock, app), rh in zip(rows, row_hs):
            tw = text_w(d, THEME_LABEL[theme], FONT)
            d.rectangle([PAD, y, PAD + tw + 24, y + theme_h - 8], fill=PANEL)
            d.text((PAD + 12, y + 6), THEME_LABEL[theme], font=FONT, fill=FG)
            y += theme_h
            lx = PAD + col_w // 2
            rx = PAD + col_w + COL_GAP + col_w // 2
            d.text((lx - text_w(d, "МАКЕТ", FONT_S) // 2, y + 6), "МАКЕТ", font=FONT_S, fill=DIM)
            d.text((rx - text_w(d, "РЕАЛИЗАЦИЯ", FONT_S) // 2, y + 6), "РЕАЛИЗАЦИЯ", font=FONT_S, fill=DIM)
            y += label_h
            canvas.paste(mock, (PAD + (col_w - mock.width) // 2, y))
            canvas.paste(app, (PAD + col_w + COL_GAP + (col_w - app.width) // 2, y))
            y += rh + PAD
        out = CMP / f"{sid}.png"
        canvas.save(out, optimize=True)
        print(f"OK {out.name} {canvas.size}")


if __name__ == "__main__":
    main()
