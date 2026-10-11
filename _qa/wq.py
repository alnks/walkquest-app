#!/usr/bin/env python3
"""wq — единый harness отладки и проверки WalkQuest (эпик map-foundation и далее).

Заменяет ручные связки из _qa/emulator-run/fixN-verify: запуск эмулятора,
сборка+установка с защитой от протухшего APK, подкладка пакета через run-as,
инжект геолокации и replay треков с безопасным темпом, скриншоты, попиксельный
diff, дамп UI, logcat, локальный сервер пакетов и установка на телефон по USB.

Только stdlib; Pillow нужен лишь для `diff` (в managed-python он есть).

Примеры:
  python _qa/wq.py doctor
  python _qa/wq.py emu-start
  python _qa/wq.py install                 # сборка + установка на эмулятор
  python _qa/wq.py phone-install           # сборка + установка на телефон по USB
  python _qa/wq.py seed-package            # подложить пакет из tools/region-pack/out
  python _qa/wq.py replay fix7-walk.track  # проиграть журнал как geo fix'ы
  python _qa/wq.py shot after-walk.png
  python _qa/wq.py serve                   # локальный сервер пакетов :8777
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "app.walkquest"
DEFAULT_AVD = "Medium_Phone_API_37.0"
DEFAULT_PORT = 8777
STATE_FILE = PROJECT_ROOT / "_qa" / ".wq-state.json"
OUT_DIR = PROJECT_ROOT / "tools" / "region-pack" / "out"

# Пеший лимит PointFilter ≈ 12 км/ч (1.7). Держим темп replay ниже него,
# иначе adb-фиксы прилетают быстрее реальных и фильтр бракует точки (fix7).
WALK_MAX_SPEED_MPS = 3.0


# ----------------------------------------------------------------- helpers

def sdk_root() -> Path:
    props = PROJECT_ROOT / "local.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir"):
                raw = line.split("=", 1)[1].strip().replace("\\\\", "\\").replace("\\:", ":")
                return Path(raw)
    default = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
    return default


def adb() -> str:
    return str(sdk_root() / "platform-tools" / "adb.exe")


def emulator_bin() -> str:
    return str(sdk_root() / "emulator" / "emulator.exe")


def run(cmd: list[str], *, check: bool = True, capture: bool = True,
        timeout: int = 120) -> subprocess.CompletedProcess:
    printable = " ".join(str(c) for c in cmd)
    print(f"$ {printable}")
    p = subprocess.run(cmd, capture_output=capture, text=True, timeout=timeout)
    if check and p.returncode != 0:
        print(p.stdout or "")
        print(p.stderr or "", file=sys.stderr)
        sys.exit(f"FAIL: команда завершилась с кодом {p.returncode}")
    return p


def adb_args(serial: str | None) -> list[str]:
    return [adb()] + (["-s", serial] if serial else [])


def pick_device(serial: str | None, *, want: str = "any") -> str | None:
    """want: 'any' | 'emulator' | 'phone'. Падает с понятной ошибкой."""
    if serial:
        return serial
    out = run([adb(), "devices"], check=True).stdout
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device":
            devices.append(parts[0])
    if want == "emulator":
        devices = [d for d in devices if d.startswith("emulator-")]
    elif want == "phone":
        devices = [d for d in devices if not d.startswith("emulator-")]
    if not devices:
        sys.exit(f"FAIL: нет устройства в состоянии 'device' (want={want}). "
                 f"Запустите эмулятор (wq.py emu-start) или подключите телефон по USB "
                 f"с включённой отладкой.")
    if len(devices) > 1:
        sys.exit(f"FAIL: несколько устройств {devices} — укажите --serial.")
    return devices[0]


def load_state() -> dict:
    if STATE_FILE.is_file():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def apk_path() -> Path:
    return PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def gradle(task: str) -> None:
    gradlew = PROJECT_ROOT / "gradlew.bat"
    run(["cmd", "/c", str(gradlew), task], capture=False, timeout=600)


def run_as(serial: str | None, sh_script: str) -> subprocess.CompletedProcess:
    """Выполняет sh-скрипт от имени приложения. Скрипт пушится файлом и
    исполняется `sh /data/local/tmp/...` — `sh -c` через adb shell ломается
    об операторы (&&), т.к. adb склеивает argv пробелами без кавычек."""
    with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False,
                                     encoding="utf-8", newline="\n") as f:
        f.write(sh_script + "\n")
        local = f.name
    remote = f"/data/local/tmp/{Path(local).name}"
    try:
        run(adb_args(serial) + ["push", local, remote])
        run(adb_args(serial) + ["shell", "chmod", "644", remote])
        return run(adb_args(serial) + ["shell", "run-as", PACKAGE, "sh", remote])
    finally:
        os.unlink(local)


# ----------------------------------------------------------------- commands

def cmd_devices(_a) -> None:
    run([adb(), "devices", "-l"], capture=False)


def cmd_doctor(_a) -> None:
    ok = True
    for label, path in [("adb", adb()), ("emulator", emulator_bin())]:
        exists = Path(path).is_file()
        ok &= exists
        print(f"{'OK ' if exists else 'НЕТ'} {label}: {path}")
    try:
        import PIL  # noqa: F401
        print("OK  Pillow (нужен для diff)")
    except ImportError:
        print("НЕТ Pillow — diff недоступен (managed-python его содержит)")
    out = run([adb(), "devices", "-l"], check=False).stdout
    print("--- устройства ---")
    print(out.strip() or "(пусто)")
    if not ok:
        sys.exit(1)


def cmd_emu_start(a) -> None:
    out = run([adb(), "devices"], check=False).stdout
    if "emulator-" in out and "\tdevice" in out:
        print("Эмулятор уже запущен.")
        return
    cmd = [emulator_bin(), "-avd", a.avd,
           "-no-snapshot-save", "-no-snapshot-load", "-gpu", "auto"]
    # -gpu auto (gfxstream): swiftshader_indirect вешает гостя в adb-offline (REPORT.md 2026-10-05).
    print(f"$ {' '.join(cmd)}  (в фоне)")
    subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     creationflags=getattr(subprocess, "DETACHED_PROCESS", 0))
    print("Жду загрузку гостя (первый раз ~98 с)...")
    deadline = time.time() + 300
    while time.time() < deadline:
        p = run([adb(), "shell", "getprop", "sys.boot_completed"], check=False)
        if p.stdout.strip() == "1":
            print("Эмулятор загрузился.")
            # GPS-провайдер эмулятора может быть выключен (замечание fix10).
            run([adb(), "shell", "settings", "put", "secure",
                 "location_providers_enabled", "+gps"], check=False)
            return
        time.sleep(3)
    sys.exit("FAIL: эмулятор не загрузился за 300 с.")


def cmd_build(a) -> None:
    apk = apk_path()
    before = apk.stat().st_mtime if apk.is_file() else 0.0
    gradle("assembleRelease" if a.release else "assembleDebug")
    if a.release:
        print("OK: release собран (install работает с debug APK).")
        return
    # Защита от протухшего APK: fix9 — adb install дважды молча ставил старую сборку.
    # Критерий свежести: APK должен быть новее всех исходников. Если сборка
    # UP-TO-DATE, mtime APK не двигается — и это нормально, пока исходники старше.
    after = apk.stat().st_mtime if apk.is_file() else 0.0
    if after <= before:
        newest_src = 0.0
        for root in (PROJECT_ROOT / "app" / "src",):
            for f in root.rglob("*"):
                if f.is_file():
                    newest_src = max(newest_src, f.stat().st_mtime)
        for f in ("gradle/libs.versions.toml", "app/build.gradle.kts"):
            p = PROJECT_ROOT / f
            if p.is_file():
                newest_src = max(newest_src, p.stat().st_mtime)
        if after < newest_src:
            sys.exit(f"FAIL: {apk} старше исходников (apk mtime {after}, "
                     f"новейший исходник {newest_src}) — сборка молча не отработала, "
                     f"установку не выполнять!")
        print("Сборка UP-TO-DATE: APK не пересобирался, но новее всех исходников — свежий.")
    print(f"OK: APK {apk} ({apk.stat().st_size} байт)")


def cmd_install(a) -> None:
    if not a.no_build:
        cmd_build(a)
    serial = pick_device(a.serial, want="any")
    apk = apk_path()
    run(adb_args(serial) + ["install", "-r", str(apk)], timeout=300)
    p = run(adb_args(serial) + ["shell", "dumpsys", "package", PACKAGE], check=False)
    m = re.search(r"lastUpdateTime=([^\n]+)", p.stdout)
    print(f"Установлено на {serial}. lastUpdateTime: {m.group(1).strip() if m else '?'}")
    print("Сверьте lastUpdateTime с текущим временем — защита от протухшей сборки.")


def cmd_emu_geo(a) -> None:
    serial = pick_device(a.serial, want="emulator")
    lat, lon = a.lat, a.lon
    # Повторный fix с теми же координатами съедается minUpdateDistance=2м (fix10) —
    # если точка совпадает с прошлой инжекцией, сдвигаем на ~5 м.
    state = load_state()
    last = state.get("last_geo")
    if last and abs(last[0] - lat) < 1e-5 and abs(last[1] - lon) < 1e-5:
        lon += 0.00008  # ~5 м на широте СПб
        print(f"Точка совпадает с прошлой инжекцией — сдвиг lon +5 м -> {lon:.6f}")
    # adb emu geo fix принимает СНАЧАЛА долготу, потом широту.
    run(adb_args(serial) + ["emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}"])
    state["last_geo"] = [lat, lon]
    save_state(state)
    print(f"geo fix: {lat:.6f}, {lon:.6f}")


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def cmd_replay(a) -> None:
    """Проигрывает .track-журнал (JSONL) как серию geo fix с безопасным темпом."""
    serial = pick_device(a.serial, want="emulator")
    points = []
    for line in Path(a.track).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            break  # усечённый хвост журнала — как читает приложение
        if "lat" in obj and "lon" in obj:
            points.append((float(obj["lat"]), float(obj["lon"])))
    if not points:
        sys.exit("FAIL: в журнале нет точек.")
    print(f"Точек в журнале: {len(points)}. Прогрев: первый fix за 3 с до «старта».")
    ns = argparse.Namespace(lat=points[0][0], lon=points[0][1], serial=serial)
    cmd_emu_geo(ns)
    time.sleep(3)
    prev = points[0]
    for i, (lat, lon) in enumerate(points[1:], start=2):
        dist = haversine_m(prev[0], prev[1], lat, lon)
        # Темп не быстрее пешего лимита фильтра, иначе точки улетят в TooFast.
        wait = max(a.min_interval, dist / (a.max_speed or WALK_MAX_SPEED_MPS))
        wait = min(wait, a.max_interval)
        time.sleep(wait)
        ns = argparse.Namespace(lat=lat, lon=lon, serial=serial)
        cmd_emu_geo(ns)
        prev = (lat, lon)
        if i % 10 == 0:
            print(f"  …{i}/{len(points)}")
    print(f"Готово: {len(points)} точек.")


def cmd_shot(a) -> None:
    serial = pick_device(a.serial)
    out = Path(a.name)
    p = subprocess.run(adb_args(serial) + ["exec-out", "screencap", "-p"],
                       capture_output=True, timeout=60)
    if p.returncode != 0 or not p.stdout:
        sys.exit("FAIL: screencap не удался.")
    out.write_bytes(p.stdout)
    print(f"OK: {out} ({out.stat().st_size} байт)")


def cmd_uidump(a) -> None:
    serial = pick_device(a.serial)
    out = Path(a.name)
    run(adb_args(serial) + ["shell", "uiautomator", "dump", "/sdcard/wq-ui.xml"])
    run(adb_args(serial) + ["pull", "/sdcard/wq-ui.xml", str(out)])
    print(f"OK: {out}")


def cmd_diff(a) -> None:
    try:
        from PIL import Image, ImageChops
    except ImportError:
        sys.exit("FAIL: нужен Pillow (в managed-python предустановлен).")
    im1 = Image.open(a.a).convert("RGB")
    im2 = Image.open(a.b).convert("RGB")
    if im1.size != im2.size:
        sys.exit(f"FAIL: размеры различаются {im1.size} vs {im2.size}")
    diff = ImageChops.difference(im1, im2)
    px = diff.load()
    changed = sum(
        1 for y in range(im1.size[1]) for x in range(im1.size[0])
        if max(px[x, y]) > a.threshold
    )
    total = im1.size[0] * im1.size[1]
    print(f"Изменено пикселей: {changed} / {total} ({100.0 * changed / total:.3f} %), "
          f"порог канала > {a.threshold}")
    if a.out:
        diff.save(a.out)
        print(f"OK: {a.out}")


def cmd_log(a) -> None:
    serial = pick_device(a.serial)
    if a.clear:
        run(adb_args(serial) + ["logcat", "-c"])
        print("logcat очищен.")
    tags = a.tags or ["WalkQuestFog", "TrackingService"]
    run(adb_args(serial) + ["logcat", "-d"] + [f"{t}:D" for t in tags] + ["*:S"],
        capture=False)


def cmd_seed_package(a) -> None:
    """Подкладывает пакет региона в приложение через run-as (debug-сборки)."""
    serial = pick_device(a.serial)
    manifest_path = Path(a.manifest or OUT_DIR / "manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    entry = next(p for p in manifest["packages"] if p["regionId"] == a.region)
    zip_path = Path(a.zip) if a.zip else OUT_DIR / entry["file"]
    region, ver = entry["regionId"], entry["packageVersion"]
    print(f"Пакет: {zip_path} -> {region}/v{ver}")

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        with zipfile.ZipFile(zip_path) as z:
            z.extractall(tmp_path)
        # Шаг, который штатно делает PackageInstaller.patchStylePmtilesUrl:
        style = tmp_path / "style.json"
        device_dir = f"/data/data/{PACKAGE}/files/packages/{region}/v{ver}"
        text = style.read_text(encoding="utf-8")
        placeholder = "pmtiles://tiles.pmtiles"
        if placeholder not in text:
            sys.exit("FAIL: style.json не содержит плейсхолдер pmtiles://tiles.pmtiles")
        style.write_text(text.replace(placeholder, f"pmtiles://file://{device_dir}/tiles.pmtiles"),
                         encoding="utf-8")
        tar_path = tmp_path / "pkg.tar"
        with tarfile.open(tar_path, "w") as tf:
            for f in sorted(tmp_path.rglob("*")):
                if f != tar_path:
                    tf.add(f, arcname=f.relative_to(tmp_path))
        # manifest-cache.json — как писал бы приложение после загрузки манифеста;
        # sizeBytes/sha256 зануляем: локальная подкладка не для проверки обновлений.
        cache = {"baseUrl": "", "packages": [
            {k: (0 if k == "sizeBytes" else "0" * 64 if k == "sha256" else v)
             for k, v in entry.items() if k != "file"} | {"file": entry["file"]}
            for entry in manifest["packages"]]}
        cache_path = tmp_path / "manifest-cache.json"
        cache_path.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
        settings_path = tmp_path / "settings.json"
        settings_path.write_text(
            json.dumps({"formatVersion": 1, "homeRegionId": region}), encoding="utf-8")

        run(adb_args(serial) + ["push", str(tar_path), "/data/local/tmp/wq-pkg.tar"], timeout=300)
        run(adb_args(serial) + ["shell", "chmod", "644", "/data/local/tmp/wq-pkg.tar"])
        run(adb_args(serial) + ["push", str(cache_path), "/data/local/tmp/wq-manifest-cache.json"])
        run(adb_args(serial) + ["push", str(settings_path), "/data/local/tmp/wq-settings.json"])

    run_as(serial, f"mkdir -p files/packages/{region}/v{ver} files/settings "
                   f"&& tar -xf /data/local/tmp/wq-pkg.tar -C files/packages/{region}/v{ver} "
                   f"&& cp /data/local/tmp/wq-manifest-cache.json files/packages/manifest-cache.json "
                   f"&& cp /data/local/tmp/wq-settings.json files/settings/settings.json "
                   f"&& ls files/packages/{region}/v{ver} && echo SEED-OK")
    print("OK: пакет подложен. Перезапустите приложение (force-stop + запуск).")


def cmd_simulate(a) -> None:
    """Симуляция трека в debug-сборке (история 1.15): файл → files/qa/sim-track.track,
    сервис проигрывает его через штатный путь записи (onLocation → фильтр → тайлы)."""
    serial = pick_device(a.serial)
    track = Path(a.track)
    if not track.is_file():
        sys.exit(f"FAIL: файл не найден: {track}")
    # FGS типа location требует выданного разрешения даже без живого GPS.
    run(adb_args(serial) + ["shell", "pm", "grant", PACKAGE,
                            "android.permission.ACCESS_FINE_LOCATION"], check=False)
    run_as(serial, "mkdir -p files/qa")
    run(adb_args(serial) + ["push", str(track), "/data/local/tmp/wq-sim-track.track"])
    run(adb_args(serial) + ["shell", "chmod", "644", "/data/local/tmp/wq-sim-track.track"])
    run_as(serial, "cp /data/local/tmp/wq-sim-track.track files/qa/sim-track.track && echo SIM-FILE-OK")
    p = run(adb_args(serial) + [
        "shell", "am", "start-foreground-service",
        "-n", f"{PACKAGE}/.tracking.TrackingService",
        "-a", "app.walkquest.tracking.action.SIMULATE",
        "--ef", "speedFactor", str(a.speed),
    ], check=False)
    out = (p.stdout or "") + (p.stderr or "")
    print(out.strip())
    if "Error" in out or p.returncode != 0:
        sys.exit("FAIL: сервис симуляции не стартовал (приложение установлено? debug-сборка?)")
    print(f"OK: симуляция {track.name} запущена (speed x{a.speed}). "
          f"Стоп: adb shell am startservice -n {PACKAGE}/.tracking.TrackingService "
          f"-a app.walkquest.tracking.action.STOP")


def cmd_push_base_url(a) -> None:
    """Debug-хук: files/qa/packages-base-url.txt (читается только в DEBUG-сборке)."""
    serial = pick_device(a.serial)
    url = a.url
    if url and not url.startswith(("http://", "https://")):
        sys.exit("FAIL: url должен начинаться с http:// или https://")
    if url:
        run_as(serial, f"mkdir -p files/qa && printf '%s' '{url}' > files/qa/packages-base-url.txt "
                       f"&& cat files/qa/packages-base-url.txt")
        print(f"OK: baseUrl = {url} (перезапустите приложение)")
    else:
        run_as(serial, "rm -f files/qa/packages-base-url.txt")
        print("OK: override снят, вернётся DEFAULT_BASE_URL (перезапустите приложение)")


def cmd_serve(a) -> None:
    import functools
    import http.server
    root = Path(a.root)
    if not (root / "manifest.json").is_file():
        sys.exit(f"FAIL: в {root} нет manifest.json — укажите --root tools/region-pack/out")
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(root))
    print(f"Сервер пакетов: http://0.0.0.0:{a.port} -> {root}")
    print(f"  эмулятор:  python _qa/wq.py push-base-url http://10.0.2.2:{a.port}")
    print(f"  телефон:   adb reverse tcp:{a.port} tcp:{a.port} && "
          f"python _qa/wq.py push-base-url http://localhost:{a.port}")
    http.server.ThreadingHTTPServer(("0.0.0.0", a.port), handler).serve_forever()


def cmd_phone_install(a) -> None:
    """Установка на телефон по USB: сборка, установка, adb reverse для локального сервера."""
    serial = pick_device(a.serial, want="phone")
    cmd_install(argparse.Namespace(serial=serial, no_build=a.no_build, release=False))
    if a.reverse:
        run(adb_args(serial) + ["reverse", f"tcp:{a.port}", f"tcp:{a.port}"])
        print(f"adb reverse настроен: телефон localhost:{a.port} -> ПК :{a.port}")
        if a.local_server:
            cmd_push_base_url(argparse.Namespace(serial=serial,
                                                 url=f"http://localhost:{a.port}"))
    if a.launch:
        run(adb_args(serial) + ["shell", "monkey", "-p", PACKAGE,
                                "-c", "android.intent.category.LAUNCHER", "1"],
            check=False)
    print("Готово. Полный цикл: сборка -> установка -> запуск — одной командой.")


def cmd_clear(a) -> None:
    serial = pick_device(a.serial)
    run(adb_args(serial) + ["shell", "pm", "clear", PACKAGE])
    print("OK: данные приложения очищены (чистая установка).")


def cmd_force_stop(a) -> None:
    serial = pick_device(a.serial)
    run(adb_args(serial) + ["shell", "am", "force-stop", PACKAGE])


def cmd_launch(a) -> None:
    serial = pick_device(a.serial)
    run(adb_args(serial) + ["shell", "monkey", "-p", PACKAGE,
                            "-c", "android.intent.category.LAUNCHER", "1"], check=False)


# ----------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", help="serial устройства (adb devices)", default=None)
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("doctor", help="проверка окружения").set_defaults(f=cmd_doctor)
    sub.add_parser("devices", help="список устройств (adb devices -l)").set_defaults(f=cmd_devices)

    p = sub.add_parser("emu-start", help="запуск эмулятора (-gpu auto) и ожидание загрузки")
    p.add_argument("--avd", default=DEFAULT_AVD)
    p.set_defaults(f=cmd_emu_start)

    p = sub.add_parser("build", help="gradle-сборка с защитой от протухшего APK")
    p.add_argument("--release", action="store_true")
    p.set_defaults(f=cmd_build)

    p = sub.add_parser("install", help="сборка + adb install на любое устройство")
    p.add_argument("--no-build", action="store_true")
    p.add_argument("--release", action="store_true", help=argparse.SUPPRESS)
    p.set_defaults(f=cmd_install)

    p = sub.add_parser("phone-install",
                       help="телефон по USB: сборка, установка, adb reverse, запуск")
    p.add_argument("--no-build", action="store_true")
    p.add_argument("--no-reverse", dest="reverse", action="store_false")
    p.add_argument("--local-server", action="store_true",
                   help="после reverse прописать base-url http://localhost:PORT (debug)")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--launch", action="store_true", default=True)
    p.set_defaults(f=cmd_phone_install)

    p = sub.add_parser("seed-package", help="подложить пакет региона через run-as")
    p.add_argument("--zip", default=None)
    p.add_argument("--manifest", default=None)
    p.add_argument("--region", default="spb-city")
    p.set_defaults(f=cmd_seed_package)

    p = sub.add_parser("simulate",
                       help="проиграть .track-фикстуру внутри приложения (debug, 1.15)")
    p.add_argument("track")
    p.add_argument("--speed", type=float, default=1.0,
                   help="масштаб темпа: 2.0 = вдвое быстрее (default 1.0)")
    p.set_defaults(f=cmd_simulate)

    p = sub.add_parser("push-base-url",
                       help="debug-override источника пакетов (без аргумента — снять)")
    p.add_argument("url", nargs="?", default=None)
    p.set_defaults(f=cmd_push_base_url)

    p = sub.add_parser("geo", help="один geo fix (lat lon), автосдвиг при повторе")
    p.add_argument("lat", type=float)
    p.add_argument("lon", type=float)
    p.set_defaults(f=cmd_emu_geo)

    p = sub.add_parser("replay", help="проиграть .track журнал как серию geo fix")
    p.add_argument("track")
    p.add_argument("--min-interval", type=float, default=1.2,
                   help="минимальная пауза между fix'ами, с (default 1.2)")
    p.add_argument("--max-interval", type=float, default=8.0,
                   help="максимальная пауза, с (default 8)")
    p.add_argument("--max-speed", type=float, default=WALK_MAX_SPEED_MPS,
                   help="потолок темпа, м/с — ниже лимита PointFilter (default 3.0)")
    p.set_defaults(f=cmd_replay)

    p = sub.add_parser("shot", help="скриншот устройства в файл")
    p.add_argument("name")
    p.set_defaults(f=cmd_shot)

    p = sub.add_parser("uidump", help="uiautomator dump в файл")
    p.add_argument("name", nargs="?", default="ui.xml")
    p.set_defaults(f=cmd_uidump)

    p = sub.add_parser("diff", help="попиксельный diff двух скриншотов")
    p.add_argument("a")
    p.add_argument("b")
    p.add_argument("--out", default=None)
    p.add_argument("--threshold", type=int, default=12)
    p.set_defaults(f=cmd_diff)

    p = sub.add_parser("log", help="logcat по QA-тегам приложения")
    p.add_argument("--tags", nargs="*", default=None)
    p.add_argument("--clear", action="store_true")
    p.set_defaults(f=cmd_log)

    p = sub.add_parser("serve", help="локальный сервер пакетов (manifest + zip)")
    p.add_argument("--root", default=str(OUT_DIR))
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.set_defaults(f=cmd_serve)

    sub.add_parser("clear", help="pm clear — чистая установка").set_defaults(f=cmd_clear)
    sub.add_parser("force-stop", help="force-stop приложения").set_defaults(f=cmd_force_stop)
    sub.add_parser("launch", help="запуск приложения").set_defaults(f=cmd_launch)

    args = ap.parse_args()
    # --serial может идти и до, и после подкоманды — прокидываем.
    if not getattr(args, "serial", None):
        args.serial = None
    args.f(args)


if __name__ == "__main__":
    main()
