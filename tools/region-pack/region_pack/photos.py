"""Фото POI для карточек (пакет v2): Wikidata P18 → thumbnail Wikimedia ~320px.

Цепочка: wikidata-id → P18 (имя файла на Commons) через wbgetentities (батчи 50)
→ URL миниатюры через Commons imageinfo (iiurlwidth=320, батчи 50) → скачивание
и сохранение как photos/poi-<osmId>.jpg (jpeg; не-jpeg конвертируется через PIL).

Вес пакета (AD-8): режим `only_described=True` (дефолт) включает фото только для
POI с описанием Википедии — ядро карточек; полный набор раздувает пакет.

Честная деградация (как wiki.py): сетевая ошибка не роняет сборку, статус в
meta.json → photos.status:
  "ok"          — все запрошенные id обработаны;
  "partial"     — часть батчей упала, есть хотя бы одно фото;
  "unavailable" — API недоступен, фото нет;
  "skipped"     --no-photos.
Кэш: cache/photos/{qid}.json (метаданные) и cache/photos/img/<md5>.jpg (байты) —
повторные сборки не ходят в сеть.
"""
import hashlib
import io
import json
import os
import time

import requests

USER_AGENT = "WalkQuestRegionPack/2.0 (region-pack generator; contact: author-local)"
TIMEOUT = 30
THUMB_WIDTH = 320
MAX_PHOTO_BYTES = 120 * 1024  # потолок одной миниатюры; больше — пережимаем

WIKIDATA_API = "https://www.wikidata.org/w/api.php"
COMMONS_API = "https://commons.wikimedia.org/w/api.php"


def _cached(cache_dir, qid):
    p = os.path.join(cache_dir, f"{qid}.json")
    if os.path.exists(p):
        try:
            with open(p, encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return None
    return None


def _store(cache_dir, qid, value):
    os.makedirs(cache_dir, exist_ok=True)
    with open(os.path.join(cache_dir, f"{qid}.json"), "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False)


def _chunks(seq, n):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def _get_json(session, url, params, retries=3):
    """GET с ретраями и backoff; None, если батч так и не удался."""
    delay = 2.0
    for attempt in range(retries):
        try:
            resp = session.get(url, params=params, timeout=TIMEOUT)
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException:
            if attempt == retries - 1:
                return None
            time.sleep(delay)
            delay *= 2
    return None


def _get_bytes(session, url, retries=3):
    delay = 2.0
    for attempt in range(retries):
        try:
            resp = session.get(url, timeout=TIMEOUT)
            resp.raise_for_status()
            return resp.content
        except requests.RequestException:
            if attempt == retries - 1:
                return None
            time.sleep(delay)
            delay *= 2
    return None


def _fetch_p18(qids, session):
    """qid → имя файла P18 (Commons). Возвращает (dict, failed_qids)."""
    out, failed = {}, set()
    for batch in _chunks(qids, 50):
        data = _get_json(session, WIKIDATA_API,
                         {"action": "wbgetentities", "ids": "|".join(batch),
                          "props": "claims", "claims": "P18", "format": "json"})
        if data is None:
            failed.update(batch)
            continue
        for qid, ent in data.get("entities", {}).items():
            claims = ent.get("claims", {}).get("P18", [])
            for c in claims:
                try:
                    out[qid] = c["mainsnak"]["datavalue"]["value"]
                    break
                except (KeyError, TypeError):
                    continue
        time.sleep(0.2)  # бережём API
    return out, failed


def _fetch_thumburls(filenames, session):
    """имя файла → URL миниатюры 320px. Возвращает (dict, failed_filenames)."""
    out, failed = {}, set()
    # батч 20, не 50: длинные имена файлов Commons упираются в 414 URI Too Long
    for batch in _chunks(sorted(set(filenames)), 20):
        titles = "|".join(f"File:{fn}" for fn in batch)
        data = _get_json(session, COMMONS_API,
                         {"action": "query", "prop": "imageinfo",
                          "iiprop": "url|mime", "iiurlwidth": THUMB_WIDTH,
                          "redirects": 1, "titles": titles, "format": "json"})
        if data is None:
            failed.update(batch)
            continue
        pages = data.get("query", {}).get("pages", {})
        for page in pages.values():
            title = (page.get("title") or "")[len("File:"):]
            info = (page.get("imageinfo") or [{}])[0]
            thumb = info.get("thumburl")
            if title and thumb:
                out[title] = thumb
        time.sleep(0.2)
    return out, failed


def _to_jpeg(raw):
    """bytes → jpeg bytes (пережим, если не jpeg или слишком тяжёлый)."""
    if raw[:2] == b"\xff\xd8" and len(raw) <= MAX_PHOTO_BYTES:
        return raw
    try:
        from PIL import Image
        img = Image.open(io.BytesIO(raw)).convert("RGB")
        img.thumbnail((THUMB_WIDTH, THUMB_WIDTH * 4))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=82, optimize=True)
        return buf.getvalue()
    except Exception:
        return None


def attach_photos(pois, cache_dir, photos_dir, enabled=True, only_described=True):
    """Проставляет pois[*]['photo'] = 'photos/poi-<osmId>.jpg' и пишет файлы.

    Фото включаются для POI с wikidata-id; при only_described=True — только для
    POI, у которых уже есть description (ядро карточек, контроль веса пакета).

    Возвращает stats для meta.json:
    {status, mode, withWikidata, eligible, withP18, downloaded, fromCache,
     included, bytes}.
    """
    mode = "described" if only_described else "all-wikidata"
    stats = {"status": "skipped" if not enabled else "ok", "mode": mode,
             "withWikidata": 0, "eligible": 0, "withP18": 0, "downloaded": 0,
             "fromCache": 0, "included": 0, "bytes": 0}
    for p in pois:
        p.pop("photo", None)
    if not enabled:
        return stats

    eligible = [p for p in pois if p.get("wikidata")
                and (not only_described or p.get("description"))]
    qids = sorted({p["wikidata"] for p in eligible})
    stats["withWikidata"] = len({p["wikidata"] for p in pois if p.get("wikidata")})
    stats["eligible"] = len(eligible)
    if not qids:
        return stats

    # --- 1. P18 (с кэшем метаданных) ---
    p18 = {}
    todo = []
    for qid in qids:
        hit = _cached(cache_dir, qid)
        if hit is None:
            todo.append(qid)
        else:
            stats["fromCache"] += 1
            if hit.get("image") and hit.get("thumburl"):
                p18[qid] = (hit["image"], hit["thumburl"])
    session = requests.Session()
    session.headers["User-Agent"] = USER_AGENT
    failed = set()
    if todo:
        fetched, f1 = _fetch_p18(todo, session)
        thumbs, f2 = _fetch_thumburls(list(fetched.values()), session)
        failed = f1 | {q for q, fn in fetched.items() if fn not in thumbs}
        for qid in todo:
            if qid in failed:
                continue  # не кэшируем промахи — повторный прогон дофетчит
            fn = fetched.get(qid)
            rec = {"image": fn, "thumburl": thumbs.get(fn) if fn else None}
            _store(cache_dir, qid, rec)
            if rec["thumburl"]:
                p18[qid] = (fn, rec["thumburl"])
    stats["withP18"] = len(p18)

    # --- 2. скачивание миниатюр (с кэшем байтов) ---
    img_cache = os.path.join(cache_dir, "img")
    os.makedirs(img_cache, exist_ok=True)
    if os.path.isdir(photos_dir):
        import shutil
        shutil.rmtree(photos_dir)  # не тащим фото прошлой сборки
    saved = 0
    total_bytes = 0
    wrote_dir = False
    for p in eligible:
        qid = p.get("wikidata")
        if qid not in p18:
            continue
        _, thumburl = p18[qid]
        key = hashlib.md5(thumburl.encode()).hexdigest()
        cpath = os.path.join(img_cache, f"{key}.jpg")
        if os.path.exists(cpath):
            with open(cpath, "rb") as f:
                jpeg = f.read()
        else:
            raw = _get_bytes(session, thumburl)
            jpeg = _to_jpeg(raw) if raw else None
            if jpeg is None:
                continue
            with open(cpath, "wb") as f:
                f.write(jpeg)
            stats["downloaded"] += 1
            time.sleep(0.1)  # бережём upload.wikimedia.org
        rel = f"photos/poi-{p['osm_id']}.jpg"
        if not wrote_dir:
            os.makedirs(photos_dir, exist_ok=True)
            wrote_dir = True
        with open(os.path.join(photos_dir, os.path.basename(rel)), "wb") as f:
            f.write(jpeg)
        p["photo"] = rel
        saved += 1
        total_bytes += len(jpeg)
    stats["included"] = saved
    stats["bytes"] = total_bytes
    if failed:
        stats["status"] = "partial" if saved else "unavailable"
        stats["failedIds"] = len(failed)
    return stats
