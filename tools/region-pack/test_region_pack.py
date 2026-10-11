#!/usr/bin/env python3
"""Проверки краевых случаев генератора (I/O-матрица плана истории 1.3).

Не требует сети и pbf: сеть подменяется, pbf-путь — несуществующий.
Реальные прогоны (happy path) — см. отчёт истории и verify.py.
"""
import os
import subprocess
import sys
from types import SimpleNamespace

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

from region_pack import collect as collect_mod
from region_pack import walkability as walk_mod
from region_pack import wiki as wiki_mod

failures = []


def check(name, cond, extra=""):
    print(f"  {'PASS' if cond else 'FAIL'}: {name} {extra}")
    if not cond:
        failures.append(name)


print("[1] wiki: API недоступно → честная деградация, сборка не падает")
orig = wiki_mod._get_json
wiki_mod._get_json = lambda *a, **k: None  # все батчи падают
pois = [{"name": "A", "wikidata": "Q656"}, {"name": "B", "wikidata": None}]
stats = wiki_mod.enrich_pois(pois, cache_dir=os.path.join(ROOT, "cache", "wiki-test"),
                             enabled=True)
check("status == unavailable", stats["status"] == "unavailable", str(stats))
check("description=None у всех", all(p["description"] is None for p in pois))
check("url-полей нет", all("url" not in p for p in pois))
check("промахи не закэшированы",
      not os.path.exists(os.path.join(ROOT, "cache", "wiki-test", "Q656.json")))
wiki_mod._get_json = orig

stats = wiki_mod.enrich_pois([{"name": "A", "wikidata": "Q1"}],
                             cache_dir=os.path.join(ROOT, "cache", "wiki-test"),
                             enabled=False)
check("--no-wiki → status skipped", stats["status"] == "skipped")

print("[2] walkability: пустые коллекции (нет воды/парков) не роняют сетку")
empty = SimpleNamespace(walk_lines=[[(10.0, 20.0), (10.01, 20.01)]],
                        water_polys=[], walk_polys=[])
header, packed, st = walk_mod.build_grid(empty, [10.0, 20.0, 10.02, 20.02], 100.0)
check("сетка построена по дорогам", st["walkable"] > 0, str(st))
check("walkableCount сходится", header["walkableCount"] == st["walkable"])

print("[3] build.py: несуществующий pbf → exit!=0 с понятным сообщением")
ENV_UTF8 = {**os.environ, "PYTHONIOENCODING": "utf-8"}
proc = subprocess.run([sys.executable, os.path.join(ROOT, "build.py"),
                       "--pbf", "no/such/file.osm.pbf", "--region-id", "x"],
                      capture_output=True, text=True, encoding="utf-8",
                      errors="replace", env=ENV_UTF8)
check("exit != 0", proc.returncode != 0)
check("сообщение про pbf", "pbf не найден" in (proc.stdout + proc.stderr))

print("[4] build.py: неверные параметры отклоняются до запуска")
for args, frag in [(["--pbf", "x.osm.pbf", "--tile-size", "0"], "tile-size"),
                   (["--pbf", "x.osm.pbf", "--maxzoom", "99"], "maxzoom"),
                   (["--pbf", "x.osm.pbf", "--bbox", "1,2,3"], "bbox")]:
    proc = subprocess.run([sys.executable, os.path.join(ROOT, "build.py")] + args,
                          capture_output=True, text=True)
    check(f"{' '.join(args[-2:])} → error", proc.returncode != 0
          and frag in (proc.stdout + proc.stderr))

print("[5] verify.py: битый zip (потерян файл) → FAIL")
import json
import tempfile
import zipfile
with tempfile.TemporaryDirectory() as td:
    bad = os.path.join(td, "region-bad-v1.zip")
    with zipfile.ZipFile(bad, "w") as z:
        z.writestr("meta.json", json.dumps({"formatVersion": 1,
                                            "tileSizeMeters": 100,
                                            "bbox": [0, 0, 1, 1],
                                            "checksums": {}}))
    proc = subprocess.run([sys.executable, os.path.join(ROOT, "verify.py"), bad],
                          capture_output=True, text=True)
    check("exit != 0", proc.returncode != 0)
    check("FAIL в выводе", "FAIL" in proc.stdout)

print("[6] collect: whitelist POI — ядро достопримечательностей (пакет v2)")
pc = collect_mod.poi_category
for tags, expect in [
    # ядро: музеи/галереи/смотровые, памятники/мемдоски, уличное искусство,
    # heritage-здания (доходные дома), парки/сады, культовые, театры,
    # знаковые мосты, маяки-башни-обелиски
    ({"tourism": "museum"}, "tourism=museum"),
    ({"tourism": "attraction"}, "tourism=attraction"),
    ({"tourism": "artwork"}, "tourism=artwork"),
    ({"tourism": "viewpoint"}, "tourism=viewpoint"),
    ({"tourism": "gallery"}, "tourism=gallery"),
    ({"historic": "memorial"}, "historic=memorial"),
    ({"historic": "monument"}, "historic=monument"),
    ({"historic": "castle"}, "historic=castle"),
    ({"historic": "palace"}, "historic=palace"),
    ({"historic": "manor"}, "historic=manor"),
    ({"historic": "heritage"}, "historic=heritage"),
    ({"historic": "building"}, "historic=building"),
    ({"historic": "locomotive"}, "historic=locomotive"),
    ({"leisure": "park"}, "leisure=park"),
    ({"leisure": "garden"}, "leisure=garden"),
    ({"leisure": "nature_reserve"}, "leisure=nature_reserve"),
    ({"amenity": "place_of_worship"}, "amenity=place_of_worship"),
    ({"amenity": "theatre"}, "amenity=theatre"),
    ({"amenity": "arts_centre"}, "amenity=arts_centre"),
    ({"man_made": "bridge"}, "man_made=bridge"),
    ({"man_made": "lighthouse"}, "man_made=lighthouse"),
    ({"man_made": "tower"}, "man_made=tower"),
    ({"man_made": "obelisk"}, "man_made=obelisk"),
]:
    check(f"{tags} → {expect}", pc(tags) == expect, str(pc(tags)))
for tags in [
    # развлечения и быт — не ядро (пакет v2)
    {"tourism": "zoo"}, {"tourism": "aquarium"}, {"tourism": "theme_park"},
    {"amenity": "library"}, {"amenity": "planetarium"},
    {"amenity": "fountain"}, {"amenity": "monastery"},
    {"leisure": "bandstand"}, {"leisure": "common"},
    {"man_made": "water_tower"}, {"man_made": "cross"},
    {"natural": "beach"}, {"natural": "spring"}, {"natural": "cliff"},
    # коммерческий и бытовой мусор — не маяки
    {"shop": "outpost"}, {"shop": "supermarket"}, {"shop": "hairdresser"},
    {"amenity": "cafe"}, {"amenity": "fast_food"}, {"amenity": "pharmacy"},
    {"amenity": "bank"}, {"amenity": "kindergarten"}, {"amenity": "fuel"},
    {"leisure": "sauna"}, {"leisure": "fitness_centre"},
    {"leisure": "sports_centre"}, {"leisure": "playground"},
    {"leisure": "tanning_salon"}, {"leisure": "escape_game"},
    {"tourism": "hotel"}, {"tourism": "hostel"}, {"tourism": "apartment"},
    {"tourism": "information"}, {"tourism": "yes"},
    {"man_made": "works"}, {"man_made": "surveillance"},
    {"man_made": "pumping_station"}, {"natural": "wood"},
    {"natural": "water"}, {"historic": "yes"}, {"historic": "kindergarten"},
]:
    check(f"{tags} → None", pc(tags) is None, str(pc(tags)))
# теги вне whitelist не дают категорию
check("пустые теги → None", pc({}) is None)
# все ключи whitelist — из тиров, известных фильтру приложения (PoiFilter 1.9)
APP_TIER_KEYS = {"tourism", "historic", "leisure", "natural", "amenity",
                 "man_made"}
check("ключи whitelist ⊂ ключи тиров приложения",
      set(collect_mod.POI_TAGS) <= APP_TIER_KEYS)

print("[7] photos: API недоступно → честная деградация, сборка не падает")
from region_pack import photos as photos_mod
orig = photos_mod._get_json
photos_mod._get_json = lambda *a, **k: None  # все батчи падают
import tempfile
with tempfile.TemporaryDirectory() as td:
    pois = [{"osm_id": 1, "name": "A", "wikidata": "Q656", "description": "x"},
            {"osm_id": 2, "name": "B", "wikidata": None, "description": None}]
    stats = photos_mod.attach_photos(pois, cache_dir=os.path.join(td, "cache"),
                                     photos_dir=os.path.join(td, "photos"),
                                     enabled=True)
    check("status == unavailable", stats["status"] == "unavailable", str(stats))
    check("photo-полей нет", all("photo" not in p for p in pois))
    check("photos/ не создан", not os.path.exists(os.path.join(td, "photos")))
    check("промахи не закэшированы",
          not os.path.exists(os.path.join(td, "cache", "Q656.json")))
photos_mod._get_json = orig

with tempfile.TemporaryDirectory() as td:
    stats = photos_mod.attach_photos(
        [{"osm_id": 1, "name": "A", "wikidata": "Q1", "description": "x"}],
        cache_dir=os.path.join(td, "cache"), photos_dir=os.path.join(td, "photos"),
        enabled=False)
    check("--no-photos → status skipped", stats["status"] == "skipped")

print("[8] photos: only_described отсекает POI без описания")
with tempfile.TemporaryDirectory() as td:
    # сеть подменена: Q1 с P18, Q2 без; скачивание подменено jpeg-байтами
    photos_mod._get_json = lambda s, url, params, **k: {
        "entities": {"Q1": {"claims": {"P18": [{"mainsnak": {"datavalue":
            {"value": "A.jpg"}}}]}}, "Q2": {"claims": {}}}
    } if "wikidata" in url else {
        "query": {"pages": {"1": {"title": "File:A.jpg", "imageinfo":
            [{"thumburl": "http://x/A.jpg"}]}}}
    }
    photos_mod._get_bytes = lambda *a, **k: b"\xff\xd8\xff\xd9"
    pois = [{"osm_id": 1, "name": "A", "wikidata": "Q1", "description": "d"},
            {"osm_id": 2, "name": "B", "wikidata": "Q1", "description": None},
            {"osm_id": 3, "name": "C", "wikidata": "Q2", "description": "d"}]
    stats = photos_mod.attach_photos(pois, cache_dir=os.path.join(td, "cache"),
                                     photos_dir=os.path.join(td, "photos"),
                                     enabled=True, only_described=True)
    check("фото только у POI с описанием",
          pois[0].get("photo") == "photos/poi-1.jpg"
          and "photo" not in pois[1] and "photo" not in pois[2], str(stats))
    check("файл записан", os.path.exists(
        os.path.join(td, "photos", "poi-1.jpg")))
    check("status ok", stats["status"] == "ok", str(stats))
photos_mod._get_json = orig

print("[9] water: маска воды (пакет v3)")
from shapely.geometry import box
from region_pack import package as package_mod
check("water.grid в PACKAGE_FILES", "water.grid" in package_mod.PACKAGE_FILES)
bbox9 = [10.0, 20.0, 10.02, 20.02]
col9 = SimpleNamespace(walk_lines=[], walk_polys=[],
                       water_polys=[box(10.0, 20.0, 10.011, 20.011)])
wheader, wpacked, wst = walk_mod.build_water_mask(col9, bbox9, 100.0)
check("водные тайлы есть", wst["water"] > 0, str(wst))
check("waterCount сходится", wheader["waterCount"] == wst["water"])
h_walk, _, _ = walk_mod.build_grid(col9, bbox9, 100.0)
check("cols/rows == walkability",
      (wheader["cols"], wheader["rows"]) == (h_walk["cols"], h_walk["rows"]),
      f"{wheader['cols']}x{wheader['rows']}")
with tempfile.TemporaryDirectory() as td:
    wpath = os.path.join(td, "water.grid")
    walk_mod.write_grid(wpath, wheader, wpacked)
    h2, g2 = walk_mod.read_grid(wpath)
    check("roundtrip битпака", int(g2.sum()) == wheader["waterCount"]
          and h2["order"] == "row-major, lsb-first")
empty_water = SimpleNamespace(walk_lines=[], walk_polys=[], water_polys=[])
h3, _, st3 = walk_mod.build_water_mask(empty_water, bbox9, 100.0)
check("без воды → маска нулевая, сборка не падает",
      st3["water"] == 0 and h3["waterCount"] == 0, str(st3))

print("[10] wiki url (пакет v5): ссылка на статью у POI с описанием")
import re
URL_RE = re.compile(r"^https://(ru|en)\.wikipedia\.org/wiki/\S+$")
RU_URL_DVORTSOVAYA = ("https://ru.wikipedia.org/wiki/%D0%94%D0%B2%D0%BE%D1%80"
                      "%D1%86%D0%BE%D0%B2%D0%B0%D1%8F_%D0%BF%D0%BB%D0%BE%D1%89"
                      "%D0%B0%D0%B4%D1%8C")


def _fake_wiki_api(session, url, params, **k):
    """Подмена сети: Q1 → ruwiki, Q2 → enwiki, Q3 → без sitelink; extract всем."""
    if "wikidata" in url:
        return {"entities": {
            "Q1": {"sitelinks": {"ruwiki": {"title": "Дворцовая площадь"}}},
            "Q2": {"sitelinks": {"enwiki": {"title": "Palace Square"}}},
            "Q3": {"sitelinks": {}}}}
    pages = {str(i): {"title": t, "extract": "Выдержка."}
             for i, t in enumerate(params["titles"].split("|"))}
    return {"query": {"pages": pages}}


with tempfile.TemporaryDirectory() as td:
    wiki_mod._get_json = _fake_wiki_api
    pois = [{"name": "A", "wikidata": "Q1"}, {"name": "B", "wikidata": "Q2"},
            {"name": "C", "wikidata": "Q3"}, {"name": "D", "wikidata": None}]
    stats = wiki_mod.enrich_pois(pois, cache_dir=os.path.join(td, "wiki"))
    check("url ru-статьи (подчёркивания, percent-encoding)",
          pois[0].get("url") == RU_URL_DVORTSOVAYA, pois[0].get("url", "?"))
    check("url en-fallback", pois[1].get("url") ==
          "https://en.wikipedia.org/wiki/Palace_Square", pois[1].get("url", "?"))
    check("формат url у всех описанных",
          all(URL_RE.match(p["url"]) for p in pois[:2]))
    check("sitelink пуст → без url", "url" not in pois[2]
          and pois[2]["description"] is None)
    check("без wikidata → без url", "url" not in pois[3])
    check("withUrl == withDescription == 2",
          stats["withUrl"] == stats["withDescription"] == 2, str(stats))
    # повторный прогон — из кэша, сеть не нужна
    wiki_mod._get_json = lambda *a, **k: None
    pois2 = [{"name": "A", "wikidata": "Q1"}, {"name": "C", "wikidata": "Q3"}]
    wiki_mod.enrich_pois(pois2, cache_dir=os.path.join(td, "wiki"))
    check("url из кэша без сети",
          pois2[0].get("url") == RU_URL_DVORTSOVAYA and "url" not in pois2[1])
    wiki_mod._get_json = orig

print("[11] wiki url: старый кэш (только description) — добор sitelink батчем")
with tempfile.TemporaryDirectory() as td:
    cdir = os.path.join(td, "wiki")
    os.makedirs(cdir)
    with open(os.path.join(cdir, "Q9.json"), "w", encoding="utf-8") as f:
        json.dump({"description": "Старая выдержка."}, f, ensure_ascii=False)
    calls = []

    def _fake_sitelink_only(session, url, params, **k):
        calls.append(url)
        return {"entities": {"Q9": {"sitelinks":
                {"ruwiki": {"title": "Исаакиевский собор"}}}}}

    wiki_mod._get_json = _fake_sitelink_only
    pois = [{"name": "I", "wikidata": "Q9"}]
    wiki_mod.enrich_pois(pois, cache_dir=cdir)
    check("url добран sitelink-запросом",
          pois[0].get("url", "").startswith("https://ru.wikipedia.org/wiki/")
          and URL_RE.match(pois[0]["url"]) is not None, pois[0].get("url", "?"))
    check("extract заново не запрошен",
          all("wikipedia.org" not in u for u in calls), str(calls))
    with open(os.path.join(cdir, "Q9.json"), encoding="utf-8") as f:
        check("url записан в кэш", json.load(f).get("url") == pois[0]["url"])
    wiki_mod._get_json = orig

print(f"\nитог: {'PASS' if not failures else 'FAIL: ' + ', '.join(failures)}")
sys.exit(1 if failures else 0)