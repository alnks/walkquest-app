# -*- coding: utf-8 -*-
"""Вырезка отдельных экранов из duo-макетов и рендер headless-Edge в PNG.

Использование: python _qa/compare-mock.py
На выходе: _bmad-output/initiative-walkquest/ux-walkquest/compare/_mock/<id>-<theme>.png
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MOCK = ROOT / "_bmad-output/initiative-walkquest/ux-walkquest/mockups"
OUT = ROOT / "_bmad-output/initiative-walkquest/ux-walkquest/compare/_mock"
OUT.mkdir(parents=True, exist_ok=True)

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# (id, файл, текст h2, индексы колонок [dark, light], ширина колонки, высота phone)
SCREENS = [
    ("01-map-home", "map-duo.html", "Главный экран", [0, 1], 272, 540),
    ("02-map-walk", "map-duo.html", "Прогулка", [0, 1], 272, 540),
    ("02p-map-walk-paused", "map-duo.html", "Прогулка — на паузе", [0, 1], 272, 540),
    ("03-walk-final", "map-duo.html", "Финал прогулки", [0, 1], 272, 540),
    ("04-onboarding", "onboarding-duo.html", "1. Приветствие", [0, 1], 262, 520),
    ("05-progress-stats", "progress-tasks-duo.html", "Прогресс · Статистика", [0, 1], 272, 540),
    ("06-progress-achievements", "progress-tasks-duo.html", "Прогресс · Достижения", [0, 1], 272, 540),
    ("07-progress-quests", "progress-tasks-duo.html", "Прогресс · Задания", [0, 1], 272, 540),
    ("08-rating", "remaining-screens.html", "Рейтинг — оба состояния, обе темы", [2, 3], 272, 520),
    ("09-profile", "remaining-screens.html", "Профиль", [0, 1], 272, 520),
    ("10-regions", "remaining-screens.html", "Регионы", [0, 1], 272, 520),
    ("11-settings", "remaining-screens.html", "Настройки (шестерёнка с главного)", [0, 1], 272, 520),
    ("12-poi-card", "remaining-screens.html", "Карточка POI", [0, 1], 272, 520),
]


def extract_cols(html: str, h2_text: str):
    """Найти сегмент после <h2> и вернуть список блоков <div class="col">...</div>."""
    m = re.search(r"<h2>\s*" + re.escape(h2_text) + r"\s*</h2>", html)
    if not m:
        raise SystemExit(f"h2 не найден: {h2_text!r}")
    seg = html[m.end():]
    nxt = re.search(r"<h2>", seg)
    if nxt:
        seg = seg[:nxt.start()]
    cols, i = [], 0
    while True:
        start = seg.find('<div class="col">', i)
        if start < 0:
            break
        depth, j = 0, start
        while True:
            dm = re.search(r"<div|</div>", seg[j:])
            if not dm:
                raise SystemExit("unbalanced div")
            tok = dm.group(0)
            j += dm.end()
            depth += 1 if tok == "<div" else -1
            if depth == 0:
                break
        cols.append(seg[start:j])
        i = j
    return cols


def main():
    style_cache = {}
    for sid, fname, h2, idxs, w, h in SCREENS:
        html = (MOCK / fname).read_text(encoding="utf-8")
        if fname not in style_cache:
            style_cache[fname] = re.search(r"<style>(.*?)</style>", html, re.S).group(1)
        style = style_cache[fname]
        cols = extract_cols(html, h2)
        for theme, idx in zip(("dark", "light"), idxs):
            col_html = cols[idx]
            # убрать подпись колонки («Тёмная»/«Светлая»)
            col_html = re.sub(r'<div class="col-name">.*?</div>', "", col_html, flags=re.S)
            page = (
                "<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"UTF-8\">"
                "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
                "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                "<link href=\"https://fonts.googleapis.com/css2?family=Nunito:wght@600;800;1000&family=DM+Sans:wght@400;500;700&display=swap\" rel=\"stylesheet\">"
                f"<style>{style}"
                "body{margin:0;padding:0;background:#100d18;}"
                f".wrap{{display:block;margin:0}}.col{{width:{w}px;margin:0}}</style></head>"
                f"<body><div class=\"wrap\">{col_html}</div></body></html>"
            )
            tmp = OUT / f"_tmp-{sid}-{theme}.html"
            tmp.write_text(page, encoding="utf-8")
            png = OUT / f"{sid}-{theme}.png"
            cmd = [
                EDGE, "--headless", "--disable-gpu", "--hide-scrollbars",
                "--force-device-scale-factor=2",
                f"--window-size={w},{h}",
                f"--screenshot={png}",
                tmp.as_uri(),
            ]
            r = subprocess.run(cmd, capture_output=True, timeout=90)
            if r.returncode != 0:
                print(f"FAIL {sid}-{theme}: {r.stderr.decode(errors='replace')[:300]}")
                continue
            tmp.unlink()
            print(f"OK   {png.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
