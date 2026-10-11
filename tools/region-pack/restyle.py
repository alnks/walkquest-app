#!/usr/bin/env python3
"""Перестилить собранный пакет без пересборки planetiler.

Подменяет style.json на текущий из region_pack.package.write_style, поднимает
packageVersion на 1, обновляет checksums/generatedAt в meta.json и пишет рядом
новый zip region-<id>-v<n+1>.zip. Тайлы, сетки, POI и фото копируются как есть.

Применение: смена оформления карты (стиля) не требует часы работы planetiler —
source-layer'ы те же (схема OpenMapTiles), меняется только отрисовка.

Выход: путь нового zip и его sha256 (для manifest.json); exit 0 при успехе.
После restyle прогнать verify.py по новому zip.
"""
import hashlib
import json
import os
import sys
import tempfile
import time
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

from region_pack import package as package_mod


def restyle(zip_path):
    z = zipfile.ZipFile(zip_path)
    if z.testzip() is not None:
        sys.exit(f"{zip_path}: zip повреждён (testzip)")
    meta = json.loads(z.read("meta.json"))
    region_id = meta["regionId"]
    new_version = int(meta["packageVersion"]) + 1

    with tempfile.TemporaryDirectory() as td:
        style_path = os.path.join(td, "style.json")
        package_mod.write_style(style_path, region_id)
        with open(style_path, "rb") as f:
            style_bytes = f.read()

    meta["packageVersion"] = new_version
    meta["generatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    meta["generatedBy"] = "tools/region-pack restyle.py"
    meta.setdefault("checksums", {})["style.json"] = \
        hashlib.sha256(style_bytes).hexdigest()
    meta_bytes = json.dumps(meta, ensure_ascii=False, indent=1).encode("utf-8")

    out_path = os.path.join(os.path.dirname(zip_path),
                            f"region-{region_id}-v{new_version}.zip")
    if os.path.exists(out_path):
        sys.exit(f"уже существует: {out_path} — удалите, если нужно пересоздать")
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zo:
        for name in z.namelist():
            if name == "style.json":
                zo.writestr(name, style_bytes)
            elif name == "meta.json":
                zo.writestr(name, meta_bytes)
            else:
                zo.writestr(name, z.read(name))

    digest = hashlib.sha256()
    with open(out_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    print(f"{out_path}")
    print(f"  version: {meta['packageVersion'] - 1} → {new_version}")
    print(f"  sizeBytes: {os.path.getsize(out_path)}")
    print(f"  sha256: {digest.hexdigest()}")
    return out_path


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: restyle.py region-<id>-v<n>.zip [ещё zip ...]")
    for path in sys.argv[1:]:
        restyle(path)


if __name__ == "__main__":
    main()
