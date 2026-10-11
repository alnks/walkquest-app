"""Выдержки Википедии для POI по собранным wikidata-id (FR-18).

Цепочка: wikidata-id → sitelink ruwiki (fallback enwiki) через wbgetentities (батчи 50)
→ вводный extract через action API Википедии (батчи 20, exintro, до 500 символов).

Пакет v5: вместе с description проставляется опциональное поле url — ссылка на
статью Википедии (ru, если выдержка из ruwiki; иначе en — тот же sitelink, что
использовался для description). url только у POI с описанием (карточка POI в
приложении: усечённое описание + «Подробнее»); схема аддитивная, обратная
совместимость сохранена. Для старых записей кэша (только description) добывается
только sitelink (батчи 50), extract заново не запрашивается.

Честная деградация: любая сетевая ошибка не роняет сборку — часть описаний может
отсутствовать; статус возвращается для meta.json:
  "ok"          — все запрошенные id обработаны;
  "partial"     — часть батчей упала, есть хотя бы одно описание;
  "unavailable" — API недоступен, описаний нет;
  "skipped"     --no-wiki.
Кэш: cache/wiki/{qid}.json — повторные сборки не ходят в сеть.
"""
import json
import os
import time
from urllib.parse import quote

import requests

USER_AGENT = "WalkQuestRegionPack/1.3 (region-pack generator; contact: author-local)"
TIMEOUT = 20
EXTRACT_CHARS = 500


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


def _wiki_url(lang, title):
    """https://{lang}.wikipedia.org/wiki/<Title> — пробелы → подчёркивания,
    остальное percent-encoding (каноническая форма ссылки на статью)."""
    return (f"https://{lang}.wikipedia.org/wiki/"
            f"{quote(title.replace(' ', '_'), safe='/')}")


def _fetch_sitelinks(qids, session):
    """qid → (lang, title) для ruwiki/enwiki. Возвращает (dict, failed_qids)."""
    out, failed = {}, set()
    for batch in _chunks(qids, 50):
        data = _get_json(session, "https://www.wikidata.org/w/api.php",
                         {"action": "wbgetentities", "ids": "|".join(batch),
                          "props": "sitelinks", "sitefilter": "ruwiki|enwiki",
                          "format": "json"})
        if data is None:
            failed.update(batch)
            continue
        for qid, ent in data.get("entities", {}).items():
            sl = ent.get("sitelinks", {})
            if "ruwiki" in sl:
                out[qid] = ("ru", sl["ruwiki"]["title"])
            elif "enwiki" in sl:
                out[qid] = ("en", sl["enwiki"]["title"])
        time.sleep(0.2)  # бережём API
    return out, failed


def _fetch_extracts(sitelinks, session):
    """(qid → (lang,title)) → (qid → текст выдержки, failed_qids)."""
    out, failed = {}, set()
    by_lang = {}
    for qid, (lang, title) in sitelinks.items():
        by_lang.setdefault(lang, []).append((qid, title))
    for lang, items in by_lang.items():
        for batch in _chunks(items, 20):
            titles = [t for _, t in batch]
            data = _get_json(session,
                             f"https://{lang}.wikipedia.org/w/api.php",
                             {"action": "query", "prop": "extracts", "exintro": 1,
                              "explaintext": 1, "exchars": EXTRACT_CHARS,
                              "redirects": 1, "titles": "|".join(titles),
                              "format": "json"})
            if data is None:
                failed.update(qid for qid, _ in batch)
                continue
            pages = data.get("query", {}).get("pages", {})
            by_title = {p.get("title"): p.get("extract") for p in pages.values()}
            for qid, title in batch:
                text = (by_title.get(title) or "").strip()
                if text:
                    out[qid] = text
            time.sleep(0.2)
    return out, failed


def enrich_pois(pois, cache_dir, enabled=True):
    """Проставляет pois[*]['description'] и pois[*]['url'] по wikidata-id.

    url — только у POI с description: тот же sitelink, что дал выдержку
    (пакет v5, аддитивно; у POI без описания поля url нет).
    Возвращает stats для meta.json:
    {status, withWikidata, fetched, fromCache, withDescription, withUrl}.
    """
    qids = sorted({p["wikidata"] for p in pois if p.get("wikidata")})
    stats = {"status": "skipped" if not enabled else "ok",
             "withWikidata": len(qids), "fetched": 0, "fromCache": 0,
             "withDescription": 0, "withUrl": 0}
    for p in pois:
        p.setdefault("description", None)
        p.pop("url", None)
    if not enabled or not qids:
        return stats

    desc, urls = {}, {}
    todo, need_url = [], []
    for qid in qids:
        hit = _cached(cache_dir, qid)
        if hit is None:
            todo.append(qid)
        else:
            stats["fromCache"] += 1
            if hit.get("description"):
                desc[qid] = hit["description"]
                if hit.get("url"):
                    urls[qid] = hit["url"]
                else:
                    need_url.append(qid)  # кэш до v5: добираем только sitelink
    session = requests.Session()
    session.headers["User-Agent"] = USER_AGENT
    failed_qids = set()
    if todo:
        sitelinks, f1 = _fetch_sitelinks(todo, session)
        extracts, f2 = _fetch_extracts(sitelinks, session)
        failed_qids = f1 | f2
        for qid in todo:
            if qid in failed_qids:
                continue  # не кэшируем промахи — повторный прогон дофетчит
            text = extracts.get(qid)
            url = _wiki_url(*sitelinks[qid]) if text and qid in sitelinks else None
            _store(cache_dir, qid, {"description": text, "url": url})
            if text:
                desc[qid] = text
                if url:
                    urls[qid] = url
        stats["fetched"] = len(todo) - len(failed_qids)
        stats["failedIds"] = len(failed_qids)
    if need_url:
        # старые записи кэша (только description): добываем sitelink батчами,
        # extract заново не запрашиваем
        sitelinks, fu = _fetch_sitelinks(need_url, session)
        for qid, (lang, title) in sitelinks.items():
            url = _wiki_url(lang, title)
            urls[qid] = url
            hit = _cached(cache_dir, qid) or {}
            hit["url"] = url
            _store(cache_dir, qid, hit)
        if fu:
            stats["urlFailedIds"] = len(fu)  # url best-effort, описания на месте
    applied = applied_url = 0
    for p in pois:
        qid = p.get("wikidata")
        if qid and qid in desc:
            p["description"] = desc[qid]
            applied += 1
            if qid in urls:
                p["url"] = urls[qid]
                applied_url += 1
    stats["withDescription"] = applied
    stats["withUrl"] = applied_url
    if failed_qids:
        stats["status"] = "partial" if applied else "unavailable"
    return stats
