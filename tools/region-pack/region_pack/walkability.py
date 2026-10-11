"""Сетки тайлов: проходимость (walkability.grid) и вода (water.grid), формат v0.

Правило walkability (spike 1.1, риск 3 — уточнено в 1.3): тайл проходим, если
  (через него проходит проходимая линия highway* ИЛИ его центр в walkable-полигоне
   — парк/сквер/заказник/площадь/пешеходная зона)
  И его центр НЕ в воде.
Дворы и частные access=private/no осознанно исключены; финальная калибровка — spike 1.11.

Правило water (пакет v3): тайл = вода, если его центр в водном полигоне
(те же источники, что исключают воду из walkability). См. build_water_mask.

Бинарь v0 (не менять — потребляется историями 1.5/1.6 и приложением):
  строка 1: JSON-заголовок {cols, rows, tileSizeMeters, origin, order,
            walkableCount | waterCount}
  далее: row-major битпак, lsb-first, 1 бит = тайл.
"""
import json
import math
import os

import numpy as np
from shapely import contains_xy
from shapely.ops import unary_union


def _sample_lines(walk_lines, step_deg_lon, step_deg_lat):
    """Дробим сегменты так, чтобы соседние точки сэмпла не пропускали тайлы."""
    xs, ys = [], []
    for line in walk_lines:
        for (lon1, lat1), (lon2, lat2) in zip(line, line[1:]):
            n = max(1, int(max(abs(lon2 - lon1) / step_deg_lon,
                               abs(lat2 - lat1) / step_deg_lat) * 2) + 1)
            xs.extend(np.linspace(lon1, lon2, n))
            ys.extend(np.linspace(lat1, lat2, n))
    return xs, ys


def _grid_geometry(bbox, tile_size_m):
    """Геометрия сетки тумана: (dx, dy, cols, rows, centers_x, centers_y)."""
    minlon, minlat, maxlon, maxlat = bbox
    dy = tile_size_m / 111320.0
    dx = tile_size_m / (111320.0 * math.cos(math.radians((minlat + maxlat) / 2)))
    cols = math.ceil((maxlon - minlon) / dx)
    rows = math.ceil((maxlat - minlat) / dy)
    centers_x = minlon + (np.arange(cols) + 0.5) * dx
    centers_y = minlat + (np.arange(rows) + 0.5) * dy
    return dx, dy, cols, rows, centers_x, centers_y


def _water_grid(collector, cols, rows, centers_x, centers_y):
    """Булева маска «центр тайла в воде» (flat, row-major); None, если воды нет."""
    if not collector.water_polys:
        return None
    water = unary_union(collector.water_polys)
    if water.is_empty:
        return None
    gx, gy = np.meshgrid(centers_x, centers_y)
    return contains_xy(water, gx.ravel(), gy.ravel())


def build_water_mask(collector, bbox, tile_size_m):
    """Маска воды (water.grid, формат v0 — тот же, что walkability.grid).

    Правило (пакет v3): тайл = вода, если его центр в водном полигоне —
    те же источники, что исключают воду из walkability (natural=water/bay,
    waterway=riverbank, landuse=reservoir/harbour/basin). Приложение читает
    файл опционально: вода видна сквозь туман как ориентир.
    Возвращает (header: dict, packed: bytes, stats: dict).
    Поле счётчика заголовка — waterCount (аналог walkableCount).
    """
    minlon, minlat = bbox[0], bbox[1]
    dx, dy, cols, rows, centers_x, centers_y = _grid_geometry(bbox, tile_size_m)

    in_water = _water_grid(collector, cols, rows, centers_x, centers_y)
    grid = in_water if in_water is not None else np.zeros(rows * cols, dtype=bool)

    packed = np.packbits(grid, bitorder="little").tobytes()
    water = int(grid.sum())
    header = {"cols": int(cols), "rows": int(rows), "tileSizeMeters": tile_size_m,
              "origin": [minlon, minlat], "order": "row-major, lsb-first",
              "waterCount": water}
    stats = {"tiles": int(rows * cols), "water": water,
             "waterPct": round(100.0 * water / (rows * cols), 2)}
    return header, packed, stats


def build_grid(collector, bbox, tile_size_m):
    """Возвращает (header: dict, packed: bytearray, stats: dict)."""
    minlon, minlat, maxlon, maxlat = bbox
    dx, dy, cols, rows, centers_x, centers_y = _grid_geometry(bbox, tile_size_m)

    grid = np.zeros(rows * cols, dtype=bool)

    # 1. проходимые линии (сэмплирование сегментов)
    xs, ys = _sample_lines(collector.walk_lines, dx, dy)
    if xs:
        c = ((np.asarray(xs) - minlon) / dx).astype(np.int64)
        r = ((np.asarray(ys) - minlat) / dy).astype(np.int64)
        ok = (r >= 0) & (r < rows) & (c >= 0) & (c < cols)
        grid[r[ok] * cols + c[ok]] = True

    # 2. центры тайлов в walkable-полигонах (парки/площади/пешеходные зоны)
    if collector.walk_polys:
        walk_poly = unary_union(collector.walk_polys)
        if not walk_poly.is_empty:
            gx, gy = np.meshgrid(centers_x, centers_y)
            inside = contains_xy(walk_poly, gx.ravel(), gy.ravel())
            grid |= inside

    # 3. вода исключает (центр в воде → непроходим)
    water_excluded = 0
    in_water = _water_grid(collector, cols, rows, centers_x, centers_y)
    if in_water is not None:
        water_excluded = int((grid & in_water).sum())
        grid &= ~in_water

    packed = np.packbits(grid, bitorder="little").tobytes()
    walkable = int(grid.sum())
    header = {"cols": int(cols), "rows": int(rows), "tileSizeMeters": tile_size_m,
              "origin": [minlon, minlat], "order": "row-major, lsb-first",
              "walkableCount": walkable}
    stats = {"tiles": int(rows * cols), "walkable": walkable,
             "walkablePct": round(100.0 * walkable / (rows * cols), 2),
             "waterExcluded": water_excluded}
    return header, packed, stats


def write_grid(path, header, packed):
    with open(path, "wb") as f:
        f.write((json.dumps(header) + "\n").encode("utf-8"))
        f.write(packed)


def read_grid(path):
    """Roundtrip-чтение grid v0: (header, np.ndarray bool)."""
    with open(path, "rb") as f:
        header = json.loads(f.readline().decode("utf-8"))
        data = f.read()
    n = header["cols"] * header["rows"]
    grid = np.unpackbits(np.frombuffer(data, dtype=np.uint8),
                         count=n, bitorder="little").astype(bool)
    return header, grid


def render_preview(path_png, header, grid, collector, bbox):
    """Превью как в PoC: вода синим, walkable-полигоны зелёным, проходимые тайлы — точками."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from shapely.ops import unary_union

    minlon, minlat, maxlon, maxlat = bbox
    cols, rows = header["cols"], header["rows"]
    img = grid.reshape(rows, cols)

    fig, ax = plt.subplots(figsize=(12, max(4, 12 * rows / max(cols, 1))))
    ax.set_xlim(minlon, maxlon)
    ax.set_ylim(minlat, maxlat)
    if collector.water_polys:
        water = unary_union(collector.water_polys)
        for geom in getattr(water, "geoms", [water]):
            x, y = geom.exterior.xy
            ax.fill(x, y, color="#9ec9e0", zorder=1)
    if collector.walk_polys:
        parks = unary_union(collector.walk_polys)
        for geom in getattr(parks, "geoms", [parks]):
            x, y = geom.exterior.xy
            ax.fill(x, y, color="#b5d8a8", zorder=2)
    dx = (maxlon - minlon) / cols
    dy = (maxlat - minlat) / rows
    r, c = np.nonzero(img)
    ax.scatter(minlon + (c + 0.5) * dx, minlat + (r + 0.5) * dy,
               s=1, c="#333333", zorder=3)
    ax.set_title(f"walkability: {int(img.sum())} / {rows * cols} тайлов "
                 f"({header['tileSizeMeters']} м)")
    ax.set_aspect(1 / math.cos(math.radians((minlat + maxlat) / 2)))
    fig.tight_layout()
    fig.savefig(path_png, dpi=110)
    plt.close(fig)


def render_water_preview(path_png, header, grid, collector, bbox):
    """Превью маски воды (пакет v3): водные тайлы — синие точки поверх
    светлых водных полигонов-источников, суша не закрашивается."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from shapely.ops import unary_union

    minlon, minlat, maxlon, maxlat = bbox
    cols, rows = header["cols"], header["rows"]
    img = grid.reshape(rows, cols)

    fig, ax = plt.subplots(figsize=(12, max(4, 12 * rows / max(cols, 1))))
    ax.set_xlim(minlon, maxlon)
    ax.set_ylim(minlat, maxlat)
    ax.set_facecolor("#f2f0ea")  # суша — светлый фон
    if collector.water_polys:
        water = unary_union(collector.water_polys)
        for geom in getattr(water, "geoms", [water]):
            x, y = geom.exterior.xy
            ax.fill(x, y, color="#c5dcec", zorder=1)
    dx = (maxlon - minlon) / cols
    dy = (maxlat - minlat) / rows
    r, c = np.nonzero(img)
    ax.scatter(minlon + (c + 0.5) * dx, minlat + (r + 0.5) * dy,
               s=2, c="#1f6fb4", zorder=3)
    ax.set_title(f"water.grid: {int(img.sum())} / {rows * cols} тайлов-вода "
                 f"({header['tileSizeMeters']} м)")
    ax.set_aspect(1 / math.cos(math.radians((minlat + maxlat) / 2)))
    fig.tight_layout()
    fig.savefig(path_png, dpi=110)
    plt.close(fig)
