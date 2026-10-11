#!/usr/bin/env python3
"""Генератор пакета региона WalkQuest — одна команда (история 1.3, AD-6).

Примеры:
  # полный прогон СПб+Ленобласть (машина автора): дамп СЗФО Geofabrik + клип по bbox
  python tools/region-pack/build.py --region spb-lo

  # произвольный участок из локального pbf
  python tools/region-pack/build.py --pbf data/SanktPetersburg.osm.pbf \
      --region-id spb-demo --bbox 29.4,59.6,31.1,60.4

Пайплайн: download → tiles.pmtiles (planetiler) → collect (pyosmium) →
wiki extracts → walkability.grid → style/meta/zip → verify.
"""
import argparse
import os
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

from region_pack import collect as collect_mod
from region_pack import package as package_mod
from region_pack import photos as photos_mod
from region_pack import tiles as tiles_mod
from region_pack import walkability as walk_mod
from region_pack import wiki as wiki_mod

# Пресеты регионов. bbox: [minlon, minlat, maxlon, maxlat].
REGIONS = {
    "spb-lo": {
        "url": "https://download.geofabrik.de/russia/northwestern-fed-district-latest.osm.pbf",
        "bbox": [27.5, 58.6, 36.0, 61.9],  # СПб + Ленинградская область
        "note": "СЗФО Geofabrik (~650 МБ) с клипом по bbox СПб+ЛО",
    },
    "spb-city": {
        "url": "https://download.geofabrik.de/russia/northwestern-fed-district-latest.osm.pbf",
        "bbox": [29.55, 59.796, 30.63, 60.071],  # пакет v4: западная граница
        # сдвинута с 30.042 до 29.55 — Невская губа и восток Финского залива
        # (до Лисьего Носа/Лахты) целиком в кадре; экстракт BBBike оканчивается
        # на 30.042, поэтому с v4 источник — дамп СЗФО Geofabrik с клипом
        "note": "СПб и пригороды из дампа СЗФО Geofabrik (~650 МБ), клип по bbox",
    },
}

SIZE_LIMIT_MB = 50  # AD-8


def parse_bbox(s):
    vals = [float(v) for v in s.split(",")]
    if len(vals) != 4 or vals[0] >= vals[2] or vals[1] >= vals[3]:
        raise argparse.ArgumentTypeError("bbox: minlon,minlat,maxlon,maxlat")
    return vals


def download(url, dest):
    if os.path.exists(dest):
        print(f"[download] уже есть: {dest} ({os.path.getsize(dest) / 1e6:.0f} МБ)")
        return dest
    print(f"[download] {url} → {dest}", flush=True)
    tmp = dest + ".part"
    try:
        urllib.request.urlretrieve(url, tmp)
    except Exception as e:
        sys.exit(
            f"Скачивание не удалось: {e}\n"
            f"Скачайте вручную: curl -L -o \"{dest}\" \"{url}\"\n"
            f"и повторите с --pbf \"{dest}\"")
    os.replace(tmp, dest)
    print(f"[download] ok, {os.path.getsize(dest) / 1e6:.0f} МБ")
    return dest


def main():
    ap = argparse.ArgumentParser(description="WalkQuest region-pack generator")
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--region", choices=sorted(REGIONS),
                     help="пресет региона (URL + bbox)")
    src.add_argument("--pbf", help="локальный OSM pbf")
    ap.add_argument("--url", help="URL pbf для скачивания (с --region необязателен)")
    ap.add_argument("--region-id", help="id региона (по умолчанию из пресета/имени pbf)")
    ap.add_argument("--bbox", type=parse_bbox,
                    help="minlon,minlat,maxlon,maxlat — клип и сетка")
    ap.add_argument("--version", type=int, default=1, help="версия пакета v{n}")
    ap.add_argument("--tile-size", type=float, default=100.0,
                    help="размер тайла сетки, метры (финал — spike 1.11)")
    ap.add_argument("--maxzoom", type=int, default=14, help="maxzoom тайлов planetiler")
    ap.add_argument("--heap", default="4g", help="heap для planetiler (напр. 8g)")
    ap.add_argument("--no-wiki", action="store_true", help="без выдержек Википедии")
    ap.add_argument("--no-photos", action="store_true",
                    help="без фото POI (Wikidata P18)")
    ap.add_argument("--photos-all", action="store_true",
                    help="фото для всех POI с wikidata (дефолт — только с описанием,"
                         " контроль веса пакета AD-8)")
    ap.add_argument("--skip-tiles", action="store_true",
                    help="переиспользовать out/<id>/tiles.pmtiles")
    ap.add_argument("--keep-package", action="store_true",
                    help="не удалять каталог out/<id>/package после zip")
    args = ap.parse_args()

    if args.tile_size <= 0:
        ap.error("--tile-size должен быть > 0")
    if not 0 <= args.maxzoom <= 15:
        ap.error("--maxzoom вне диапазона 0–15")

    # --- источник pbf и bbox ---
    preset = REGIONS.get(args.region or "", {})
    url = args.url or preset.get("url")
    bbox = args.bbox or preset.get("bbox")
    if args.pbf:
        pbf = args.pbf
    elif url:
        os.makedirs(os.path.join(ROOT, "data"), exist_ok=True)
        pbf = os.path.join(ROOT, "data", url.rsplit("/", 1)[-1])
        download(url, pbf)
    else:
        ap.error("нужен --region, --pbf или --url")
    if not os.path.exists(pbf):
        sys.exit(f"pbf не найден: {pbf}")
    pbf = os.path.abspath(pbf)  # planetiler запускается с cwd=ROOT: нужен абсолютный путь
    region_id = (args.region_id or args.region
                 or os.path.splitext(os.path.basename(pbf))[0]
                 .replace("-latest", "").replace(".osm", ""))
    if bbox is None:
        bbox = collect_mod.read_pbf_bbox(pbf)
        if bbox is None:
            sys.exit("bbox не задан и в заголовке pbf его нет — передайте --bbox")

    out_dir = os.path.join(ROOT, "out", region_id)
    pkg_dir = os.path.join(out_dir, "package")
    os.makedirs(pkg_dir, exist_ok=True)
    pmtiles = os.path.join(out_dir, "tiles.pmtiles")
    zip_path = os.path.join(ROOT, "out", f"region-{region_id}-v{args.version}.zip")
    t0 = time.time()

    # --- 1. векторные тайлы ---
    if args.skip_tiles and os.path.exists(pmtiles):
        print(f"[tiles] пропуск, используем {pmtiles}")
    else:
        tiles_mod.build_tiles(ROOT, pbf, pmtiles, args.maxzoom, bbox=bbox,
                              heap=args.heap)

    # --- 2. collect OSM (с pickle-кэшем: повторные прогоны не перечитывают pbf) ---
    collect_cache = os.path.join(out_dir, "collect.pkl")
    print(f"[collect] {pbf} bbox={bbox}", flush=True)
    t = time.time()
    import pickle
    from types import SimpleNamespace
    if os.path.exists(collect_cache):
        with open(collect_cache, "rb") as f:
            col = SimpleNamespace(**pickle.load(f))
        print(f"[collect] из кэша {collect_cache} ({time.time() - t:.0f}s); "
              f"удалите файл, если pbf/bbox изменились")
    else:
        c = collect_mod.collect(pbf, bbox=bbox)
        col = SimpleNamespace(admin=c.admin, pois=c.pois,
                              water_polys=c.water_polys, walk_polys=c.walk_polys,
                              walk_lines=c.walk_lines)
        with open(collect_cache, "wb") as f:
            pickle.dump(vars(col), f)
    print(f"[collect] {time.time() - t:.0f}s: admin={len(col.admin)} "
          f"poi={len(col.pois)} water={len(col.water_polys)} "
          f"walkpolys={len(col.walk_polys)} walklines={len(col.walk_lines)}")

    # --- 3. выдержки Википедии (FR-18) ---
    t = time.time()
    wiki_stats = wiki_mod.enrich_pois(
        col.pois, os.path.join(ROOT, "cache", "wiki"), enabled=not args.no_wiki)
    print(f"[wiki] {time.time() - t:.0f}s: {wiki_stats}")

    # --- 4. фото POI (Wikidata P18, пакет v2) ---
    t = time.time()
    photo_stats = photos_mod.attach_photos(
        col.pois, os.path.join(ROOT, "cache", "photos"),
        photos_dir=os.path.join(pkg_dir, "photos"),
        enabled=not args.no_photos, only_described=not args.photos_all)
    print(f"[photos] {time.time() - t:.0f}s: {photo_stats}")

    # --- 5. файлы пакета ---
    bcount = package_mod.write_boundaries(
        os.path.join(pkg_dir, "boundaries.geojson"), col.admin)
    package_mod.write_poi(os.path.join(pkg_dir, "poi.json"), col.pois)
    package_mod.write_style(os.path.join(pkg_dir, "style.json"), region_id)

    print("[walkability] сетка...", flush=True)
    t = time.time()
    header, packed, wstats = walk_mod.build_grid(col, bbox, args.tile_size)
    walk_mod.write_grid(os.path.join(pkg_dir, "walkability.grid"), header, packed)
    print(f"[walkability] {time.time() - t:.0f}s: {wstats}")
    walk_mod.render_preview(os.path.join(out_dir, "walkability-preview.png"),
                            header, walk_mod.read_grid(
                                os.path.join(pkg_dir, "walkability.grid"))[1],
                            col, bbox)

    print("[water] маска воды...", flush=True)
    t = time.time()
    wheader, wpacked, water_stats = walk_mod.build_water_mask(col, bbox,
                                                              args.tile_size)
    walk_mod.write_grid(os.path.join(pkg_dir, "water.grid"), wheader, wpacked)
    print(f"[water] {time.time() - t:.0f}s: {water_stats}")
    walk_mod.render_water_preview(os.path.join(out_dir, "water-preview.png"),
                                  wheader, walk_mod.read_grid(
                                      os.path.join(pkg_dir, "water.grid"))[1],
                                  col, bbox)

    import shutil
    shutil.copy(pmtiles, os.path.join(pkg_dir, "tiles.pmtiles"))
    meta = package_mod.write_meta(
        os.path.join(pkg_dir, "meta.json"),
        region_id=region_id, version=args.version,
        tile_size_m=args.tile_size, maxzoom=args.maxzoom, bbox=bbox,
        grid_header=header, poi_count=len(col.pois), boundary_count=bcount,
        wiki_stats=wiki_stats, package_dir=pkg_dir, photo_stats=photo_stats,
        water_grid_header=wheader)
    package_mod.make_zip(zip_path, pkg_dir)

    size_mb = os.path.getsize(zip_path) / 1e6
    print(f"\n[done] {zip_path} = {size_mb:.1f} МБ "
          f"(порог AD-8: ≤ {SIZE_LIMIT_MB} МБ) за {time.time() - t0:.0f}s")
    if size_mb > SIZE_LIMIT_MB:
        print(f"[risk] пакет тяжелее {SIZE_LIMIT_MB} МБ — риск AD-8, "
              f"ручки: --maxzoom ниже / --no-photos / детализация planetiler "
              f"(spike 1.11)")
    for fn in package_mod.PACKAGE_FILES:
        print(f"  {fn}: {os.path.getsize(os.path.join(pkg_dir, fn))} Б")
    n_photos = len(package_mod.package_photos(pkg_dir))
    if n_photos:
        print(f"  photos/: {n_photos} фото, {photo_stats.get('bytes', 0)} Б")

    # --- 6. verify ---
    print("\n[verify]")
    import subprocess
    proc = subprocess.run([sys.executable, os.path.join(ROOT, "verify.py"), zip_path])
    if not args.keep_package:
        shutil.rmtree(pkg_dir, ignore_errors=True)
    sys.exit(proc.returncode)


if __name__ == "__main__":
    main()
