#!/usr/bin/env python3
"""check-fog-render — проверки рендера тумана WalkQuest по скриншоту.

Формализует ручную методику прогонов fix7–fix10 (замер ширины просвета на
сканлайне, контраст завесы) в команду с порогами и кодом выхода — чтобы
рендер-регрессии типа D2 (кольца-не-дырки) и «завеса слилась с базой» (fix9)
ловились одной командой, а не глазами.

Что измеряет:
  * цвет завесы — медиана угловых проб скриншота (там всегда туман);
  * просвет(ы) — не-туманные участки на сканлайне (и ±N строк вокруг),
    самый длинный считается «тропой»;
  * покрытие завесой — доля пикселей цвета завесы в кадре.

Примеры:
  python _qa/check-fog-render.png shot.png --report
  python _qa/check-fog-render.py shot.png --expect-gap-px 43 --tolerance-px 8
  python _qa/check-fog-render.py shot.png --expect-veil-min 0.60
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path


def veil_color(im, inset: int = 10) -> tuple[int, int, int]:
    """Цвет завесы — доминантный цвет кадра: завеса занимает большую часть
    экрана карты, а углы ненадёжны (статус-бар, навбар, плашки UI).
    Гистограмма по квантованным корзинам (шаг 8), цвет — среднее корзины."""
    w, h = im.size
    px = im.load()
    counts: dict[tuple[int, int, int], int] = {}
    acc: dict[tuple[int, int, int], list[int]] = {}
    for y in range(inset, h - inset, 3):
        for x in range(inset, w - inset, 3):
            p = px[x, y]
            key = (p[0] // 8, p[1] // 8, p[2] // 8)
            counts[key] = counts.get(key, 0) + 1
            s = acc.setdefault(key, [0, 0, 0])
            s[0] += p[0]; s[1] += p[1]; s[2] += p[2]
    top = max(counts, key=counts.get)
    s = acc[top]
    n = counts[top]
    return (s[0] // n, s[1] // n, s[2] // n)


def scanline_gap(im, y: int, veil: tuple[int, int, int], tolerance: int) -> tuple[int, list]:
    """Самый длинный не-туманный прогон на строке y и все прогоны."""
    w = im.size[0]
    px = im.load()
    runs, start = [], None
    for x in range(w):
        p = px[x, y]
        is_veil = max(abs(p[i] - veil[i]) for i in range(3)) <= tolerance
        if not is_veil and start is None:
            start = x
        elif is_veil and start is not None:
            runs.append((start, x - 1, x - start))
            start = None
    if start is not None:
        runs.append((start, w - 1, w - start))
    gap = max((r[2] for r in runs), default=0)
    return gap, runs


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("screenshot")
    ap.add_argument("--line-y", type=float, default=0.5,
                    help="сканлайн: доля высоты (default 0.5)")
    ap.add_argument("--veil-tolerance", type=int, default=28,
                    help="допуск канала для «это завеса» (default 28; перо/ореол шире)")
    ap.add_argument("--band", type=int, default=5,
                    help="сканировать ±N строк вокруг line-y, медиана (default 5)")
    ap.add_argument("--expect-gap-px", type=int, default=None,
                    help="ожидаемая ширина просвета, px (лесенка радиусов: 43/65/87 на зуме ~16)")
    ap.add_argument("--tolerance-px", type=int, default=8,
                    help="допуск к --expect-gap-px (default 8)")
    ap.add_argument("--expect-veil-min", type=float, default=None,
                    help="минимальная доля пикселей завесы в кадре, 0..1")
    ap.add_argument("--expect-veil-max", type=float, default=None,
                    help="максимальная доля завесы (ловит «туман не гаснет», D2)")
    ap.add_argument("--report", action="store_true", help="печатать JSON-отчёт")
    args = ap.parse_args()

    try:
        from PIL import Image
    except ImportError:
        sys.exit("FAIL: нужен Pillow (в managed-python предустановлен).")

    path = Path(args.screenshot)
    if not path.is_file():
        sys.exit(f"FAIL: файл не найден: {path}")
    im = Image.open(path).convert("RGB")
    w, h = im.size
    veil = veil_color(im)

    gaps, all_runs = [], []
    center = int(h * args.line_y)
    for y in range(center - args.band, center + args.band + 1):
        g, runs = scanline_gap(im, y, veil, args.veil_tolerance)
        gaps.append(g)
        all_runs.extend(runs)
    gap = int(statistics.median(gaps))

    px = im.load()
    veil_px = sum(
        1 for yy in range(0, h, 4) for xx in range(0, w, 4)
        if max(abs(px[xx, yy][i] - veil[i]) for i in range(3)) <= args.veil_tolerance
    )
    sampled = len(range(0, h, 4)) * len(range(0, w, 4))
    coverage = veil_px / sampled

    report = {
        "file": str(path), "size": [w, h], "veil_color": list(veil),
        "gap_px": gap, "veil_coverage": round(coverage, 4),
        "runs_on_center_line": [r for r in all_runs if r[2] >= 3][:10],
    }
    if args.report or not (args.expect_gap_px or args.expect_veil_min or args.expect_veil_max):
        print(json.dumps(report, ensure_ascii=False, indent=2))

    failures = []
    if args.expect_gap_px is not None and abs(gap - args.expect_gap_px) > args.tolerance_px:
        failures.append(f"просвет {gap}px вне {args.expect_gap_px}±{args.tolerance_px}px")
    if args.expect_veil_min is not None and coverage < args.expect_veil_min:
        failures.append(f"покрытие завесой {coverage:.3f} < {args.expect_veil_min}")
    if args.expect_veil_max is not None and coverage > args.expect_veil_max:
        failures.append(f"покрытие завесой {coverage:.3f} > {args.expect_veil_max} "
                        f"(туман не гаснет? регрессия D2)")

    if failures:
        print("FAIL: " + "; ".join(failures))
        sys.exit(1)
    print(f"PASS: gap={gap}px, завеса={coverage:.1%}, цвет={veil}")


if __name__ == "__main__":
    main()
