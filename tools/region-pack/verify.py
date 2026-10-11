#!/usr/bin/env python3
"""Верификация пакета региона WalkQuest (verify истории 1.3).

Проверяет zip со стороны потребителя (как будет data/packages в 1.5):
  1. состав: ровно 7 файлов формата spine (пакет v3: + water.grid), без служебных
     бинарников;
  2. meta.json: formatVersion, tileSizeMeters, bbox, checksums, maxzoom;
  3. sha256 каждого файла == checksums из meta.json (roundtrip);
  4. tiles.pmtiles: валидный заголовок (go-pmtiles show), vector_layers ⊇
     source-layer'ы style.json;
  5. walkability.grid: roundtrip битпака, walkableCount сходится, сетка не пуста
     и не вся проходима (вода/непроходимое исключены конструкцией — см. preview);
  6. water.grid (пакет v3): roundtrip битпака, waterCount сходится, маска не пуста
     и не вся вода (реки/залив видны сквозь туман — см. water-preview.png).

Выход: PASS/FAIL по каждой проверке, вес пакета, maxzoom; exit 0 только при PASS.
"""
import hashlib
import json
import os
import re
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

from region_pack import tiles as tiles_mod
from region_pack.walkability import read_grid

REQUIRED = ["style.json", "tiles.pmtiles", "walkability.grid", "water.grid",
            "boundaries.geojson", "poi.json", "meta.json"]
FORBIDDEN_EXT = {".jar", ".exe", ".zip", ".pbf"}
PHOTO_RE = re.compile(r"^photos/poi-[w]?\d+\.jpg$")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def fail(msg):
    print(f"  FAIL: {msg}")
    return False


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: verify.py region-<id>-v<n>.zip")
    zip_path = sys.argv[1]
    ok = True
    size_mb = os.path.getsize(zip_path) / 1e6
    print(f"пакет: {zip_path} ({size_mb:.1f} МБ)")

    z = zipfile.ZipFile(zip_path)
    if z.testzip() is not None:
        ok = fail("zip повреждён (testzip)")
    names = z.namelist()

    print("[1] состав пакета")
    missing = [f for f in REQUIRED if f not in names]
    if missing:
        ok = fail(f"нет файлов: {missing}")
    junk = [n for n in names if n not in REQUIRED and not PHOTO_RE.match(n)]
    junk += [n for n in names if os.path.splitext(n)[1].lower() in FORBIDDEN_EXT]
    if junk:
        ok = fail(f"посторонние файлы: {junk}")
    if not missing and not junk:
        n_photos = len([n for n in names if PHOTO_RE.match(n)])
        print(f"  PASS: {len(REQUIRED)} файлов формата + {n_photos} фото, "
              f"служебных нет")

    if missing:
        sys.exit(1)

    meta = json.loads(z.read("meta.json"))
    print("[2] meta.json")
    for key in ("formatVersion", "tileSizeMeters", "bbox", "checksums", "maxzoom"):
        if key not in meta:
            ok = fail(f"нет поля {key}")
        else:
            print(f"  {key} = {meta[key] if key != 'checksums' else f'<{len(meta[key])} sha256>'}")

    print("[3] контрольные суммы (roundtrip)")
    for fn, expect in meta.get("checksums", {}).items():
        actual = sha256_bytes(z.read(fn))
        if actual != expect:
            ok = fail(f"{fn}: sha256 {actual[:12]}… != {expect[:12]}…")
        else:
            print(f"  PASS: {fn}")

    with tempfile.TemporaryDirectory() as td:
        pm_path = os.path.join(td, "tiles.pmtiles")
        with open(pm_path, "wb") as f:
            f.write(z.read("tiles.pmtiles"))
        print("[4] tiles.pmtiles")
        rc, out = tiles_mod.pmtiles_show(ROOT, pm_path)
        if rc != 0 or "pmtiles" not in out.lower():
            ok = fail(f"pmtiles show: rc={rc}: {out[:200]}")
        else:
            print("  PASS: заголовок pmtiles валиден")
            for line in out.splitlines()[:6]:
                print(f"    {line.strip()}")
        rc, out = tiles_mod.pmtiles_show(ROOT, pm_path, metadata=True)
        layers = set()
        if rc == 0:
            try:
                vj = json.loads(out[out.index("{"):])
                layers = {l["id"] for l in vj.get("vector_layers", [])}
            except Exception:
                pass
        style = json.loads(z.read("style.json"))
        used = {l.get("source-layer") for l in style.get("layers", [])
                if l.get("source-layer")}
        if not layers:
            ok = fail("не удалось прочитать vector_layers из метаданных")
        elif not used <= layers:
            ok = fail(f"слои стиля {used - layers} отсутствуют в pmtiles")
        else:
            print(f"  PASS: слои стиля {sorted(used)} ⊂ vector_layers")

    print("[5] walkability.grid")
    with tempfile.TemporaryDirectory() as td:
        gpath = os.path.join(td, "walkability.grid")
        with open(gpath, "wb") as f:
            f.write(z.read("walkability.grid"))
        header, grid = read_grid(gpath)
    n = header["cols"] * header["rows"]
    walkable = int(grid.sum())
    if walkable != header["walkableCount"]:
        ok = fail(f"walkableCount {header['walkableCount']} != битов {walkable}")
    elif walkable == 0 or walkable == n:
        ok = fail(f"сетка вырождена: {walkable}/{n}")
    else:
        print(f"  PASS: {walkable}/{n} проходимых "
              f"({100.0 * walkable / n:.1f}%), roundtrip битпака сошёлся")
    print(f"  превью для визуальной проверки вода/парки: "
          f"out/{meta.get('regionId', '?')}/walkability-preview.png")

    print("[6] water.grid (пакет v3)")
    with tempfile.TemporaryDirectory() as td:
        wpath = os.path.join(td, "water.grid")
        with open(wpath, "wb") as f:
            f.write(z.read("water.grid"))
        wheader, wgrid = read_grid(wpath)
    wn = wheader["cols"] * wheader["rows"]
    water = int(wgrid.sum())
    if wheader["cols"] != header["cols"] or wheader["rows"] != header["rows"]:
        ok = fail(f"геометрия water.grid {wheader['cols']}x{wheader['rows']} "
                  f"!= walkability {header['cols']}x{header['rows']}")
    if "waterCount" not in wheader:
        ok = fail("нет поля waterCount в заголовке water.grid")
    elif water != wheader["waterCount"]:
        ok = fail(f"waterCount {wheader['waterCount']} != битов {water}")
    elif water == 0 or water == wn:
        ok = fail(f"маска воды вырождена: {water}/{wn}")
    else:
        print(f"  PASS: {water}/{wn} тайлов-вода "
              f"({100.0 * water / wn:.1f}%), roundtrip битпака сошёлся")
    wg = meta.get("waterGrid")
    if wg and (wg.get("waterCount") != water):
        ok = fail(f"meta.waterGrid.waterCount {wg.get('waterCount')} != {water}")
    elif wg:
        print("  PASS: meta.waterGrid сходится с файлом")
    print(f"  превью для визуальной проверки воды: "
          f"out/{meta.get('regionId', '?')}/water-preview.png")

    print("[7] фото POI (пакет v2, опционально)")
    poi = json.loads(z.read("poi.json"))
    referenced = {p["photo"] for p in poi.get("pois", []) if p.get("photo")}
    photo_files = {n for n in names if PHOTO_RE.match(n)}
    bad_refs = {r for r in referenced if r not in photo_files}
    orphan = photo_files - referenced
    bad_jpeg = [n for n in sorted(photo_files)
                if z.read(n)[:2] != b"\xff\xd8"]
    if bad_refs:
        ok = fail(f"poi.json ссылается на отсутствующие фото: "
                  f"{sorted(bad_refs)[:3]}… ({len(bad_refs)})")
    if orphan:
        ok = fail(f"фото без ссылки из poi.json: {sorted(orphan)[:3]}… "
                  f"({len(orphan)})")
    if bad_jpeg:
        ok = fail(f"не jpeg: {bad_jpeg[:3]}… ({len(bad_jpeg)})")
    pstats = meta.get("photos", {})
    if pstats.get("status") == "unavailable" and photo_files:
        ok = fail("meta.photos.status=unavailable, но фото в пакете есть")
    if not (bad_refs or orphan or bad_jpeg):
        print(f"  PASS: {len(photo_files)} фото, все jpeg, ссылки poi.json "
              f"двусторонне целостны (status={pstats.get('status', '?')})")

    print("[8] poi url — ссылка на статью Википедии (пакет v5, опционально)")
    URL_RE = re.compile(r"^https://(ru|en)\.wikipedia\.org/wiki/\S+$")
    all_pois = poi.get("pois", [])
    described = [p for p in all_pois if p.get("description")]
    with_url = [p for p in all_pois if p.get("url")]
    bad_url = [p for p in with_url if not URL_RE.match(p["url"])]
    url_no_desc = [p for p in with_url if not p.get("description")]
    if bad_url:
        ok = fail(f"url не формата https://<lang>.wikipedia.org/wiki/<Title>: "
                  f"{[p['url'] for p in bad_url[:3]]}… ({len(bad_url)})")
    if url_no_desc:
        ok = fail(f"url у POI без описания: {len(url_no_desc)} "
                  f"(контракт v5: url только вместе с description)")
    if meta.get("packageVersion", 0) >= 5:
        desc_no_url = [p for p in described if not p.get("url")]
        if desc_no_url:
            ok = fail(f"POI с описанием без url: {len(desc_no_url)} "
                      f"(напр. {desc_no_url[0].get('name')!r})")
    wstats = meta.get("wikipedia", {})
    if wstats.get("withUrl") is not None and wstats["withUrl"] != len(with_url):
        ok = fail(f"meta.wikipedia.withUrl {wstats['withUrl']} != "
                  f"url в poi.json {len(with_url)}")
    if not (bad_url or url_no_desc):
        print(f"  PASS: url у {len(with_url)}/{len(described)} POI с описанием, "
              f"формат ссылок корректен, без описания url нет")

    print(f"\nитог: {'PASS' if ok else 'FAIL'} | вес {size_mb:.1f} МБ "
          f"(порог 50) | maxzoom {meta.get('maxzoom')}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
