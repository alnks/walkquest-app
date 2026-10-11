#!/usr/bin/env python3
"""QA-зеркало истории 1.7 «Прототип записи трека» — проверки без JDK/SDK.

Зеркалит спецификации из Kotlin-кода и проверяет:
1. Формат append-only журнала: запись/чтение по той же спеке, усечённый хвост.
2. Фильтр: haversine + правила вердиктов против констант, извлечённых из TrackModels.kt.
3. Манифест: разрешения FINE/COARSE/POST_NOTIFICATIONS/FOREGROUND_SERVICE(+_LOCATION)
   и сервис с foregroundServiceType="location", exported=false.
4. AD-10: android.*/androidx.*/org.maplibre.* только в TrackingService (внутри tracking).
5. Баланс скобок новых Kotlin-файлов.
"""
import json
import math
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRACKING = os.path.join(ROOT, "app", "src", "main", "java", "app", "walkquest", "tracking")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")

passed = failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"PASS  {name}")
    else:
        failed += 1
        print(f"FAIL  {name}  {detail}")


# ---------- 1. Журнал: зеркало формата ----------

def journal_write(path, points, started=0):
    """Писатель по спеке TrackJournal: строка-заголовок + JSONL точек."""
    lines = [json.dumps({"formatVersion": 1, "startedAtEpochMillis": started})]
    for lat, lon, t in points:
        lines.append(json.dumps({"lat": lat, "lon": lon, "t": t}))
    with open(path, "wb") as f:
        f.write(("\n".join(lines) + "\n").encode("utf-8"))


def journal_read(path):
    """Читатель по спеке TrackJournal.read(): Ok(points, truncated) | Invalid."""
    if not os.path.isfile(path):
        return ("invalid", "missing")
    with open(path, "rb") as f:
        text = f.read().decode("utf-8", errors="replace")
    lines = text.split("\n")
    try:
        header = json.loads(lines[0])
    except Exception:
        return ("invalid", "header")
    if header.get("formatVersion") != 1:
        return ("invalid", "version")
    points, truncated = [], False
    for line in lines[1:]:
        if not line.strip():
            continue
        try:
            p = json.loads(line)
            points.append((p["lat"], p["lon"], p["t"]))
        except Exception:
            truncated = True
            break
    return ("ok", points, truncated)


with tempfile.TemporaryDirectory() as tmp:
    jp = os.path.join(tmp, "track-1.track")
    src = [(59.930 + i * 0.001, 30.33, (i + 1) * 1000) for i in range(5)]
    journal_write(jp, src)
    status, pts, trunc = journal_read(jp)
    check("journal roundtrip preserves 5 points and order", status == "ok" and pts == src and not trunc)

    with open(jp, "rb") as f:
        data = f.read()
    with open(jp, "wb") as f:
        f.write(data[:-7])  # убийство процесса: обрыв mid-line
    status, pts, trunc = journal_read(jp)
    check("journal truncated tail keeps 4 valid points", status == "ok" and pts == src[:4] and trunc)

    with open(jp, "ab") as f:
        f.write(b'{"lat":59.9,"lon":\x00\x01garbage')
    status, pts, trunc = journal_read(jp)
    check("journal garbage tail tolerated", status == "ok" and len(pts) == 4 and trunc)

    bad = os.path.join(tmp, "bad.track")
    with open(bad, "w", encoding="utf-8") as f:
        f.write("не json\n")
    check("journal unreadable header -> Invalid", journal_read(bad)[0] == "invalid")

    v99 = os.path.join(tmp, "v99.track")
    with open(v99, "w", encoding="utf-8") as f:
        f.write(json.dumps({"formatVersion": 99}) + "\n")
    check("journal formatVersion!=1 -> Invalid", journal_read(v99)[0] == "invalid")

    empty = os.path.join(tmp, "empty.track")
    with open(empty, "w", encoding="utf-8") as f:
        f.write(json.dumps({"formatVersion": 1, "startedAtEpochMillis": 0}) + "\n")
    status, pts, trunc = journal_read(empty)
    check("journal header-only reads empty Ok", status == "ok" and pts == [] and not trunc)

    check("journal missing file -> Invalid", journal_read(os.path.join(tmp, "absent.track"))[0] == "invalid")

# Kotlin-писатель и Python-читатель совместимы: образец строки из TrackJournalTest
with tempfile.TemporaryDirectory() as tmp:
    kp = os.path.join(tmp, "kotlin.track")
    with open(kp, "w", encoding="utf-8") as f:
        f.write('{"formatVersion":1,"startedAtEpochMillis":1759617600000}\n')
        f.write('{"lat":59.93,"lon":30.33,"t":1000}\n')
    status, pts, trunc = journal_read(kp)
    check("kotlin-written journal line parses", status == "ok" and pts == [(59.93, 30.33, 1000)])

# ---------- 2. Фильтр: константы из Kotlin + зеркало правил ----------

with open(os.path.join(TRACKING, "TrackModels.kt"), encoding="utf-8") as f:
    models_src = f.read()

def const(name, src):
    m = re.search(rf"{name}\s*=\s*([0-9.]+)", src)
    return float(m.group(1)) if m else None

walking_kmh = const("WALKING_KMH", models_src)
cycling_kmh = const("CYCLING_KMH", models_src)
teleport_window = const("TELEPORT_WINDOW_SECONDS", models_src)
check("SpeedLimits hypotheses are 12/30 km/h", walking_kmh == 12.0 and cycling_kmh == 30.0,
      f"got {walking_kmh}/{cycling_kmh}")
check("teleport window constant present (120 s)", teleport_window == 120.0, f"got {teleport_window}")

EARTH_R = 6_371_000.0

def haversine(a, b):
    dlat = math.radians(b[0] - a[0])
    dlon = math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_R * math.atan2(math.sqrt(h), math.sqrt(1 - h))

def filt_check(anchor, point, limit_mps, teleport_m):
    """Зеркало PointFilter.check (anchor = (lat, lon, t_ms) или None)."""
    if anchor is None:
        return "accepted", point
    dt = point[2] - anchor[2]
    if dt <= 0:
        return "non_monotonic", anchor
    dist = haversine(anchor, point)
    if dist > teleport_m:
        return "teleport", anchor
    if dist / (dt / 1000.0) > limit_mps:
        return "too_fast", anchor
    return "accepted", point

LIMIT = walking_kmh / 3.6
TELEPORT = LIMIT * teleport_window

anchor = None
anchor = filt_check(anchor, (59.930, 30.33, 0), LIMIT, TELEPORT)[1]
v, anchor = filt_check(anchor, (59.931, 30.33, 60_000), LIMIT, TELEPORT)
check("filter accepts walking pace ~6.7 km/h", v == "accepted")
v, anchor_after = filt_check(anchor, (59.932, 30.33, 65_000), LIMIT, TELEPORT)
check("filter rejects 80 km/h as too_fast", v == "too_fast")
v, _ = filt_check(anchor, (59.932, 30.33, 125_000), LIMIT, TELEPORT)
check("filter anchor unmoved after reject (next valid accepted)", v == "accepted")
v, _ = filt_check(anchor, (60.43, 30.33, 3_600_000), LIMIT, TELEPORT)
check("filter rejects ~55 km jump with large dt as teleport", v == "teleport")
v, _ = filt_check(anchor, (60.43, 30.33, 126_000), LIMIT, TELEPORT)
check("filter rejects ~55 km jump in 1 s as teleport", v == "teleport")
v, _ = filt_check(anchor, (59.931, 30.33, 60_000), LIMIT, TELEPORT)
check("filter rejects equal timestamp as non_monotonic", v == "non_monotonic")
# граница: скорость ровно на лимите принимается (строгое >)
a = (59.930, 30.33, 0)
b = (59.931, 30.33, 30_000)
speed = haversine(a, b) / 30.0
v, _ = filt_check(a, b, speed, 1e9)
check("filter boundary: speed == limit accepted", v == "accepted")
v, _ = filt_check(a, b, speed * 0.999, 1e9)
check("filter boundary: speed > limit rejected", v == "too_fast")

# ---------- 3. Манифест ----------

ANDROID = "{http://schemas.android.com/apk/res/android}"
tree = ET.parse(MANIFEST)
root = tree.getroot()
perms = {e.get(ANDROID + "name") for e in root.iter("uses-permission")}
required = {
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
}
check("manifest declares 5 tracking permissions", required <= perms,
      f"missing {required - perms}")
services = list(root.iter("service"))
check("manifest declares exactly one service", len(services) == 1)
if services:
    svc = services[0]
    check("service is TrackingService, not exported, location type",
          svc.get(ANDROID + "name") == ".tracking.TrackingService"
          and svc.get(ANDROID + "exported") == "false"
          and svc.get(ANDROID + "foregroundServiceType") == "location",
          str(svc.attrib))

# ---------- 4. AD-10: android только в TrackingService ----------

FORBIDDEN = re.compile(r"import\s+(android\.|androidx\.|org\.maplibre\.)")
pure_files = ["TrackModels.kt", "PointFilter.kt", "TrackJournal.kt", "TrackingStateBus.kt"]
for name in pure_files:
    with open(os.path.join(TRACKING, name), encoding="utf-8") as f:
        src = f.read()
    check(f"AD-10: {name} has no android imports", not FORBIDDEN.search(src))
with open(os.path.join(TRACKING, "TrackingService.kt"), encoding="utf-8") as f:
    svc_src = f.read()
check("AD-10: TrackingService is the android host (has android imports)", bool(FORBIDDEN.search(svc_src)))
check("TrackingService opens tiles only via domain (openTileAtPoint)",
      "openTileAtPoint" in svc_src and "FogRepository" not in svc_src)

# ---------- 5. Баланс скобок новых Kotlin-файлов ----------

def strip_strings_comments(src):
    src = re.sub(r'"""(?:[^"]|"(?!""))*"""', '""', src, flags=re.S)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    src = re.sub(r"'(?:\\.|[^'\\\n])'", "''", src)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return src

new_kt = pure_files + ["TrackingService.kt"]
test_dir = os.path.join(ROOT, "app", "src", "test", "kotlin", "app", "walkquest", "tracking")
new_kt_paths = [os.path.join(TRACKING, n) for n in new_kt] + [
    os.path.join(test_dir, "PointFilterTest.kt"),
    os.path.join(test_dir, "TrackJournalTest.kt"),
]
for path in new_kt_paths:
    with open(path, encoding="utf-8") as f:
        src = strip_strings_comments(f.read())
    ok = src.count("{") == src.count("}") and src.count("(") == src.count(")")
    check(f"kotlin balance: {os.path.basename(path)}", ok)

print(f"\n{passed} PASS / {failed} FAIL")
sys.exit(1 if failed else 0)
