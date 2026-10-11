"""Обёртка planetiler: OSM pbf → tiles.pmtiles (риски 4–5 spike 1.1).

Бинарники — portable, лежат в tools/region-pack/bin/ (не коммитим):
  bin/planetiler.jar (0.10.2), bin/jdk-25.0.4.1+1-jre/ (Temurin), bin/pmtiles.exe.
Кэш источников (Natural Earth, water polygons ~1.4 ГБ) — data/sources, скачивается
один раз при первом прогоне с --download.
"""
import glob
import os
import subprocess
import sys

PLANETILER_JAR = "planetiler.jar"
JRE_GLOB = "jdk-*/bin/java.exe"
PMTILES_EXE = "pmtiles.exe"


def bin_dir(root):
    return os.path.join(root, "bin")


def find_java(root):
    hits = glob.glob(os.path.join(bin_dir(root), JRE_GLOB))
    if not hits:
        sys.exit(f"portable JRE не найден по {os.path.join(bin_dir(root), JRE_GLOB)} — "
                 f"см. README (источники бинарников)")
    return hits[0]


def find_jar(root):
    jar = os.path.join(bin_dir(root), PLANETILER_JAR)
    if not os.path.exists(jar):
        sys.exit(f"planetiler не найден: {jar} — см. README (источники бинарников)")
    return jar


def build_tiles(root, pbf_path, out_pmtiles, maxzoom, bbox=None, heap="4g"):
    """Запускает planetiler. CWD = root, чтобы кэш источников ложился в data/sources."""
    cmd = [find_java(root), f"-Xmx{heap}", "-jar", find_jar(root),
           f"--osm-path={pbf_path}", f"--output={out_pmtiles}",
           f"--maxzoom={maxzoom}", "--download", "--force"]
    if bbox:
        cmd.append("--bounds=" + ",".join(str(v) for v in bbox))
    print("[tiles] " + " ".join(cmd), flush=True)
    proc = subprocess.run(cmd, cwd=root)
    if proc.returncode != 0:
        sys.exit(f"planetiler завершился с кодом {proc.returncode}")
    if not os.path.exists(out_pmtiles):
        sys.exit(f"planetiler не создал {out_pmtiles}")
    return out_pmtiles


def pmtiles_show(root, pmtiles_path, metadata=False):
    """go-pmtiles CLI: заголовок или метаданные pmtiles (для verify)."""
    exe = os.path.join(bin_dir(root), PMTILES_EXE)
    if not os.path.exists(exe):
        sys.exit(f"go-pmtiles CLI не найден: {exe} — см. README")
    cmd = [exe, "show"] + (["--metadata"] if metadata else []) + [pmtiles_path]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr
