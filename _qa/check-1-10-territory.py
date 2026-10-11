#!/usr/bin/env python3
"""Зеркало модели территории 1.10 против фактических артефактов обоих пакетов — проверка без SDK.

Проверяет (аналог _qa/check-1-9-poi.py):
  1. Контракт boundaries.geojson обоих пакетов (spb-city + gatchina): FeatureCollection,
     kind/admin_level, геометрии Polygon/MultiPolygon — то, что парсит data/boundaries.
  2. Семантика point-in-polygon (ray casting, дырки, multipolygon) — Python-зеркало
     алгоритма из domain/territory/PointInPolygon.kt — на синтетике и на фактических
     границах, с shapely как независимым оракулом (зеркало == оракул на сетке точек).
  3. Принадлежность центров городов: центр СПб — в границах spb-city и вне gatchina,
     центр Гатчины — в границах gatchina и вне spb-city (AD-9: Гатчина — не часть СПб).
  4. Покрытие: доля проходимых тайлов сетки (walkability.grid), попавших в полигоны
     региона — контроль, что границы реально покрывают сетку тумана.
  5. Мульти-регион в коде: OpenTileAtPoint ищет регион через RegionLocator (не константа),
     ComputeRegionProgress.compute(regionId), кэш манифеста (MANIFEST_CACHE_FILE +
     cachedManifest + опциональные bbox/name в ManifestPackage), настройки settings.json.
  6. UI: вкладка «Регионы» (RegionsScreen/RegionsViewModel), карточка предложения
     на MapScreen, токены rg-pct (AccentTextColor/MutedLavenderTextColor).
  7. AD-10 grep: domain/territory, data/boundaries, data/settings без android/androidx/maplibre.
  8. Баланс скобок новых/изменённых Kotlin-файлов.
"""
import json
import random
import re
import sys
import zipfile
from pathlib import Path

from shapely.geometry import Point, shape
from shapely.prepared import prep

ROOT = Path(__file__).resolve().parent.parent
ZIP_SPB = ROOT / "tools/region-pack/out/region-spb-city-v1.zip"
ZIP_GATCHINA = ROOT / "tools/region-pack/out/region-gatchina-v1.zip"
APP = ROOT / "app/src/main/java/app/walkquest"
TEST = ROOT / "app/src/test/kotlin/app/walkquest"

# Точки городов (lat, lon).
SPB_CENTER = (59.9343, 30.3351)   # Дворцовая площадь
GATCHINA_CENTER = (59.5653, 30.1282)  # центр Гатчины

passed = failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"PASS  {name}")
    else:
        failed += 1
        print(f"FAIL  {name}  {detail}")


def read_zip(zip_path):
    with zipfile.ZipFile(zip_path) as z:
        boundaries = json.loads(z.read("boundaries.geojson"))
        meta = json.loads(z.read("meta.json"))
        grid_raw = z.read("walkability.grid")
    newline = grid_raw.index(b"\n")
    grid_header = json.loads(grid_raw[:newline])
    grid_payload = grid_raw[newline + 1:]
    return boundaries, meta, grid_header, grid_payload


# --- Python-зеркало алгоритма PointInPolygon.kt (ray casting, луч +∞ по lon) ---------
def ring_contains(lat, lon, ring):
    n = len(ring) // 2
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = ring[2 * i], ring[2 * i + 1]
        xj, yj = ring[2 * j], ring[2 * j + 1]
        if _on_segment(lat, lon, xi, yi, xj, yj):
            return True
        if (yi > lat) != (yj > lat):
            cross_lon = xi + (lat - yi) / (yj - yi) * (xj - xi)
            if lon < cross_lon:
                inside = not inside
        j = i
    return inside


def _on_segment(lat, lon, x1, y1, x2, y2):
    eps = 1e-9
    cross = (lat - y1) * (x2 - x1) - (lon - x1) * (y2 - y1)
    if abs(cross) > eps:
        return False
    return (min(x1, x2) - eps <= lon <= max(x1, x2) + eps
            and min(y1, y2) - eps <= lat <= max(y1, y2) + eps)


def polygon_contains(lat, lon, rings):
    if not ring_contains(lat, lon, rings[0]):
        return False
    for hole in rings[1:]:
        if ring_contains(lat, lon, hole):
            return False
    return True


def flatten_rings(ring_coords):
    flat = []
    for lon, lat in ring_coords:
        flat.extend((lon, lat))
    return flat


def geojson_polygons(boundaries):
    """FeatureCollection → список (bbox, кольца); bbox — дешёвый префильтр, как в RegionLocator."""
    polygons = []
    for feature in boundaries["features"]:
        geom = feature.get("geometry") or {}
        coords = geom.get("coordinates") or []
        parts = []
        if geom.get("type") == "Polygon":
            parts.append(coords)
        elif geom.get("type") == "MultiPolygon":
            parts.extend(coords)
        for poly in parts:
            rings = [flatten_rings(r) for r in poly]
            lons = rings[0][0::2]
            lats = rings[0][1::2]
            polygons.append(((min(lons), min(lats), max(lons), max(lats)), rings))
    return polygons


def contains_any(lat, lon, polygons):
    for (min_lon, min_lat, max_lon, max_lat), rings in polygons:
        if min_lon <= lon <= max_lon and min_lat <= lat <= max_lat:
            if polygon_contains(lat, lon, rings):
                return True
    return False


def shapely_geoms(boundaries):
    """Разобранные геометрии + prepared-версии (быстрые повторные contains)."""
    geoms = []
    for feature in boundaries["features"]:
        geom = feature.get("geometry")
        if geom and geom.get("type") in ("Polygon", "MultiPolygon"):
            geoms.append(prep(shape(geom)))
    return geoms


def shapely_contains_any(lat, lon, geoms):
    pt = Point(lon, lat)
    return any(g.covers(pt) for g in geoms)


# --- 0. оба пакета на месте ------------------------------------------------------------
check("пакет второго региона сгенерирован (region-gatchina-v1.zip)", ZIP_GATCHINA.is_file())

b_spb, meta_spb, grid_spb, payload_spb = read_zip(ZIP_SPB)
if ZIP_GATCHINA.is_file():
    b_gat, meta_gat, grid_gat, payload_gat = read_zip(ZIP_GATCHINA)
else:
    # Заглушки: пакет-зависимые проверки Гатчины честно падают (features=0, нет покрытия),
    # код-проверки (5–8) всё равно выполняются.
    b_gat = {"type": "FeatureCollection", "features": []}
    meta_gat = {"bbox": [0.0, 0.0, 0.0, 0.0]}
    grid_gat = {"cols": 1, "rows": 1, "tileSizeMeters": 100.0, "origin": [0.0, 0.0]}
    payload_gat = b"\x00"

# --- 1. контракт boundaries.geojson ---------------------------------------------------
for name, b, meta in (("spb-city", b_spb, meta_spb), ("gatchina", b_gat, meta_gat)):
    feats = b["features"]
    kinds = {f["properties"].get("kind") for f in feats}
    check(f"{name}: boundaries.geojson — FeatureCollection с границами",
          b["type"] == "FeatureCollection" and len(feats) > 0, f"features={len(feats)}")
    check(f"{name}: kind из словаря генератора (country/region/city/district)",
          kinds <= {"country", "region", "city", "district"}, f"kinds={kinds}")
    check(f"{name}: геометрии только Polygon/MultiPolygon",
          all(f["geometry"]["type"] in ("Polygon", "MultiPolygon") for f in feats))
    check(f"{name}: meta.json несёт bbox из 4 чисел",
          len(meta.get("bbox", [])) == 4 and all(isinstance(v, (int, float)) for v in meta["bbox"]))

check("gatchina: регион меньше СПб (малый тестовый регион)",
      (meta_gat["bbox"][2] - meta_gat["bbox"][0]) < (meta_spb["bbox"][2] - meta_spb["bbox"][0]),
      f"{meta_gat['bbox']} vs {meta_spb['bbox']}")

# --- 2. зеркало PIP: синтетика + оракул shapely ---------------------------------------
sq = [[10.0, 50.0, 20.0, 50.0, 20.0, 60.0, 10.0, 60.0, 10.0, 50.0]]
sq_hole = [sq[0], [14.0, 54.0, 16.0, 54.0, 16.0, 56.0, 14.0, 56.0, 14.0, 54.0]]
check("зеркало PIP: внутри квадрата", polygon_contains(55.0, 15.0, sq))
check("зеркало PIP: снаружи квадрата", not polygon_contains(55.0, 25.0, sq))
check("зеркало PIP: дырка вычитается", not polygon_contains(55.0, 15.0, sq_hole))
check("зеркало PIP: между краем и дыркой — внутри", polygon_contains(52.0, 15.0, sq_hole))
check("зеркало PIP: точка на ребре — внутри", polygon_contains(55.0, 10.0, sq))

poly_spb = geojson_polygons(b_spb)
poly_gat = geojson_polygons(b_gat)
geom_spb = shapely_geoms(b_spb)
geom_gat = shapely_geoms(b_gat)

random.seed(110)
mismatch = 0
for name, polys, geoms, meta in (("spb-city", poly_spb, geom_spb, meta_spb),
                                 ("gatchina", poly_gat, geom_gat, meta_gat)):
    min_lon, min_lat, max_lon, max_lat = meta["bbox"]
    for _ in range(300):
        lat = random.uniform(min_lat - 0.05, max_lat + 0.05)
        lon = random.uniform(min_lon - 0.05, max_lon + 0.05)
        if contains_any(lat, lon, polys) != shapely_contains_any(lat, lon, geoms):
                mismatch += 1
check("зеркало PIP == оракул shapely на 600 случайных точках фактических границ",
      mismatch == 0, f"расхождений: {mismatch}")

# --- 3. принадлежность центров городов (AD-9) ------------------------------------------
# Семантика RegionLocator: bbox-префильтр пакета + PIP. Без префильтра сырой PIP по
# границам gatchina матчит центр СПб: в boundaries.geojson попало отношение «Санкт-Петербург»
# (admin_level 4), чья геометрия простирается далеко за bbox пакета — это штатно, регион
# пакета = его границы ∩ его bbox (сетка тумана живёт только в bbox).
def locate(lat, lon, regions):
    """Зеркало RegionLocator.locate: regions = [(regionId, meta_bbox, polygons)]."""
    for region_id, bbox, polygons in regions:
        if not (bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3]):
            continue
        if not polygons or contains_any(lat, lon, polygons):
            return region_id
    return None


REGIONS = [("spb-city", meta_spb["bbox"], poly_spb), ("gatchina", meta_gat["bbox"], poly_gat)]

check("центр СПб — в границах spb-city",
      contains_any(*SPB_CENTER, poly_spb))
check("центр СПб — ВНЕ региона gatchina (bbox-префильтр + полигоны)",
      locate(*SPB_CENTER, [("gatchina", meta_gat["bbox"], poly_gat)]) is None)
check("центр Гатчины — в границах gatchina",
      contains_any(*GATCHINA_CENTER, poly_gat))
check("центр Гатчины — ВНЕ региона spb-city (Гатчина — не часть СПб, AD-9)",
      locate(*GATCHINA_CENTER, [("spb-city", meta_spb["bbox"], poly_spb)]) is None)
check("локатор по двум регионам: центры относятся к своим регионам",
      locate(*SPB_CENTER, REGIONS) == "spb-city" and locate(*GATCHINA_CENTER, REGIONS) == "gatchina")

# --- 4. покрытие сетки тумана полигонами -----------------------------------------------
def grid_coverage(meta, grid_header, payload, polygons, max_samples=2000):
    """Доля проходимых тайлов (бит 1 в маске), чьи центры внутри полигонов региона.
    Выборка с шагом (≤ max_samples тайлов) — полный перебор 90k тайлов × десятки
    полигонов в чистом Python слишком медленный для прогона без SDK."""
    cols, rows = grid_header["cols"], grid_header["rows"]
    tile_m = grid_header["tileSizeMeters"]
    origin_lon, origin_lat = grid_header["origin"]
    min_lon, min_lat, max_lon, max_lat = meta["bbox"]
    import math
    dy = tile_m / 111320.0
    dx = tile_m / (111320.0 * math.cos(math.radians((min_lat + max_lat) / 2.0)))
    walkable_idx = [idx for idx in range(cols * rows)
                    if (payload[idx // 8] >> (idx % 8)) & 1]
    if not walkable_idx:
        return 0, 0
    step = max(1, len(walkable_idx) // max_samples)
    sample = walkable_idx[::step]
    covered = 0
    for idx in sample:
        row, col = divmod(idx, cols)
        lat = origin_lat + (row + 0.5) * dy
        lon = origin_lon + (col + 0.5) * dx
        if contains_any(lat, lon, polygons):
            covered += 1
    return covered, len(sample)


for name, meta, gh, payload, polys in (("spb-city", meta_spb, grid_spb, payload_spb, poly_spb),
                                       ("gatchina", meta_gat, grid_gat, payload_gat, poly_gat)):
    covered, sampled = grid_coverage(meta, gh, payload, polys)
    ratio = covered / sampled if sampled else 0.0
    check(f"{name}: полигоны покрывают ≥ 60% проходимых тайлов сетки "
          f"(выборка {covered}/{sampled} = {ratio:.0%})",
          sampled > 0 and ratio >= 0.6, f"ratio={ratio:.2%}")

# --- 5. мульти-регион в коде ------------------------------------------------------------
open_kt = (APP / "domain/OpenTileAtPoint.kt").read_text(encoding="utf-8")
check("OpenTileAtPoint: регион точки — через RegionLocator (границы пакетов, AD-9), не константа",
      "RegionLocator.locate" in open_kt and "installedPackages()" in open_kt
      and "HOME_REGION_ID" not in open_kt)
check("OpenTileAtPoint: бит открывается в биткарту найденного региона",
      "fogRepository.open(regionId" in open_kt)

progress_kt = (APP / "domain/FogProgress.kt").read_text(encoding="utf-8")
check("ComputeRegionProgress: регион — параметр compute(regionId), формула 1.8 не тронута",
      "fun compute(regionId: String)" in progress_kt and "walkableMask.get(it)" in progress_kt)

repo_kt = (APP / "data/packages/RegionPackageRepository.kt").read_text(encoding="utf-8")
check("кэш манифеста: запись после скачивания + cachedManifest() только с диска (AD-2)",
      "MANIFEST_CACHE_FILE" in repo_kt and "cachedManifest()" in repo_kt)
models_kt = (APP / "data/packages/PackageModels.kt").read_text(encoding="utf-8")
check("ManifestPackage: опциональные bbox и name (обратная совместимость манифеста 1.4)",
      "val bbox: List<Double>? = null" in models_kt and "val name: String? = null" in models_kt)

detect_kt = (APP / "domain/territory/DetectForeignRegion.kt").read_text(encoding="utf-8")
check("DetectForeignRegion: установленные не предлагаются, совпадение — по bbox манифест-кэша",
      "cachedManifest()" in detect_kt and "RegionLocator.locate" in detect_kt)

settings_kt = (APP / "data/settings/SettingsRepository.kt").read_text(encoding="utf-8")
check("домашний регион: settings.json formatVersion + homeRegionId, атомарная запись",
      '"settings.json"' in settings_kt and "homeRegionId" in settings_kt
      and "renameTo" in settings_kt)

pip_kt = (APP / "domain/territory/PointInPolygon.kt").read_text(encoding="utf-8")
check("PointInPolygon.kt: ray casting + HomeRegionPolicy (сохранённый → первый → null)",
      "ringContains" in pip_kt and "HomeRegionPolicy" in pip_kt)

service_kt = (APP / "tracking/TrackingService.kt").read_text(encoding="utf-8")
bus_kt = (APP / "tracking/TrackingStateBus.kt").read_text(encoding="utf-8")
check("трекинг 1.7 не сломан: точка по-прежнему идёт в openAt; детект — при OutOfRegion",
      "openTileAtPoint" in service_kt and ".openAt(" in service_kt
      and "detectForeignRegion" in service_kt and "OutOfRegion" in service_kt)
check("шина: suggestedRegionId в снимке, сброс на recordingStarted",
      "suggestedRegionId" in bus_kt and "recordingStarted" in bus_kt)

# --- 6. UI: вкладка и карточка ----------------------------------------------------------
regions_screen = APP / "ui/regions/RegionsScreen.kt"
check("вкладка «Регионы»: экран + viewmodel существуют",
      regions_screen.is_file() and (APP / "viewmodel/RegionsViewModel.kt").is_file())
rs = regions_screen.read_text(encoding="utf-8")
check("строки региона: «домашний регион», процент домашнего акцентом, чужого — лавандовым",
      "домашний регион" in rs and "AccentTextColor" in rs and "MutedLavenderTextColor" in rs)
check("форматирование процента только на UI (ru-локаль, запятая)",
      'Locale.forLanguageTag("ru-RU")' in rs)

theme_kt = (APP / "ui/theme/Theme.kt").read_text(encoding="utf-8")
check("токены rg-pct: MutedLavenderTextColor #B3A6C9 тёмная / #8D83A6 светлая",
      "0xFFB3A6C9" in theme_kt and "0xFF8D83A6" in theme_kt)

map_screen = (APP / "ui/map/MapScreen.kt").read_text(encoding="utf-8")
check("карточка предложения региона на карте: Загрузить/Позже + состояние загрузки",
      "RegionSuggestionCard" in map_screen and "onInstallSuggested" in map_screen
      and "onDismissSuggestion" in map_screen)

main_kt = (APP / "MainActivity.kt").read_text(encoding="utf-8")
check("две вкладки material3 (Карта/Регионы), переход к карте региона из списка",
      "NavigationBar" in main_kt and "onRegionSelected" in main_kt)

container_kt = (APP / "di/AppContainer.kt").read_text(encoding="utf-8")
check("AppContainer: HOME_REGION_ID удалён, проводка boundary/settings/detect",
      "HOME_REGION_ID" not in container_kt and "BoundaryRepository" in container_kt
      and "SettingsRepository" in container_kt and "DetectForeignRegion" in container_kt)

# --- 7. AD-10 grep ----------------------------------------------------------------------
ANDROID_RE = re.compile(r"import\s+(android\.|androidx\.|org\.maplibre\.)")
violations = []
for sub in ("domain/territory", "data/boundaries", "data/settings"):
    for f in sorted((APP / sub).glob("*.kt")):
        for line_no, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if ANDROID_RE.search(line):
                violations.append(f"{f.name}:{line_no}")
check("AD-10: domain/territory, data/boundaries, data/settings без android/androidx/maplibre",
      not violations, "; ".join(violations))

# --- 8. баланс скобок Kotlin -------------------------------------------------------------
CHANGED = [
    APP / "domain/territory/PointInPolygon.kt",
    APP / "domain/territory/DetectForeignRegion.kt",
    APP / "domain/OpenTileAtPoint.kt",
    APP / "domain/FogProgress.kt",
    APP / "data/boundaries/BoundaryRepository.kt",
    APP / "data/settings/SettingsRepository.kt",
    APP / "data/packages/PackageModels.kt",
    APP / "data/packages/RegionPackageRepository.kt",
    APP / "tracking/TrackingStateBus.kt",
    APP / "tracking/TrackingService.kt",
    APP / "viewmodel/MapViewModel.kt",
    APP / "viewmodel/RegionsViewModel.kt",
    APP / "ui/map/MapScreen.kt",
    APP / "ui/regions/RegionsScreen.kt",
    APP / "ui/theme/Theme.kt",
    APP / "MainActivity.kt",
    APP / "di/AppContainer.kt",
    TEST / "data/fog/TestFog.kt",
    TEST / "domain/territory/PointInPolygonTest.kt",
    TEST / "domain/territory/DetectForeignRegionTest.kt",
    TEST / "domain/MultiRegionProgressTest.kt",
    TEST / "domain/OpenTileAtPointTest.kt",
    TEST / "domain/FogProgressTest.kt",
    TEST / "data/settings/SettingsRepositoryTest.kt",
    TEST / "data/boundaries/BoundaryRepositoryTest.kt",
]


def strip_strings_and_comments(text):
    text = re.sub(r'"""(?:[^"]|"(?!""))*"""', '""', text, flags=re.S)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


balance_ok = True
for f in CHANGED:
    if not f.is_file():
        balance_ok = False
        print(f"      нет файла: {f.relative_to(ROOT)}")
        continue
    body = strip_strings_and_comments(f.read_text(encoding="utf-8"))
    for open_ch, close_ch in (("{", "}"), ("(", ")"), ("[", "]")):
        if body.count(open_ch) != body.count(close_ch):
            balance_ok = False
            print(f"      дисбаланс {open_ch}{close_ch}: {f.name}")
check("баланс скобок новых/изменённых Kotlin-файлов", balance_ok)

print(f"\n{passed} PASS / {failed} FAIL")
sys.exit(1 if failed else 0)
