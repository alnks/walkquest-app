"""Чтение OSM pbf через pyosmium: границы, POI, вода, парки, проходимые линии.

Расширяет Collector из PoC spike 1.1:
- POI также из way-центроидов (музеи/памятники часто мапятся полигонами);
- walkable-полигоны: парки + leisure=garden/nature_reserve, boundary=protected_area,
  place=square, пешеходные площади (highway=pedestrian + area=yes) — риск 3 spike;
- опциональная фильтрация по bbox (для вырезки региона из большого дампа).
"""
import json

import osmium
import osmium.geom
from shapely.geometry import shape

WATER_AREA_TAGS = {
    "natural": {"water", "bay"},
    "waterway": {"riverbank"},
    "landuse": {"reservoir", "harbour", "basin"},
}
# Whitelist POI-маяков — «ядро классических достопримечательностей» (пакет v2,
# уточнение владельца 2026-10-05): музеи, доходные дома/heritage-здания, театры,
# памятники и мемориалы + парки (завязаны на ачивки A15/A16 контент-каталога).
# Всё вне списка — не маяк: shop=* целиком, бытовой amenity (кафе/аптеки/банки/
# салоны/библиотеки), развлекательное tourism (zoo/aquarium/theme_park), спорт
# и досуг в leisure (fitness_centre, sauna, bandstand, playground…), размещение
# в tourism (hotel/hostel/apartment…), инфраструктура man_made (water_tower,
# cross, works…), ландшафтные и природные точки natural (beach/spring/cliff —
# не «классические достопримечательности», пакет v1 их исключил из ядра).
# Места для спальных районов владелец добавляет вручную поверх этого ядра.
POI_TAGS = {
    "tourism": {"attraction", "museum", "gallery", "artwork", "viewpoint"},
    "historic": {"memorial", "monument", "tomb", "castle", "palace", "ruins",
                 "battlefield", "archaeological_site", "church",
                 "wayside_cross", "wayside_shrine", "manor", "gate",
                 "milestone", "boundary_stone", "heritage", "heritage_building",
                 "building", "house", "locomotive", "railway_car", "cannon",
                 "anchor", "aircraft", "ship", "vehicle", "exhibit", "shield",
                 "bridge"},
    "leisure": {"park", "garden", "nature_reserve"},
    "amenity": {"place_of_worship", "theatre", "arts_centre"},
    "man_made": {"lighthouse", "bridge", "tower", "obelisk"},
}
NO_WALK_HIGHWAY = {"motorway", "motorway_link", "trunk", "trunk_link", "construction", "proposed"}
ADMIN_LEVELS = {"2", "4", "6", "8", "9", "10"}
WALK_POLYGON_RULES = (
    lambda t: t.get("leisure") in ("park", "garden", "nature_reserve"),
    lambda t: t.get("boundary") in ("national_park", "protected_area"),
    lambda t: t.get("place") == "square",
    lambda t: t.get("highway") == "pedestrian" and t.get("area") == "yes",
)


def _tags(obj):
    return {t.k: t.v for t in obj.tags}


def poi_category(tags):
    """«key=value» первого совпадения с whitelist POI_TAGS; None — не маяк."""
    for key, values in POI_TAGS.items():
        if tags.get(key) in values:
            return f"{key}={tags[key]}"
    return None


def _in_bbox(lon, lat, bbox):
    return bbox is None or (bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3])


class Collector(osmium.SimpleHandler):
    def __init__(self, bbox=None):
        super().__init__()
        self.bbox = bbox
        self.admin = []        # {admin_level, name, geometry(shapely)}
        self.pois = []         # {osm_id, name, name_ru, category, lon, lat, wikidata}
        self.water_polys = []  # shapely
        self.walk_polys = []   # shapely (парки, скверы, площади, пешеходные зоны)
        self.walk_lines = []   # [[(lon, lat), ...], ...]

    def _multipolygon(self, a):
        try:
            gj = osmium.geom.GeoJSONFactory().create_multipolygon(a)
            g = shape(json.loads(gj))
            if g is not None and not g.is_empty and self.bbox is not None:
                if not g.intersects(_bbox_poly(self.bbox)):
                    return None
            return g
        except Exception:
            return None

    def _way_poly(self, w):
        pts = [(n.lon, n.lat) for n in w.nodes if n.location.valid()]
        if len(pts) < 4 or pts[0] != pts[-1]:
            return None
        try:
            from shapely.geometry import Polygon
            g = Polygon(pts)
            if not g.is_valid or g.is_empty:
                return None
            if self.bbox is not None and not g.intersects(_bbox_poly(self.bbox)):
                return None
            return g
        except Exception:
            return None

    def area(self, a):
        tags = _tags(a)
        g = self._multipolygon(a)
        if g is None:
            return
        if tags.get("boundary") == "administrative" and tags.get("admin_level") in ADMIN_LEVELS:
            self.admin.append({"admin_level": int(tags["admin_level"]),
                               "name": tags.get("name:ru") or tags.get("name"),
                               "geometry": g})
        if any(tags.get(k) in v for k, v in WATER_AREA_TAGS.items()):
            self.water_polys.append(g)
        if any(rule(tags) for rule in WALK_POLYGON_RULES):
            self.walk_polys.append(g)

    def way(self, w):
        tags = _tags(w)
        hw = tags.get("highway")
        if hw and hw not in NO_WALK_HIGHWAY and tags.get("area") != "yes":
            if tags.get("access") in ("private", "no"):
                return
            pts = [(n.lon, n.lat) for n in w.nodes if n.location.valid()]
            pts = [(lon, lat) for lon, lat in pts if _in_bbox(lon, lat, self.bbox)]
            if len(pts) >= 2:
                self.walk_lines.append(pts)
        # POI на полигонах: центроид замкнутого way с именем
        category = poi_category(tags)
        if category and "name" in tags and tags.get("area") != "no":
            g = self._way_poly(w)
            if g is not None:
                c = g.representative_point()
                if _in_bbox(c.x, c.y, self.bbox):
                    self.pois.append({"osm_id": f"w{w.id}", "name": tags["name"],
                                      "name_ru": tags.get("name:ru"),
                                      "category": category,
                                      "lon": c.x, "lat": c.y,
                                      "wikidata": tags.get("wikidata")})
        # пешеходные площади как walkable-полигоны
        if tags.get("highway") == "pedestrian" and tags.get("area") == "yes":
            g = self._way_poly(w)
            if g is not None:
                self.walk_polys.append(g)

    def node(self, n):
        tags = _tags(n)
        category = poi_category(tags)
        if category and "name" in tags and _in_bbox(n.lon, n.lat, self.bbox):
            self.pois.append({"osm_id": n.id, "name": tags["name"],
                              "name_ru": tags.get("name:ru"),
                              "category": category,
                              "lon": n.lon, "lat": n.lat,
                              "wikidata": tags.get("wikidata")})


_BBOX_CACHE = {}


def _bbox_poly(bbox):
    if id(bbox) not in _BBOX_CACHE:
        from shapely.geometry import box
        _BBOX_CACHE[id(bbox)] = box(*bbox)
    return _BBOX_CACHE[id(bbox)]


def read_pbf_bbox(pbf_path):
    """bbox из заголовка pbf; None, если в заголовке его нет."""
    try:
        r = osmium.io.Reader(str(pbf_path))
        box = r.header().box()
        r.close()
        if box.valid():
            return [box.bottom_left.lon, box.bottom_left.lat,
                    box.top_right.lon, box.top_right.lat]
    except Exception:
        pass
    return None


def collect(pbf_path, bbox=None):
    """Полный проход по pbf. Возвращает Collector."""
    c = Collector(bbox=tuple(bbox) if bbox else None)
    c.apply_file(str(pbf_path), locations=True)
    return c
