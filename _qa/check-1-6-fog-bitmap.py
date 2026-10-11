#!/usr/bin/env python3
"""Зеркало формата тумана 1.6 против фактических артефактов пакета — проверка без SDK.

Проверяет (аналог _qa/check-1-5-package-install.py для истории 1.5):
  1. Формулы сетки Kotlin (data/fog/FogGrid.kt) воспроизводят cols/rows заголовка
     фактического walkability.grid из tools/region-pack/out/region-spb-city-v1.zip.
  2. Порядок бит lsb-first: ручная упаковка по семантике java.util.BitSet.toByteArray
     совпадает с np.packbits(bitorder="little") генератора (walkability.py).
  3. Формат файла биткарты (JSON-заголовок + битпак) читается тем же приёмом, что grid v0.
  4. indexAt (Kotlin floor-формула) на центрах тайлов фактической сетки даёт те же индексы.
  5. Маркеры согласованности в исходниках Kotlin и AD-10 grep (нет android.*/MapLibre
     в domain/ и data/fog/).
"""
import json
import math
import re
import sys
import zipfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
ZIP = ROOT / "tools/region-pack/out/region-spb-city-v1.zip"
APP = ROOT / "app/src/main/java/app/walkquest"

passed = failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"PASS  {name}")
    else:
        failed += 1
        print(f"FAIL  {name}  {detail}")


# --- фактические артефакты -------------------------------------------------
with zipfile.ZipFile(ZIP) as z:
    meta = json.loads(z.read("meta.json"))
    with z.open("walkability.grid") as f:
        grid_header = json.loads(f.readline().decode("utf-8"))
        grid_payload = f.read()

bbox = meta["bbox"]
cols, rows = grid_header["cols"], grid_header["rows"]
tile_m = grid_header["tileSizeMeters"]
origin_lon, origin_lat = grid_header["origin"]

# --- 1. формулы сетки (как в Kotlin FogGrid и в walkability.py) ------------
dy = tile_m / 111320.0
dx = tile_m / (111320.0 * math.cos(math.radians((bbox[1] + bbox[3]) / 2)))
calc_cols = math.ceil((bbox[2] - bbox[0]) / dx)
calc_rows = math.ceil((bbox[3] - bbox[1]) / dy)
check("формулы dx/dy воспроизводят cols", calc_cols == cols, f"{calc_cols} != {cols}")
check("формулы dx/dy воспроизводят rows", calc_rows == rows, f"{calc_rows} != {rows}")
check("origin grid == юго-запад bbox", [origin_lon, origin_lat] == [bbox[0], bbox[1]])
check("порядок битпака grid — row-major, lsb-first",
      grid_header["order"] == "row-major, lsb-first")
n = cols * rows
check("длина битпака grid == ceil(n/8)", len(grid_payload) == (n + 7) // 8)


# --- 2. lsb-first: java.util.BitSet.toByteArray == np.packbits(little) -----
def bitset_to_byte_array(indices, count):
    """Семантика java.util.BitSet.toByteArray (lsb-first в байте, little-endian байты)."""
    out = bytearray((count + 7) // 8)
    for i in indices:
        out[i // 8] |= 1 << (i % 8)
    return bytes(out)


probe = [0, 1, 7, 8, cols, cols + 3, n - 1]
bool_arr = np.zeros(n, dtype=bool)
bool_arr[probe] = True
numpy_packed = np.packbits(bool_arr, bitorder="little").tobytes()
kotlin_packed = bitset_to_byte_array(probe, n)
check("BitSet.toByteArray == np.packbits(bitorder=little)", kotlin_packed == numpy_packed)

# roundtrip: развёртка фактического grid как BitSet.valueOf
unpacked = np.unpackbits(np.frombuffer(grid_payload, dtype=np.uint8),
                         count=n, bitorder="little").astype(bool)
check("walkableCount заголовка == сумме бит", int(unpacked.sum()) == grid_header["walkableCount"])


def bitset_value_of(payload, count):
    """Семантика java.util.BitSet.valueOf: множество выставленных индексов."""
    result = set()
    for byte_i, b in enumerate(payload):
        for bit in range(8):
            if b & (1 << bit):
                i = byte_i * 8 + bit
                if i < count:
                    result.add(i)
    return result


check("BitSet.valueOf(payload grid) == np.unpackbits(little)",
      bitset_value_of(grid_payload, n) == set(np.nonzero(unpacked)[0].tolist()))

# --- 3. формат файла биткарты (как пишет Kotlin FogRepository) -------------
fog_header = {
    "formatVersion": 1,
    "regionId": meta["regionId"],
    "cols": cols,
    "rows": rows,
    "tileSizeMeters": tile_m,
    "origin": [origin_lon, origin_lat],
    "order": "row-major, lsb-first",
}
fog_file = (json.dumps(fog_header, separators=(",", ":")) + "\n").encode() + kotlin_packed
# читаем тем же приёмом, что read_grid из walkability.py (первая строка — JSON, дальше битпак)
nl = fog_file.index(b"\n")
parsed_header = json.loads(fog_file[:nl].decode())
parsed_bits = bitset_value_of(fog_file[nl + 1:], n)
check("fog-файл: заголовок читается read_grid-приёмом",
      parsed_header["cols"] == cols and parsed_header["rows"] == rows
      and parsed_header["order"] == "row-major, lsb-first")
check("fog-файл: биты roundtrip lsb-first", parsed_bits == set(probe))
check("fog-файл: размерность совпадает с grid",
      parsed_header["tileSizeMeters"] == tile_m and parsed_header["origin"] == [origin_lon, origin_lat])

# --- 4. indexAt на фактической сетке ---------------------------------------
def index_at(lat, lon):
    col = math.floor((lon - origin_lon) / dx)
    row = math.floor((lat - origin_lat) / dy)
    if not (0 <= col < cols and 0 <= row < rows):
        return None
    return row * cols + col


rng = np.random.default_rng(16)
sample = rng.choice(n, size=200, replace=False)
mismatches = 0
for i in sample:
    r, c = divmod(int(i), cols)
    lat = origin_lat + (r + 0.5) * dy
    lon = origin_lon + (c + 0.5) * dx
    if index_at(lat, lon) != int(i):
        mismatches += 1
check("indexAt: центры 200 случайных тайлов → свои индексы", mismatches == 0,
      f"{mismatches} несовпадений")
check("indexAt: за пределами bbox → None",
      index_at(origin_lat - 0.01, origin_lon) is None
      and index_at(origin_lat, bbox[2] + 0.01) is None)
# тайл 0 и последний
check("indexAt: центры крайних тайлов", index_at(origin_lat + dy / 2, origin_lon + dx / 2) == 0
      and index_at(origin_lat + (rows - 0.5) * dy, origin_lon + (cols - 0.5) * dx) == n - 1)

# --- 5. исходники Kotlin: маркеры согласованности + AD-10 -------------------
fog_grid_kt = (APP / "data/fog/FogGrid.kt").read_text(encoding="utf-8")
fog_repo_kt = (APP / "data/fog/FogRepository.kt").read_text(encoding="utf-8")
check("FogGrid.kt: формула dy (111320.0)", "tileSizeMeters / 111320.0" in fog_grid_kt)
check("FogGrid.kt: dx через cos(центр bbox)",
      "111320.0 * cos(Math.toRadians((bboxMinLat + bboxMaxLat) / 2.0))" in fog_grid_kt)
check("FogGrid.kt: index = row * cols + col", "row * cols + col" in fog_grid_kt)
check("FogRepository.kt: запись BitSet.toByteArray", "next.toByteArray()" in fog_repo_kt)
check("FogRepository.kt: чтение BitSet.valueOf", "BitSet.valueOf(payload)" in fog_repo_kt)
check("FogRepository.kt: formatVersion=1 и lsb-first",
      "FORMAT_VERSION = 1" in fog_repo_kt and "row-major, lsb-first" in fog_repo_kt)
check("FogRepository.kt: атомарная запись temp→rename", "renameTo" in fog_repo_kt)

forbidden = re.compile(r"^\s*import\s+(android\.|androidx\.|org\.maplibre\.)", re.M)
for layer in ("domain", "data/fog"):
    dirty = [p for p in (APP / layer).rglob("*.kt") if forbidden.search(p.read_text(encoding="utf-8"))]
    check(f"AD-10: {layer}/ без android.*/androidx.*/org.maplibre.*", not dirty, str(dirty))

print(f"\n{passed} PASS / {failed} FAIL")
sys.exit(1 if failed else 0)
