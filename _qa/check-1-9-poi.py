#!/usr/bin/env python3
"""Зеркало POI-маяков 1.9 против фактических артефактов пакета — проверка без SDK.

Проверяет (аналог _qa/check-1-6-fog-bitmap.py / check-1-8-region-progress.py):
  1. Контракт poi.json фактического region-spb-city-v1.zip == модель data/poi:
     корень {"pois": [...]}, ключи записей (osm_id, name, name_ru, category, lon, lat,
     wikidata, description), типы и null-допустимость; DTO в Poi.kt несёт те же поля.
  2. Семантика PoiFilter (тиры, пороги зума, область с запасом, лимит с приоритетом)
     на фактических 58k POI: монотонность выдачи по зуму, выживание маяков при
     переполнении, границы области; константы в Kotlin == константам зеркала.
  3. Порядок слоёв в MapScreen.kt: POI добавляется после тумана (маяки сквозь туман,
     FR-18); цвет маркера — токен poi-marker из темы.
  4. Офлайн (AD-2): ни один новый/изменённый файл не тянет сеть для POI.
  5. AD-10 grep: нет android.*/androidx.*/org.maplibre.* в data/poi/.
  6. Баланс скобок новых/изменённых Kotlin-файлов.
"""
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ZIP = ROOT / "tools/region-pack/out/region-spb-city-v1.zip"
APP = ROOT / "app/src/main/java/app/walkquest"
TEST = ROOT / "app/src/test/kotlin/app/walkquest"

passed = failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"PASS  {name}")
    else:
        failed += 1
        print(f"FAIL  {name}  {detail}")


# --- 1. контракт poi.json == модель data/poi ----------------------------------
with zipfile.ZipFile(ZIP) as z:
    raw = z.read("poi.json")
data = json.loads(raw)

check("корень poi.json — объект с ключом pois",
      isinstance(data, dict) and isinstance(data.get("pois"), list))
pois = data["pois"]
check("счёт POI фактического пакета ~58k", len(pois) > 50_000, f"{len(pois)}")

EXPECTED_KEYS = {"osm_id", "name", "name_ru", "category", "lon", "lat", "wikidata", "description"}
key_sets = {frozenset(p.keys()) for p in pois}
check("все записи несут ровно ключи модели (osm_id…description)",
      key_sets == {frozenset(EXPECTED_KEYS)}, f"варианты ключей: {key_sets}")

OSM_ID_RE = re.compile(r"^[nwr]?\d+$")
types_ok = all(
    (isinstance(p["osm_id"], int) or (isinstance(p["osm_id"], str) and OSM_ID_RE.match(p["osm_id"])))
    and isinstance(p["lon"], (int, float)) and isinstance(p["lat"], (int, float))
    and isinstance(p["category"], str)
    and (p["name"] is None or isinstance(p["name"], str))
    and (p["name_ru"] is None or isinstance(p["name_ru"], str))
    and (p["wikidata"] is None or isinstance(p["wikidata"], str))
    and (p["description"] is None or isinstance(p["description"], str))
    for p in pois
)
check("типы полей и null-допустимость (name/name_ru/wikidata/description nullable)", types_ok)

str_ids = sum(1 for p in pois if isinstance(p["osm_id"], str))
check("osm_id смешанный: число для узлов, строка «w…»/«r…» для путей и отношений",
      0 < str_ids < len(pois), f"строковых id: {str_ids}")

poi_kt = (APP / "data/poi/Poi.kt").read_text(encoding="utf-8")
check("DTO в Poi.kt несёт snake_case-поля генератора (@SerialName osm_id, name_ru)",
      '"osm_id"' in poi_kt and '"name_ru"' in poi_kt)
check("Poi.kt нормализует смешанный osm_id к строке (OsmIdSerializer)",
      "OsmIdSerializer" in poi_kt and "decodeJsonElement" in poi_kt)

# --- 2. зеркало PoiFilter на фактических данных --------------------------------
filter_kt = (APP / "data/poi/PoiFilter.kt").read_text(encoding="utf-8")
m = re.search(r"CULTURE_ZOOM = ([\d.]+)", filter_kt)
CULTURE_ZOOM = float(m.group(1))
m = re.search(r"ALL_ZOOM = ([\d.]+)", filter_kt)
ALL_ZOOM = float(m.group(1))
m = re.search(r"MAX_RESULTS = (\d+)", filter_kt)
MAX_RESULTS = int(m.group(1))
m = re.search(r"BOUNDS_MARGIN = ([\d.]+)", filter_kt)
BOUNDS_MARGIN = float(m.group(1))
check("константы выдачи прочитаны из PoiFilter.kt",
      (CULTURE_ZOOM, ALL_ZOOM, MAX_RESULTS, BOUNDS_MARGIN) == (13.0, 15.0, 800, 0.2),
      f"{CULTURE_ZOOM}/{ALL_ZOOM}/{MAX_RESULTS}/{BOUNDS_MARGIN}")

CULTURE_KEYS = {"tourism", "historic", "leisure", "natural"}


def tier_of(p):
    if p["wikidata"] or p["description"]:
        return 1
    if p["category"].split("=")[0] in CULTURE_KEYS:
        return 2
    return 3


def select(pois, bounds, zoom, cap=MAX_RESULTS):
    """Семантика PoiFilter.selectPois: тиры по зуму, область + запас, лимит по приоритету."""
    max_tier = 3 if zoom >= ALL_ZOOM else (2 if zoom >= CULTURE_ZOOM else 1)
    min_lat, max_lat, min_lon, max_lon = bounds
    d_lat = (max_lat - min_lat) * BOUNDS_MARGIN
    d_lon = (max_lon - min_lon) * BOUNDS_MARGIN
    in_area = [p for p in pois
               if tier_of(p) <= max_tier
               and min_lat - d_lat <= p["lat"] <= max_lat + d_lat
               and min_lon - d_lon <= p["lon"] <= max_lon + d_lon]
    if len(in_area) <= cap:
        return in_area
    return sorted(in_area, key=tier_of)[:cap]


with zipfile.ZipFile(ZIP) as z:
    meta = json.loads(z.read("meta.json"))
REGION_BOUNDS = (meta["bbox"][1], meta["bbox"][3], meta["bbox"][0], meta["bbox"][2])

NO_CAP = 10**9
n10 = len(select(pois, REGION_BOUNDS, 10.0, cap=NO_CAP))
n13 = len(select(pois, REGION_BOUNDS, 13.0, cap=NO_CAP))
n16 = len(select(pois, REGION_BOUNDS, 16.0, cap=NO_CAP))
tier1_actual = sum(1 for p in pois if tier_of(p) == 1)
check("на обзорном зуме весь регион — ровно маяки тира 1 (wikidata/описание)",
      n10 == tier1_actual, f"{n10} != {tier1_actual}")
check("маяков тира 1 в пакете сотни (580 по отчёту 1.3)", 400 <= tier1_actual <= 1000,
      f"{tier1_actual}")
check("выдача монотонно растёт с зумом (1 → 1+2 → все)", n10 <= n13 <= n16,
      f"{n10}/{n13}/{n16}")
check("тираж тира 3 существует (на уличном зуме выдача строго больше)", n16 > n13)

# Центр города на уличном зуме: лимит уважается, маяки выживают при переполнении.
CITY = (59.90, 59.98, 30.25, 30.42)
city16 = select(pois, CITY, 16.0)
check("лимит выдачи уважается", len(city16) <= MAX_RESULTS, f"{len(city16)}")
beacons_city = [p for p in pois
                if tier_of(p) == 1 and CITY[0] <= p["lat"] <= CITY[1] and CITY[2] <= p["lon"] <= CITY[3]]
check("при переполнении все маяки области выживают",
      all(p in city16 for p in beacons_city), f"маяков {len(beacons_city)}, в выдаче {len(city16)}")

far = (10.0, 11.0, 10.0, 11.0)
check("область вне региона → пустая выдача", len(select(pois, far, 18.0)) == 0)

# --- 3. порядок слоёв и токен маркера ------------------------------------------
map_screen = (APP / "ui/map/MapScreen.kt").read_text(encoding="utf-8")
fog_pos = map_screen.find("updateFogLayer(style, currentState")
poi_pos = map_screen.find("updatePoiLayer(style, currentState")
check("setStyle-колбэк: POI-слой добавляется после fog (маяки поверх тумана, FR-18)",
      0 < fog_pos < poi_pos)
check("тап по карте закрывает карточку (тап вне, DESIGN)",
      "onMapTappedOutside" in map_screen and "currentOnOutsideTap()" in map_screen)
check("карточка закрывается свайпом вниз (detectVerticalDragGestures)",
      "detectVerticalDragGestures" in map_screen)
check("данные карточки — только поля Poi из пакета (офлайн)",
      "poi.displayName" in map_screen and "poi.description" in map_screen)

theme_kt = (APP / "ui/theme/Theme.kt").read_text(encoding="utf-8")
check("токен poi-marker: #C6E26B тёмная / #7A9A1F светлая (accent, графика)",
      "0xFFC6E26B" in theme_kt and "0xFF7A9A1F" in theme_kt)

viewmodel = (APP / "viewmodel/MapViewModel.kt").read_text(encoding="utf-8")
check("UDF: выдача POI через PoiFilter в viewmodel, не из UI напрямую",
      "PoiFilter.selectPois" in viewmodel and "PoiFilter" not in map_screen)
check("UDF: selectedPoi живёт в MapUiState, события onPoiTapped/onPoiCardDismissed",
      "selectedPoi" in viewmodel and "onPoiTapped" in viewmodel and "onPoiCardDismissed" in viewmodel)

# --- 4. офлайн (AD-2): POI-цепочка без сети -------------------------------------
NET_RE = re.compile(r"(HttpURLConnection|okhttp|retrofit|download\(|http://|https://)")
net_hits = []
for f in [APP / "data/poi/Poi.kt", APP / "data/poi/PoiRepository.kt", APP / "data/poi/PoiFilter.kt"]:
    if NET_RE.search(f.read_text(encoding="utf-8")):
        net_hits.append(f.name)
check("data/poi без сетевых вызовов (AD-2: офлайн после установки)", not net_hits, "; ".join(net_hits))

# --- 5. AD-10 grep --------------------------------------------------------------
ANDROID_RE = re.compile(r"import\s+(android\.|androidx\.|org\.maplibre\.)")
violations = []
for f in sorted((APP / "data/poi").glob("*.kt")):
    for line_no, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if ANDROID_RE.search(line):
            violations.append(f"{f.name}:{line_no}")
check("AD-10: data/poi/ без android/androidx/maplibre", not violations, "; ".join(violations))

# --- 6. баланс скобок Kotlin -----------------------------------------------------
CHANGED = [
    APP / "data/poi/Poi.kt",
    APP / "data/poi/PoiRepository.kt",
    APP / "data/poi/PoiFilter.kt",
    APP / "viewmodel/MapViewModel.kt",
    APP / "ui/map/MapScreen.kt",
    APP / "ui/theme/Theme.kt",
    APP / "di/AppContainer.kt",
    TEST / "data/poi/PoiRepositoryTest.kt",
    TEST / "data/poi/PoiFilterTest.kt",
]


def strip_strings_and_comments(text):
    text = re.sub(r'"""(?:[^"]|"(?!""))*"""', '""', text, flags=re.S)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


balance_ok = True
for f in CHANGED:
    body = strip_strings_and_comments(f.read_text(encoding="utf-8"))
    for open_ch, close_ch in (("{", "}"), ("(", ")"), ("[", "]")):
        if body.count(open_ch) != body.count(close_ch):
            balance_ok = False
            print(f"      дисбаланс {open_ch}{close_ch}: {f.name}")
check("баланс скобок новых/изменённых Kotlin-файлов", balance_ok)

print(f"\n{passed} PASS / {failed} FAIL")
sys.exit(1 if failed else 0)
