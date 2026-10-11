#!/usr/bin/env python3
"""Зеркало процента открытия 1.8 против фактических артефактов пакета — проверка без SDK.

Проверяет (аналог _qa/check-1-6-fog-bitmap.py / check-1-7-tracking.py):
  1. Знаменатель: payload walkability.grid фактического пакета — cardinality == walkableCount
     (маска, которую читает FogRepository.loadWalkableMask, консистентна).
  2. Формула доли Kotlin (domain/FogProgress.kt: пересечение открытых битов с маской /
     cardinality маски, пустой знаменатель → 0) против numpy на случайных сценариях,
     включая открытые биты вне маски и независимость от порядка открытий.
  3. Форматирование «17,4%» живёт только на UI: маркеры ru-локали в MapScreen.kt,
     отсутствие форматирования процента в domain/ и viewmodel/.
  4. AD-10 grep: нет android.*/androidx.*/org.maplibre.* в domain/ и data/fog/.
  5. Баланс скобок новых/изменённых Kotlin-файлов.
"""
import json
import random
import re
import sys
import zipfile
from pathlib import Path

import numpy as np

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


# --- 1. знаменатель из фактического пакета ----------------------------------
with zipfile.ZipFile(ZIP) as z:
    with z.open("walkability.grid") as f:
        grid_header = json.loads(f.readline().decode("utf-8"))
        grid_payload = f.read()

n = grid_header["cols"] * grid_header["rows"]
mask = np.unpackbits(np.frombuffer(grid_payload, dtype=np.uint8),
                     count=n, bitorder="little").astype(bool)
walkable_total = int(mask.sum())
check("фактический пакет: cardinality маски == walkableCount",
      walkable_total == grid_header["walkableCount"],
      f"{walkable_total} != {grid_header['walkableCount']}")
check("знаменатель фактического пакета положителен", walkable_total > 0)


# --- 2. зеркало вычислителя (семантика domain/FogProgress.compute) ----------
def kotlin_compute(opened_indices, mask_bits):
    """BitSet.cardinality / count { i >= 0 && mask.get(i) } — как в Kotlin."""
    total = int(mask_bits.sum())
    if total == 0:
        return 0.0
    opened_walkable = sum(1 for i in opened_indices if i >= 0 and i < len(mask_bits) and mask_bits[i])
    return opened_walkable / total


rng = random.Random(1_8)
ok = True
for _ in range(200):
    # Случайная под-маска фактического пакета и случайные открытые биты,
    # включая индексы вне маски (непроходимые тайлы).
    opened = {i for i in rng.sample(range(n), rng.randint(0, 300))}
    expected_num = int(mask[list(opened & set(np.nonzero(mask)[0]))].sum()) if opened else 0
    expected = expected_num / walkable_total
    got = kotlin_compute(opened, mask)
    if abs(got - expected) > 1e-12:
        ok = False
        break
check("формула доли == numpy на 200 случайных сценариях (с битами вне маски)", ok)

# Краевые случаи матрицы тикета.
check("пустой регион → 0", kotlin_compute(set(), mask) == 0.0)
all_walkable = set(np.nonzero(mask)[0].tolist())
check("полное открытие → 1", kotlin_compute(all_walkable, mask) == 1.0)
check("биты вне маски не завышают",
      kotlin_compute(all_walkable | set(range(n, n + 50)), mask) == 1.0)
check("пустой знаменатель → 0 без деления на ноль",
      kotlin_compute({0, 1}, np.zeros(10, dtype=bool)) == 0.0)
sample = rng.sample(range(n), 100)
check("порядок открытий не влияет",
      kotlin_compute(set(sample), mask) == kotlin_compute(set(reversed(sample)), mask))

# --- 3. форматирование только на UI -----------------------------------------
def format_percent_ru(fraction):
    """Эквивалент String.format(Locale ru, "%.1f%%", fraction * 100): запятая, 1 знак."""
    return f"{fraction * 100:.1f}".replace(".", ",") + "%"


check("формат 0.174 → «17,4%»", format_percent_ru(0.174) == "17,4%")
check("формат 1.0 → «100,0%»", format_percent_ru(1.0) == "100,0%")
check("формат 0.0 → «0,0%»", format_percent_ru(0.0) == "0,0%")

map_screen = (APP / "ui/map/MapScreen.kt").read_text(encoding="utf-8")
check("MapScreen: ru-локаль форматирования", 'Locale.forLanguageTag("ru-RU")' in map_screen)
check("MapScreen: шаблон «%.1f%%»", '"%.1f%%"' in map_screen)

fog_progress = (APP / "domain/FogProgress.kt").read_text(encoding="utf-8")
check("domain не форматирует процент (нет %/String.format)",
      "String.format" not in fog_progress and "%%" not in fog_progress)

viewmodel = (APP / "viewmodel/MapViewModel.kt").read_text(encoding="utf-8")
check("viewmodel берёт готовую долю из domain (computeRegionProgress)",
      "computeRegionProgress.compute()" in viewmodel)
check("viewmodel не форматирует процент", "String.format" not in viewmodel and "%%" not in viewmodel)

# --- 4. AD-10 grep -----------------------------------------------------------
ANDROID_RE = re.compile(r"import\s+(android\.|androidx\.|org\.maplibre\.)")
violations = []
for pkg in ("domain", "data/fog"):
    for f in sorted((APP / pkg).glob("*.kt")):
        for line_no, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if ANDROID_RE.search(line):
                violations.append(f"{f.name}:{line_no}")
check("AD-10: domain/ и data/fog/ без android/androidx/maplibre", not violations, "; ".join(violations))

# --- 5. баланс скобок Kotlin -------------------------------------------------
CHANGED = [
    APP / "domain/FogProgress.kt",
    APP / "data/fog/FogRepository.kt",
    APP / "viewmodel/MapViewModel.kt",
    APP / "ui/map/MapScreen.kt",
    APP / "ui/theme/Theme.kt",
    APP / "di/AppContainer.kt",
    TEST / "domain/FogProgressTest.kt",
    TEST / "data/fog/FogRepositoryTest.kt",
    TEST / "data/fog/TestFog.kt",
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
check("баланс скобок изменённых Kotlin-файлов", balance_ok)

print(f"\n{passed} PASS / {failed} FAIL")
sys.exit(1 if failed else 0)
