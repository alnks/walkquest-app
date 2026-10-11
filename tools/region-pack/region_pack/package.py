"""Упаковка: boundaries.geojson, poi.json, style.json, meta.json, region-{id}-v{n}.zip.

Решения по рискам spike 1.1:
1. pmtiles URL — плейсхолдер `pmtiles://tiles.pmtiles` в style.json; приложение при
   установке пакета подставляет абсолютный путь (MapLibre Native требует абсолютный).
   Конвенция зафиксирована в meta.json (pmtilesUrlPlaceholder) и README.
2. glyphs/sprite — стиль v1 без symbol-слоёв, шрифты и спрайты не нужны и не поставляются;
   добавление подписей = повышение formatVersion.
3. дороги рисуются по мотивам openstreetmap-carto (см. _road_layers): кожух+заливка
   по классам OpenMapTiles, туннели приглушены, тропинки/велодорожки — пунктиром;
   текстовых подписей по-прежнему нет (п. 2).
"""
import hashlib
import json
import os
import time
import zipfile

from shapely.geometry import mapping

FORMAT_VERSION = 1
PMTILES_URL_PLACEHOLDER = "pmtiles://tiles.pmtiles"

PACKAGE_FILES = ["style.json", "tiles.pmtiles", "walkability.grid", "water.grid",
                 "boundaries.geojson", "poi.json", "meta.json"]


def kind_of(admin_level):
    return ("country" if admin_level == 2 else
            "region" if admin_level == 4 else
            "city" if admin_level in (6, 8) else "district")


def write_boundaries(path, admin):
    feats = []
    for b in sorted(admin, key=lambda x: x["admin_level"]):
        feats.append({"type": "Feature",
                      "properties": {"admin_level": b["admin_level"],
                                     "name": b["name"],
                                     "kind": kind_of(b["admin_level"])},
                      "geometry": mapping(b["geometry"])})
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f, ensure_ascii=False)
    return len(feats)


def write_poi(path, pois):
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"pois": pois}, f, ensure_ascii=False)


def _zoom_width(stops):
    """Интерполяция ширины линии по зуму: [(z, w), ...] → expression."""
    expr = ["interpolate", ["linear"], ["zoom"]]
    for z, w in stops:
        expr.extend([z, w])
    return expr


def _zoom_width_add(stops, extra):
    return _zoom_width([(z, w + extra) for z, w in stops])


# Туннели (OpenMapTiles brunnel=tunnel) рисуются приглушённо, как в Carto.
_TUNNEL_OPACITY = ["case", ["==", ["get", "brunnel"], "tunnel"], 0.45, 1.0]


def _road_line(layer_id, filt, color, width, *, minzoom=None, dash=None):
    layer = {"id": layer_id, "type": "line", "source": "region",
             "source-layer": "transportation", "filter": filt,
             "layout": {"line-join": "round",
                        "line-cap": "butt" if dash else "round"},
             "paint": {"line-color": color, "line-width": width,
                       "line-opacity": _TUNNEL_OPACITY}}
    if minzoom is not None:
        layer["minzoom"] = minzoom
    if dash:
        layer["paint"]["line-dasharray"] = dash
    return layer


def _road_layers():
    """Дороги в духе openstreetmap-carto: кожух + заливка по классам, пунктирные
    тропинки. Классы — из схемы OpenMapTiles (planetiler): class ∈ motorway…path,
    subclass = исходный highway (footway/cycleway/bridleway/steps…), *_link
    свёрнуты в базовый класс. Порядок слоёв: младшие классы под старшими."""
    # (суффикс id, классы, заливка, кожух, ширина по зуму, добавка кожуха, minzoom)
    specs = [
        ("service", ["service"], "#ffffff", "#999999",
         [(14, 1.2), (16, 3.5)], 1.2, 13),
        ("minor", ["minor"], "#ffffff", "#b9b9b9",
         [(13, 1.6), (16, 5.0)], 1.4, None),
        ("tertiary", ["tertiary"], "#ffffff", "#8f8f8f",
         [(13, 1.8), (16, 6.0)], 1.4, None),
        ("secondary", ["secondary"], "#f7fabf", "#707d05",
         [(12, 1.8), (16, 7.0)], 1.6, None),
        ("primary", ["primary"], "#fcd6a4", "#a06b00",
         [(12, 2.0), (16, 8.0)], 1.6, None),
        ("trunk", ["trunk"], "#f9b29c", "#c84e2f",
         [(12, 2.4), (16, 9.0)], 1.8, None),
        ("motorway", ["motorway"], "#e892a2", "#dc2a2a",
         [(12, 2.8), (16, 10.0)], 1.8, None),
    ]
    layers = []
    for suffix, classes, fill, casing, stops, extra, mz in specs:
        filt = ["in", "class"] + classes
        layers.append(_road_line(f"road-casing-{suffix}", filt, casing,
                                 _zoom_width_add(stops, extra), minzoom=mz))
        layers.append(_road_line(f"road-fill-{suffix}", filt, fill,
                                 _zoom_width(stops), minzoom=mz))
    # грунтовые дороги — коричневый пунктир
    layers.append(_road_line("road-track", ["==", "class", "track"], "#996600",
                             _zoom_width([(13, 1.2), (16, 2.0)]),
                             minzoom=13, dash=[1.5, 1.2]))
    # ж/д — тёмная линия со светлой штриховкой
    layers.append(_road_line("road-rail", ["==", "class", "rail"], "#555555",
                             _zoom_width([(12, 1.0), (16, 2.5)]), minzoom=12))
    layers.append(_road_line("road-rail-hatching", ["==", "class", "rail"],
                             "#dddddd", _zoom_width([(12, 0.8), (16, 1.8)]),
                             minzoom=12, dash=[2.5, 2.5]))
    # паромы — синий пунктир
    layers.append(_road_line("road-ferry", ["==", "class", "ferry"], "#5b8bd0",
                             _zoom_width([(12, 1.2), (16, 2.0)]),
                             minzoom=12, dash=[2.0, 2.0]))
    # пешеходные пути (веб-Carto): велодорожки синие, конные — зелёные,
    # тропинки/тротуары/лестницы — «лососевый» пунктир
    path_stops = _zoom_width([(14, 1.2), (17, 2.2)])
    layers.append(_road_line(
        "road-bridleway",
        ["all", ["==", "class", "path"], ["==", "subclass", "bridleway"]],
        "#007a3d", path_stops, minzoom=13, dash=[2.0, 1.0]))
    layers.append(_road_line(
        "road-cycleway",
        ["all", ["==", "class", "path"], ["==", "subclass", "cycleway"]],
        "#1a5fb4", path_stops, minzoom=13, dash=[2.0, 1.0]))
    layers.append(_road_line(
        "road-path",
        ["all", ["==", "class", "path"],
         ["!", ["in", "subclass", "bridleway", "cycleway"]]],
        "#fa8072", path_stops, minzoom=13, dash=[1.5, 1.0]))
    return layers


def write_style(path, region_id):
    """Стиль под схему OpenMapTiles, без текстовых слоёв (см. шапку); дороги —
    по мотивам openstreetmap-carto (кожух+заливка, классы, пунктирные тропы)."""
    style = {
        "version": 8,
        "name": f"walkquest-{region_id}",
        "sources": {"region": {"type": "vector", "url": PMTILES_URL_PLACEHOLDER}},
        "layers": [
            {"id": "background", "type": "background",
             "paint": {"background-color": "#e8e4da"}},
            {"id": "water", "type": "fill", "source": "region", "source-layer": "water",
             "paint": {"fill-color": "#9ec9e0"}},
            {"id": "park", "type": "fill", "source": "region", "source-layer": "park",
             "paint": {"fill-color": "#b5d8a8", "fill-opacity": 0.7}},
            *_road_layers(),
            {"id": "buildings", "type": "fill", "source": "region",
             "source-layer": "building", "minzoom": 13,
             "paint": {"fill-color": "#d9d2c4"}},
        ],
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(style, f, ensure_ascii=False, indent=1)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_meta(path, *, region_id, version, tile_size_m, maxzoom, bbox,
               grid_header, poi_count, boundary_count, wiki_stats, package_dir,
               photo_stats=None, water_grid_header=None):
    meta = {
        "formatVersion": FORMAT_VERSION,
        "regionId": region_id,
        "packageVersion": version,
        "tileSizeMeters": tile_size_m,
        "maxzoom": maxzoom,
        "bbox": bbox,
        "grid": grid_header,
        "waterGrid": water_grid_header,
        "poiCount": poi_count,
        "boundaryCount": boundary_count,
        "wikipedia": wiki_stats,
        "photos": photo_stats or {"status": "skipped"},
        "pmtilesUrlPlaceholder": PMTILES_URL_PLACEHOLDER,
        "generatedBy": "tools/region-pack (story 1.3)",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "checksums": {fn: sha256(os.path.join(package_dir, fn))
                      for fn in PACKAGE_FILES if fn != "meta.json"},
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    return meta


def package_photos(package_dir):
    """Отсортированный список photos/*.jpg в каталоге пакета (может быть пустым)."""
    photos_dir = os.path.join(package_dir, "photos")
    if not os.path.isdir(photos_dir):
        return []
    return sorted(fn for fn in os.listdir(photos_dir)
                  if fn.startswith("poi-") and fn.endswith(".jpg"))


def make_zip(zip_path, package_dir):
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for fn in PACKAGE_FILES:
            z.write(os.path.join(package_dir, fn), fn)
        for fn in package_photos(package_dir):
            z.write(os.path.join(package_dir, "photos", fn), f"photos/{fn}")
    return zip_path
