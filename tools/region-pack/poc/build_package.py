# PoC spike 1.1: сборка файлов пакета региона из OSM pbf.
# Вход: data/monaco.osm.pbf, out/monaco-tiles.pmtiles (уже собран planetiler).
# Выход: out/package/ — boundaries.geojson, poi.json, walkability.grid, style.json, meta.json
# и region-monaco-poc-v1.zip по формату spine (Structural Seed).
import json, math, hashlib, zipfile, os, sys, time
import osmium
from shapely.geometry import shape, mapping, Point, Polygon
from shapely.ops import unary_union

BASE = os.path.dirname(os.path.abspath(__file__))
PBF = os.path.join(BASE, "data", "monaco.osm.pbf")
OUT = os.path.join(BASE, "out", "package")
PMTILES = os.path.join(BASE, "out", "monaco-tiles.pmtiles")
TILE_SIZE_M = 100.0  # параметр генератора (spine: размер тайла — параметр пакета)

# ---------- 1. Чтение OSM ----------
WATER_AREA_TAGS = {"natural": {"water", "bay"}, "waterway": {"riverbank"}, "landuse": {"reservoir", "harbour", "basin"}}
POI_KEYS = ("tourism", "amenity", "historic", "leisure", "shop", "natural", "man_made")
NO_WALK_HIGHWAY = {"motorway", "motorway_link", "trunk", "trunk_link", "construction", "proposed"}
ADMIN_LEVELS = {"2", "4", "6", "8", "9", "10"}

class Collector(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.admin = []      # (admin_level, name, shapely geom)
        self.pois = []
        self.water_polys = []
        self.park_polys = []
        self.walk_lines = []  # проходимые линии (highway и т.п.)
        self.bbox = None

    def _geom(self, a):
        try:
            import osmium.geom
            gj = osmium.geom.GeoJSONFactory().create_multipolygon(a)
            return shape(json.loads(gj))
        except Exception:
            return None

    def area(self, a):
        tags = {t.k: t.v for t in a.tags}
        g = self._geom(a)
        if g is None or g.is_empty:
            return
        if tags.get("boundary") == "administrative" and tags.get("admin_level") in ADMIN_LEVELS:
            self.admin.append({"admin_level": int(tags["admin_level"]),
                               "name": tags.get("name:ru") or tags.get("name"),
                               "geometry": g})
        if any(tags.get(k) in v for k, v in WATER_AREA_TAGS.items()):
            self.water_polys.append(g)
        if tags.get("leisure") == "park" or tags.get("boundary") == "national_park":
            self.park_polys.append(g)

    def way(self, w):
        tags = {t.k: t.v for t in w.tags}
        hw = tags.get("highway")
        if hw and hw not in NO_WALK_HIGHWAY and tags.get("area") != "yes":
            if tags.get("access") in ("private", "no"):
                return
            pts = [(n.lon, n.lat) for n in w.nodes if n.location.valid()]
            if len(pts) >= 2:
                self.walk_lines.append(pts)

    def node(self, n):
        tags = {t.k: t.v for t in n.tags}
        hit = [k for k in POI_KEYS if k in tags]
        if hit and "name" in tags:
            self.pois.append({"osm_id": n.id, "name": tags["name"],
                              "name_ru": tags.get("name:ru"),
                              "category": f"{hit[0]}={tags[hit[0]]}",
                              "lon": n.lon, "lat": n.lat,
                              "description": None,  # выдержки Википедии — история 1.3
                              "wikidata": tags.get("wikidata")})

c = Collector()
t0 = time.time()
c.apply_file(PBF, locations=True)
t_parse = time.time() - t0

os.makedirs(OUT, exist_ok=True)

# ---------- 2. boundaries.geojson ----------
feats = []
for b in sorted(c.admin, key=lambda x: x["admin_level"]):
    g = b["geometry"]
    feats.append({"type": "Feature",
                  "properties": {"admin_level": b["admin_level"], "name": b["name"],
                                 "kind": "country" if b["admin_level"] == 2 else
                                         "region" if b["admin_level"] == 4 else
                                         "city" if b["admin_level"] in (6, 8) else "district"},
                  "geometry": mapping(g)})
boundaries = {"type": "FeatureCollection", "features": feats}
with open(os.path.join(OUT, "boundaries.geojson"), "w", encoding="utf-8") as f:
    json.dump(boundaries, f, ensure_ascii=False)

# ---------- 3. poi.json ----------
with open(os.path.join(OUT, "poi.json"), "w", encoding="utf-8") as f:
    json.dump({"pois": c.pois}, f, ensure_ascii=False)

# ---------- 4. walkability.grid ----------
# Правило PoC: тайл проходим, если через него проходит проходимая линия (highway)
# или он покрыт парком, И его центр не в воде.
water = unary_union(c.water_polys) if c.water_polys else None
parks = unary_union(c.park_polys) if c.park_polys else None

minlon, minlat, maxlon, maxlat = 7.408583, 43.483817, 7.595671, 43.752930  # из pmtiles/pbf header
dy = TILE_SIZE_M / 111320.0
dx = TILE_SIZE_M / (111320.0 * math.cos(math.radians((minlat + maxlat) / 2)))
cols = math.ceil((maxlon - minlon) / dx)
rows = math.ceil((maxlat - minlat) / dy)

grid = bytearray(rows * cols)  # 1 = проходимый
walk_pts = []
for line in c.walk_lines:
    for lon, lat in line:
        col = int((lon - minlon) / dx); row = int((lat - minlat) / dy)
        if 0 <= row < rows and 0 <= col < cols:
            grid[row * cols + col] = 1
if parks:
    for row in range(rows):
        cy = minlat + (row + 0.5) * dy
        for col in range(cols):
            if grid[row * cols + col]:
                continue
            if parks.contains(Point(minlon + (col + 0.5) * dx, cy)):
                grid[row * cols + col] = 1
if water:
    for row in range(rows):
        cy = minlat + (row + 0.5) * dy
        for col in range(cols):
            if grid[row * cols + col] and water.contains(Point(minlon + (col + 0.5) * dx, cy)):
                grid[row * cols + col] = 0

# бинарный формат v0: JSON-заголовок (1 строка) + row-major битпак (1 бит = тайл)
packed = bytearray((rows * cols + 7) // 8)
for i, v in enumerate(grid):
    if v:
        packed[i >> 3] |= (1 << (i & 7))
header = {"cols": cols, "rows": rows, "tileSizeMeters": TILE_SIZE_M,
          "origin": [minlon, minlat], "order": "row-major, lsb-first",
          "walkableCount": sum(grid)}
with open(os.path.join(OUT, "walkability.grid"), "wb") as f:
    f.write((json.dumps(header) + "\n").encode("utf-8"))
    f.write(bytes(packed))

# ---------- 5. style.json (минимальный, слои под схему OpenMapTiles) ----------
style = {
    "version": 8,
    "name": "walkquest-poc",
    "sources": {"region": {"type": "vector", "url": "pmtiles://tiles.pmtiles"}},
    "layers": [
        {"id": "background", "type": "background", "paint": {"background-color": "#e8e4da"}},
        {"id": "water", "type": "fill", "source": "region", "source-layer": "water",
         "paint": {"fill-color": "#9ec9e0"}},
        {"id": "park", "type": "fill", "source": "region", "source-layer": "park",
         "paint": {"fill-color": "#b5d8a8", "fill-opacity": 0.7}},
        {"id": "roads", "type": "line", "source": "region", "source-layer": "transportation",
         "paint": {"line-color": "#ffffff", "line-width": 1.2}},
        {"id": "buildings", "type": "fill", "source": "region", "source-layer": "building",
         "minzoom": 13, "paint": {"fill-color": "#d9d2c4"}},
    ],
}
with open(os.path.join(OUT, "style.json"), "w", encoding="utf-8") as f:
    json.dump(style, f, ensure_ascii=False, indent=1)

# ---------- 6. meta.json + zip ----------
import shutil
shutil.copy(PMTILES, os.path.join(OUT, "tiles.pmtiles"))

def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

files = ["style.json", "tiles.pmtiles", "walkability.grid", "boundaries.geojson", "poi.json"]
meta = {
    "formatVersion": 1,
    "regionId": "monaco-poc",
    "tileSizeMeters": TILE_SIZE_M,
    "bbox": [minlon, minlat, maxlon, maxlat],
    "grid": header,
    "poiCount": len(c.pois),
    "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "checksums": {fn: sha256(os.path.join(OUT, fn)) for fn in files},
}
with open(os.path.join(OUT, "meta.json"), "w", encoding="utf-8") as f:
    json.dump(meta, f, ensure_ascii=False, indent=1)

zip_path = os.path.join(BASE, "out", "region-monaco-poc-v1.zip")
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for fn in files + ["meta.json"]:
        z.write(os.path.join(OUT, fn), fn)

print(f"parse: {t_parse:.1f}s | admin={len(c.admin)} poi={len(c.pois)} "
      f"water={len(c.water_polys)} parks={len(c.park_polys)} walklines={len(c.walk_lines)}")
print(f"grid: {cols}x{rows} = {rows*cols} тайлов, проходимых {sum(grid)} "
      f"({100*sum(grid)/(rows*cols):.1f}%), файл {os.path.getsize(os.path.join(OUT,'walkability.grid'))} Б")
for fn in files + ["meta.json"]:
    print(f"  {fn}: {os.path.getsize(os.path.join(OUT, fn))} Б")
print(f"zip: {zip_path} = {os.path.getsize(zip_path)} Б")
