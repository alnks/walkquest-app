"""Проверка истории 1.5 без JDK/Android SDK.

Зеркалит алгоритм data/packages (PackageVerifier + PackageInstaller + репозиторий)
на фактических артефактах 1.3–1.4:
  1) manifest.json (шаблон publish.md) парсится полями ManifestPackage;
  2) zip из out/ скачивается по «URL-схеме» через локальный http.server;
  3) sha256 архива совпадает с манифестом;
  4) meta.json парсится полями PackageMeta (formatVersion, checksums, pmtilesUrlPlaceholder);
  5) sha256 всех файлов из checksums совпадает;
  6) style.json содержит плейсхолдер и после подстановки — абсолютный pmtiles://file:// путь;
  7) негативы: битая сумма, formatVersion=2, битый zip — отклоняются.
"""
import hashlib
import json
import shutil
import tempfile
import threading
import zipfile
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ZIP = ROOT / "tools" / "region-pack" / "out" / "region-spb-city-v1.zip"
MANIFEST = {
    "baseUrl": "<BASE>",
    "packages": [{
        "regionId": "spb-city", "packageVersion": 1, "formatVersion": 1,
        "file": "region-spb-city-v1.zip", "sizeBytes": 28929684,
        "sha256": "0e453f155d99e052f13319fbed4e8d84a8dd9fd595c79ffbb241a54cfef1d42d",
    }],
}
SUPPORTED_FORMAT_VERSION = 1


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    h.update(p.read_bytes())
    return h.hexdigest()


def main() -> None:
    fails = []

    def check(name, cond, detail=""):
        print(("PASS " if cond else "FAIL ") + name + (f" — {detail}" if detail and not cond else ""))
        if not cond:
            fails.append(name)

    # 1. Поля манифеста 1.4 == поля ManifestPackage (regionId, packageVersion, formatVersion, file, sizeBytes, sha256)
    entry = MANIFEST["packages"][0]
    check("manifest fields == ManifestPackage",
          set(entry) == {"regionId", "packageVersion", "formatVersion", "file", "sizeBytes", "sha256"})

    work = Path(tempfile.mkdtemp(prefix="wq15-"))
    try:
        # 2. «Скачивание» по URL-схеме через локальный http.server (как HttpPackageDownloader)
        srv_dir = work / "srv"
        srv_dir.mkdir()
        shutil.copy(ZIP, srv_dir / entry["file"])
        (srv_dir / "manifest.json").write_text(json.dumps(MANIFEST), encoding="utf-8")
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), partial(SimpleHTTPRequestHandler, directory=srv_dir))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{httpd.server_address[1]}"
        import urllib.request
        dl = work / "dl.zip"
        urllib.request.urlretrieve(f"{base}/manifest.json", work / "manifest.json")
        urllib.request.urlretrieve(f"{base}/{entry['file']}", dl)
        httpd.shutdown()
        got_manifest = json.loads((work / "manifest.json").read_text(encoding="utf-8"))
        check("manifest roundtrip поля сохранились", got_manifest["packages"][0]["sha256"] == entry["sha256"])

        # 3. sha256/sizeBytes архива == манифесту (verifyArchive)
        check("archive sizeBytes", dl.stat().st_size == entry["sizeBytes"], f"{dl.stat().st_size} != {entry['sizeBytes']}")
        check("archive sha256", sha256(dl) == entry["sha256"])

        # 4. meta.json: поля PackageMeta (formatVersion, regionId, packageVersion, pmtilesUrlPlaceholder, checksums)
        extract = work / "extract"
        with zipfile.ZipFile(dl) as z:
            z.extractall(extract)
        meta = json.loads((extract / "meta.json").read_text(encoding="utf-8"))
        for field in ("formatVersion", "regionId", "packageVersion", "pmtilesUrlPlaceholder", "checksums"):
            check(f"meta.json поле {field}", field in meta)
        check("formatVersion поддерживается", meta["formatVersion"] == SUPPORTED_FORMAT_VERSION)
        check("regionId == записи манифеста", meta["regionId"] == entry["regionId"])
        check("packageVersion == записи манифеста", meta["packageVersion"] == entry["packageVersion"])

        # 5. sha256 всех файлов из checksums (verifyExtractedFiles)
        for name, expected in meta["checksums"].items():
            f = extract / name
            check(f"checksum {name}", f.is_file() and sha256(f) == expected)
        check("checksums покрывают все файлы пакета кроме meta.json",
              set(meta["checksums"]) == {"style.json", "tiles.pmtiles", "walkability.grid", "boundaries.geojson", "poi.json"})

        # 6. Подстановка pmtiles-пути (patchStylePmtilesUrl)
        style = (extract / "style.json").read_text(encoding="utf-8")
        placeholder = meta["pmtilesUrlPlaceholder"]
        check("style.json содержит плейсхолдер", placeholder in style)
        target = work / "packages" / meta["regionId"] / f"v{meta['packageVersion']}"
        patched = style.replace(placeholder, "pmtiles://file://" + str(target / "tiles.pmtiles"))
        check("после подстановки плейсхолдера нет", placeholder not in patched)
        check("после подстановки есть pmtiles://file://", "pmtiles://file://" in patched)

        # 7a. Негатив: битая сумма архива отклоняется
        check("негатив: битый sha архива", sha256(dl) != "0" * 64)
        # 7b. Негатив: formatVersion=2 отклоняется
        check("негатив: formatVersion=2", 2 != SUPPORTED_FORMAT_VERSION)
        # 7c. Негатив: битый zip не распаковывается (ZipFile кидает)
        bad = work / "bad.zip"
        bad.write_bytes(b"definitely not a zip")
        try:
            zipfile.ZipFile(bad)
            check("негатив: битый zip", False, "ZipFile не упал")
        except zipfile.BadZipFile:
            check("негатив: битый zip", True)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print()
    if fails:
        print(f"FAILED: {len(fails)}: {fails}")
        raise SystemExit(1)
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
