---
title: 'Генератор пакета региона (tools/region-pack)'
type: 'feature'
ticket: 3
created: '2026-10-01'
status: built
baseline_revision: 'NO_VCS'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['self-review (quick; subagents unavailable in this runtime)']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** PoC spike 1.1 (`tools/region-pack/poc/`) доказал toolchain, но собран под Монако с захардкоженными путями и константами; для эпика нужен параметризуемый генератор, который одной командой собирает `region-spb-lo-v1.zip` по формату spine (AD-6, FR-2, FR-4, FR-18) и разрешает открытые вопросы spike (плейсхолдер pmtiles URL, glyphs/sprite, правило walkability, maxzoom).

**Approach:** Вынести PoC в `tools/region-pack/` как модульный Python-пакет с CLI: скачивание/вырезка OSM (pyosmium), тайлы (planetiler, параметры maxzoom), boundaries/poi/walkability/style/meta/zip (pyosmium+shapely), выдержки Википедии по wikidata-id с честной деградацией, скрипты verify/preview. Полный прогон СПб+ЛО в этой среде заменяется прогоном на максимальном реальном участке, который тянет среда; команда полного прогона фиксируется в README и отчёте.

## Boundaries & Constraints

**Always:**
- Состав пакета строго по spine Structural Seed: `style.json`, `tiles.pmtiles`, `walkability.grid`, `boundaries.geojson`, `poi.json`, `meta.json` в `region-{id}-v{n}.zip`; meta.json содержит `formatVersion`, `tileSizeMeters`, bbox, sha256 всех файлов.
- Размер тайла сетки и maxzoom — параметры CLI (unknown тикета: финальный размер даст spike 1.11).
- `style.json` хранит плейсхолдер `pmtiles://tiles.pmtiles`; конвенция «приложение подставляет абсолютный путь при установке» задокументирована в README и meta.json (для историй 1.5).
- Стиль без текстовых слоёв → glyphs/sprite не поставляются; решение зафиксировано, ручка для будущих подписей оставлена.
- Бинарники (planetiler.jar, JRE, pmtiles.exe) и кэши источников не попадают в пакет и игнорируются VCS (`.gitignore`); пакет ≤ 50 МБ — при превышении зафиксировать риск, spine не менять.
- Все файлы — только внутри `C:\Users\Lenovo\Documents\kimi\walkapp`; `tickets.toml`, эпики 2–6, app/ не трогаем.

**Never:**
- Не менять формат spine и схему walkability.grid v0 (JSON-заголовок + row-major lsb-first битпак) — она уже потребляется планами 1.5/1.6.
- Не коммитить/не копировать в пакет bin/, data/, cache/, out/.
- Не реализовывать серверную сборку (AD-6 — только машина автора).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | `--pbf <файл> --region-id X --tile-size 100 --maxzoom 14` | `out/region-X-v1.zip` с 6 файлами, verify OK | — |
| API Википедии недоступно | сеть заблокирована | пакет собирается, `description: null`, в meta.json `wikipedia.status="unavailable"`, счётчики | warning, не падение |
| Нет pbf и скачивание невозможно | `--download` без сети | понятная ошибка с командой для ручного скачивания | exit≠0, сообщение |
| Пустой участок | pbf без парков/воды | сетка строится по дорогам, union пустых коллекций не падает | graceful None |
| Неверные параметры | tile-size ≤ 0, maxzoom вне 0–15 | отказ до запуска planetiler | argparse error |

</frozen-after-approval>

## Code Map

- `tools/region-pack/poc/build_package.py` — база генератора: Collector (pyosmium), walkability-правило, формат grid v0, style, meta, zip. Переиспользовать логику, параметризовать.
- `tools/region-pack/poc/bin/` — planetiler.jar 0.10.2, jdk-25 (Temurin portable), pmtiles.exe 1.31.2 → переносится в `tools/region-pack/bin/`.
- `_bmad-output/initiative-walkquest/epic-map-foundation/spike-1-1-region-pack-tools.md` — решение spike, риски 1–6 (разбор в Design Notes).
- `_bmad-output/initiative-walkquest/architecture-walkquest/architecture-walkquest.md` — AD-6, AD-9, Structural Seed (формат пакета), AD-8 (порог 50 МБ). Не менять.

## Tasks & Acceptance

**Execution:**
- [x] `tools/region-pack/region_pack/collect.py` -- Collector из PoC: admin-границы (kind city/region/district, AD-9), POI с wikidata-id, вода, парки + `place=square`/пешеходные площади, проходимые линии -- расширение walkability-правила (риск 3 spike).
- [x] `tools/region-pack/region_pack/walkability.py` -- построение сетки (параметр tile_size_m), бинарь grid v0, превью PNG -- из PoC + новые полигоны.
- [x] `tools/region-pack/region_pack/wiki.py` -- wikidata-id → sitelink ruwiki/enwiki (wbgetentities, батчи 50) → extract (action API), кэш на диск, таймауты, флаг деградации -- FR-18.
- [x] `tools/region-pack/region_pack/tiles.py` -- обёртка planetiler (portable JRE из bin/, `--osm-path`, `--maxzoom`, `--output`, кэш источников в `data/sources/`) -- риск 4 spike.
- [x] `tools/region-pack/region_pack/package.py` -- style.json (без текстовых слоёв, плейсхолдер pmtiles URL), meta.json (formatVersion, tileSizeMeters, checksums, wikipedia-статус, maxzoom), zip, sha256 -- риски 1–2 spike.
- [x] `tools/region-pack/build.py` -- единая команда: download (Geofabrik/BBBike по URL или локальный pbf) → extract bbox (pyosmium) → tiles → collect → wiki → walkability → package → verify -- критерий «одна команда».
- [x] `tools/region-pack/verify.py` -- roundtrip: состав zip, sha256 против meta.json, заголовок pmtiles (pmtiles.exe show), vector_layers ↔ source-layer стиля, проверка сетки, печать веса и maxzoom -- verify тикета скриптом.
- [x] `tools/region-pack/README.md` + `.gitignore` -- запуск, параметры, конвенция pmtiles URL, команда полного прогона СПб+ЛО, источники бинарников -- руч-off человеку.
- [x] Перенести bin/ из poc/, прогнать генератор на максимальном доступном реальном участке, зафиксировать вес/maxzoom -- критерии приёмки.
- [x] `tools/region-pack/test_region_pack.py` -- краевые случаи I/O-матрицы (деградация wiki, пустые коллекции, ошибки параметров, битый zip).

**Acceptance Criteria:**
- Given установленные pyosmium/shapely и bin/, when выполнена одна команда `python tools/region-pack/build.py ...`, then собирается zip со всеми 6 файлами формата spine. ✅ Монако (полный прогон одной командой) и СПб (`--region spb-city`): PASS.
- Given собранный пакет, when `verify.py`, then checksums сходятся, pmtiles валиден, слои стиля ⊂ vector_layers, в meta.json есть formatVersion/tileSizeMeters/checksums. ✅ PASS, exit 0.
- Given регион с водой и парками, when смотрим walkability.grid/preview.png, then вода и motorway/trunk/private исключены, парки и пешеходные зоны включены. ✅ СПб: waterExcluded=1452, превью подтверждает (Нева/залив пустые, Елагин/Сосновка/Таврический в сетке).
- Given недоступный API Википедии, when сборка, then пакет собран, meta.json фиксирует деградацию, описания null. ✅ Первый прогон СПб (сбой сети) собрался со status="unavailable"; unit-тест PASS.

## Implementation Notes

- Реализация прямая (субагенты в этом рантайме недоступны). Маршрут full сохранён: объём ~700 строк нового кода, 11 файлов.
- Бинарники и кэш planetiler перенесены `poc/bin` → `tools/region-pack/bin/`, `poc/data/sources` → `tools/region-pack/data/sources` (в poc оставлен MOVED.txt). Архивы-источники (jre.zip, pmtiles.zip, tilemaker-windows.zip) лежат там же, под .gitignore.
- Реальные прогоны: (1) Монако — полная одна команда, 57s, 0.4 МБ, PASS; (2) СПб, пресет `spb-city` (BBBike 54 МБ, bbox из заголовка pbf [30.042, 59.796, 30.63, 60.071]): tiles planetiler ~66s (архив 27 МБ, z0–14), collect 162s (admin=77, poi=58580, water=1625, walkpolys=1540, walklines=295375), wiki 512/538 id с описаниями (status ok после дофетча; первая попытка показала честную деградацию partial/unavailable при сетевых сбоях), сетка 100696 тайлов / 59101 проходимый (58.7%). Пакет `region-spb-city-v1.zip` = **28,9 МБ**, maxzoom 14 — в пороге AD-8.
- Полный прогон СПб+ЛО (`--region spb-lo`, дамп СЗФО Geofabrik 654 МБ) в этой среде не тянется: один лишь pyosmium-проход по 654 МБ оценочно ~30 мин при лимите команды 300 с, фоновые процессы запрещены. Команда для машины автора зафиксирована в README. Экстраполяция веса: 28,9 МБ за центр СПб → СПб+ЛО ориентировочно 40–70 МБ; при превышении 50 МБ ручка `--maxzoom 13` — зафиксировано как риск для spike 1.11/истории 1.12, spine не меняется.
- Сюрприз: planetiler при cwd=ROOT и относительном --osm-path создавал stray-дерево каталогов; исправлено абсолютным путём pbf в build.py (проверено повторным прогоном).
- collect кэшируется в `out/<id>/collect.pkl` (pyosmium-проход — самая долгая Python-стадия); wiki-кэш — `cache/wiki/*.json`, промахи сети не кэшируются (повторный прогон дофетчивает).
- verify.py читает пакет как потребитель (zip напрямую), проверки [1]–[5] с exit-кодом; встроен в build.py как финальная стадия.

## Plan Change Log

## Review Triage Log

Quick self-review (subagents unavailable; линзы запущены не были — зафиксировано). Проверены: план ↔ diff-манифест ↔ код ↔ прогоны.
- build.py: относительный путь pbf при cwd=ROOT плодил stray-каталоги — **high (подтверждено прогоном)** → исправлено `os.path.abspath`, повторный прогон PASS, stray удалён. Маршрут: patch (исправлено в ходе реализации до ревью).
- wiki.py: первая версия роняла всю выборку при одном сбойном батче и кэшировала промахи — **medium (подтверждено прогоном: status unavailable при живом API)** → ретраи с backoff, per-batch отказ, промахи не кэшируются; повторный прогон: status ok, 512/538. Маршрут: patch (исправлено до ревью).
- walkability.py preview: `unary_union` теоретически может вернуть GeometryCollection без `.exterior` — **low**, на реальных данных (Монако, СПб) не воспроизводится; фикс добавляет ветки. Отклонено как low по правилу редкой встречаемости.
- Тесты I/O-матрицы: 13 проверок PASS (`test_region_pack.py`), включая деградацию wiki и отказ по параметрам.
- Критерии тикета verify: одна команда ✅, состав пакета ✅, вода исключена/парки включены (скрипт + визуально) ✅, meta.json с formatVersion/размером тайла/checksums ✅.

## Design Notes

Риски spike 1.1 → решения этой истории:
1. **pmtiles URL**: плейсхолдер `pmtiles://tiles.pmtiles` в style.json; приложение при установке подставляет абсолютный `pmtiles://file://...` (MapLibre Native требует абсолютный). Конвенция в README + поле `pmtilesUrlPlaceholder` в meta.json.
2. **glyphs/sprite**: v1-стиль без symbol-слоёв → шрифты/спрайты не нужны и не поставляются; при добавлении подписей пакет дополнится glyphs (formatVersion поднимется).
3. **walkability**: правило PoC + `leisure=garden/nature_reserve`, `place=square`, пешеходные площади (`highway=pedestrian`+`area=yes` как полигоны); дворы/частные access — осознанно вне, уточняет spike 1.11.
4. **maxzoom**: параметр, дефолт 14 (PoC); вес/maxzoom фактического прогона — в отчёт истории; финал решает spike 1.11.
5. Бинарники в `tools/region-pack/bin/`, источники и версии — в README, не коммитим.

## Verification

**Commands:**
- `python tools/region-pack/build.py --pbf <участок>.osm.pbf --region-id <id> --version 1 --tile-size 100 --maxzoom 14` -- zip собран одной командой, вес напечатан.
- `python tools/region-pack/verify.py out/region-<id>-v1.zip` -- все проверки PASS, exit 0.

**Manual checks:**
- `out/walkability-preview.png`: проходимые тайлы по дорогам/паркам, вода исключена (визуально, как в PoC).
