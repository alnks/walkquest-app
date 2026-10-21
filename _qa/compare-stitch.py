# -*- coding: utf-8 -*-
"""Склейка сравнительных картинок «макет ↔ реализация» для сверки экранов.

СЛЕВА — отрендеренный макет (compare/_mock/<id>-<theme>.png),
СПРАВА — скриншот приложения (_qa/emulator-run/compare-verify/<id>-<theme>.png).
Результат: compare/<id>.png

Использование: python _qa/compare-stitch.py [выходная_папка] [id ...]
(без аргументов — все экраны в compare/, argv[1] — другая папка, argv[2:] — фильтр id)
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
CMP = ROOT / "_bmad-output/initiative-walkquest/ux-walkquest/compare"
MOCK = CMP / "_mock"
APP = ROOT / "_qa/emulator-run/compare-verify"

# (id, [(theme, mock_exists, app_exists)], заголовок)
SCREENS = [
    ("01-map-home", [("dark", True, True), ("light", True, True)], "01 · Главная карта"),
    # Главная карта (история 7.11) — по-темные склейки: id уже несёт тему,
    # сырьё — compare/_mock/01-map-home-<theme>.png и свежие шоты в
    # compare-verify/ (fallback-имена {sid}.png резолвятся в те же файлы).
    ("01-map-home-dark", [("dark", True, True)], "01 · Главная карта"),
    ("01-map-home-light", [("light", True, True)], "01 · Главная карта"),
    ("02-map-walk", [("dark", True, True)], "02 · Прогулка (запись)"),
    # Прогулка (история 7.12) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/02-map-walk-<theme>.png и свежие шоты в compare-verify/
    # (fallback-имена {sid}.png резолвятся в те же файлы).
    ("02-map-walk-dark", [("dark", True, True)], "02 · Прогулка (запись)"),
    ("02-map-walk-light", [("light", True, True)], "02 · Прогулка (запись)"),
    ("02p-map-walk-paused", [("dark", True, True)], "02п · Прогулка (пауза)"),
    # Пауза (история 7.12) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/02p-map-walk-paused-<theme>.png и свежие шоты в compare-verify/.
    ("02p-map-walk-paused-dark", [("dark", True, True)], "02п · Прогулка (пауза)"),
    # Пауза светлая (история 7.13, sweep) — по-темный id, сырьё —
    # compare/_mock/02p-map-walk-paused-light.png и compare-verify/ (7.13).
    ("02p-map-walk-paused-light", [("light", True, True)], "02п · Прогулка (пауза)"),
    ("03-walk-final", [("dark", True, True)], "03 · Финал прогулки"),
    # Финал (история 7.2) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/03-walk-final-<theme>.png и свежие шоты в compare-verify/
    # (fallback-имена {sid}.png резолвятся в те же файлы).
    ("03-walk-final-dark", [("dark", True, True)], "03 · Финал прогулки"),
    ("03-walk-final-light", [("light", True, True)], "03 · Финал прогулки"),
    ("04-onboarding", [("dark", True, True)], "04 · Онбординг"),
    # Пошаговая сверка онбординга (история 7.1): id уже несёт тему, сырьё —
    # compare/_mock/04-onboarding-<theme>-s<N>.png (рендер _qa/render-onboarding-mocks.py)
    # и _qa/emulator-run/compare-verify/04-onboarding-<theme>-s<N>.png (имена без -<theme>).
    ("04-onboarding-dark-s1", [("dark", True, True)], "04 · Онбординг · Шаг 1 — Приветствие"),
    ("04-onboarding-dark-s2", [("dark", True, True)], "04 · Онбординг · Шаг 2 — Домашний регион"),
    ("04-onboarding-dark-s3", [("dark", True, True)], "04 · Онбординг · Шаг 3 — Цель"),
    ("04-onboarding-dark-s4", [("dark", True, True)], "04 · Онбординг · Шаг 4 — Доступ к геолокации"),
    ("04-onboarding-light-s1", [("light", True, True)], "04 · Онбординг · Шаг 1 — Приветствие"),
    ("04-onboarding-light-s2", [("light", True, True)], "04 · Онбординг · Шаг 2 — Домашний регион"),
    ("04-onboarding-light-s3", [("light", True, True)], "04 · Онбординг · Шаг 3 — Цель"),
    ("04-onboarding-light-s4", [("light", True, True)], "04 · Онбординг · Шаг 4 — Доступ к геолокации"),
    ("05-progress-stats", [("dark", True, True), ("light", True, True)], "05 · Прогресс · Статистика"),
    # Статистика (история 7.4) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/05-progress-stats-<theme>.png и свежие шоты в compare-verify/
    # (fallback-имена {sid}.png резолвятся в те же файлы).
    ("05-progress-stats-dark", [("dark", True, True)], "05 · Прогресс · Статистика"),
    ("05-progress-stats-light", [("light", True, True)], "05 · Прогресс · Статистика"),
    ("06-progress-achievements", [("dark", True, True)], "06 · Прогресс · Достижения"),
    # Достижения (история 7.5) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/06-progress-achievements-<theme>.png и свежие шоты в compare-verify/
    # (fallback-имена {sid}.png резолвятся в те же файлы).
    ("06-progress-achievements-dark", [("dark", True, True)], "06 · Прогресс · Достижения"),
    ("06-progress-achievements-light", [("light", True, True)], "06 · Прогресс · Достижения"),
    ("07-progress-quests", [("dark", True, True)], "07 · Прогресс · Задания"),
    # Задания (история 7.6) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/07-progress-quests-<theme>.png и свежие шоты в compare-verify/
    # (fallback-имена {sid}.png резолвятся в те же файлы). Состояние — активное
    # задание (выдано во время прогулки по сценарию 7.6).
    ("07-progress-quests-dark", [("dark", True, True)], "07 · Прогресс · Задания"),
    ("07-progress-quests-light", [("light", True, True)], "07 · Прогресс · Задания"),
    ("08-rating", [("dark", True, True), ("light", True, True)], "08 · Рейтинг (первый вход)"),
    # Рейтинг (история 7.7) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/08-rating-<theme>.png (макет в состоянии «с ником») и свежие
    # шоты в compare-verify/ (fallback-имена {sid}.png резолвятся в те же файлы).
    ("08-rating-dark", [("dark", True, True)], "08 · Рейтинг"),
    ("08-rating-light", [("light", True, True)], "08 · Рейтинг"),
    ("09-profile", [("dark", True, True), ("light", True, True)], "09 · Профиль"),
    # Профиль (история 7.3) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/09-profile-<theme>.png и свежие шоты в compare-verify/
    # (fallback-имена {sid}.png резолвятся в те же файлы).
    ("09-profile-dark", [("dark", True, True)], "09 · Профиль"),
    ("09-profile-light", [("light", True, True)], "09 · Профиль"),
    ("10-regions", [("dark", True, True)], "10 · Мои регионы"),
    # Регионы (история 7.9) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/10-regions-<theme>.png и свежие шоты в compare-verify/
    # (fallback-имена {sid}.png резолвятся в те же файлы).
    ("10-regions-dark", [("dark", True, True)], "10 · Мои регионы"),
    ("10-regions-light", [("light", True, True)], "10 · Мои регионы"),
    ("11-settings", [("dark", True, True), ("light", True, True)], "11 · Настройки"),
    # Настройки (история 7.8) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/11-settings-<theme>.png и свежие кадры в compare-verify/.
    ("11-settings-dark", [("dark", True, True)], "11 · Настройки"),
    ("11-settings-light", [("light", True, True)], "11 · Настройки"),
    ("12-poi-card", [("dark", True, True)], "12 · Карточка POI"),
    # POI-карточка (история 7.10) — по-темные склейки: id уже несёт тему, сырьё —
    # compare/_mock/12-poi-card-<theme>.png (макет с полосой из 3 фото) и свежие
    # шоты в compare-verify/ (fallback-имена {sid}.png резолвятся в те же файлы).
    ("12-poi-card-dark", [("dark", True, True)], "12 · Карточка POI"),
    ("12-poi-card-light", [("light", True, True)], "12 · Карточка POI"),
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
    # argv[1] (опционально) — выходная папка для склеек; argv[2:] — фильтр id.
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else CMP
    only = set(sys.argv[2:])
    for sid, themes, title in SCREENS:
        if only and sid not in only:
            continue
        # готовим изображения строк
        rows = []
        for theme, has_mock, has_app in themes:
            mock_path = MOCK / f"{sid}-{theme}.png"
            app_path = APP / f"{sid}-{theme}.png"
            # Пошаговые id уже содержат тему: сырьё лежит без суффикса -<theme>.
            if not mock_path.exists():
                mock_path = MOCK / f"{sid}.png"
            if not app_path.exists():
                app_path = APP / f"{sid}.png"
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
        out = out_dir / f"{sid}.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        canvas.save(out, optimize=True)
        print(f"OK {out.name} {canvas.size}")


if __name__ == "__main__":
    main()
