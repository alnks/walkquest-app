# Spike 1.1 — инструменты генератора region-pack

**Дата:** 2026-10-01 · **Статус:** решение принято, PoC собран · **Тикет:** `tickets.toml`, запись 1 (spike)

## Задача

Выбрать состав инструментов для `tools/region-pack` (spine, Stack и Deferred «Состав инструментов region-pack»; AD-6: офлайн-генерация на машине автора) и доказать выбор PoC-сборкой пакета малого участка со всеми файлами формата spine:

```text
region-{id}-v{n}.zip
  style.json + tiles.pmtiles   # векторная карта для MapLibre
  walkability.grid             # проходимые тайлы (знаменатель процента)
  boundaries.geojson           # границы города/региона/районов (AD-9)
  poi.json                     # POI + описания (OSM + Википедия)
  meta.json                    # formatVersion, размер тайла, контрольные суммы
```

## Критерии (из тикета и spine)

1. Покрывает весь формат пакета, в первую очередь `tiles.pmtiles` (AD-6, spine Structural Seed).
2. Работает на Windows-машине автора **без Docker и WSL** (оба не установлены), без GIS-опыта у автора — «проторённая дорога» (constraint из `.memlog.md`).
3. Зрелость и живость проекта, понятный CLI/документация.
4. Размер артефактов и время сборки — измерить на PoC (порог пакета ≤ 50 МБ, AD-8).

## Кандидаты и сравнение

| Инструмент | Роль | Windows без Docker | Выходной формат | Вердикт |
| --- | --- | --- | --- | --- |
| **planetiler 0.10.2** (Java, OpenMapTiles встроена) | OSM pbf → векторные тайлы | Да (portable JRE, один jar) | **pmtiles напрямую** | **Выбран** |
| tilemaker 3.0.0 | OSM pbf → тайлы | Бинарь есть, но **падает** (см. ниже); релизы 3.1/3.2 без Windows-сборок | pmtiles/mbtiles | Отклонён |
| osmium-tool 1.19 | фильтрация/вырезки OSM | Официальных Windows-бинарников нет | pbf | Отклонён, заменён pyosmium |
| **pyosmium + shapely** (pip) | границы, POI, walkability | Да (`pip install osmium shapely`) | geojson/json/бинарь | **Выбран** |
| **go-pmtiles CLI 1.31.2** | верификация/конвертация pmtiles | Да (официальный Windows-бинарь) | — | **Выбран (служебный)** |
| openmaptiles (docker-compose стек) | тайлы | Нет (нужен Docker) | mbtiles | Отклонён |
| tippecanoe + pmtiles convert | тайлы | Нет нативного Windows; нужен WSL | mbtiles | Отклонён |

### Почему не tilemaker (проверено практически)

Официальная Windows-сборка `tilemaker-windows.zip` (v3.0.0, единственная — в 3.1/3.2 бинарники не публикуются) на машине автора **молча падает с кодом 127** сразу после печати bounding box, до чтения данных; `--verbose` ничего не добавляет, падение одинаково на openmaptiles- и debug-конфигах, с относительными и абсолютными путями, существующим и несуществующим входным файлом (для несуществующего хотя бы печатает ошибку). DLL-зависимости (MSVCP140/VCRUNTIME140/ucrtbase) разрешаются — дело не в окружении. Известные проблемы Windows-сборки подтверждаются трекером (crash на Windows, #776; «Does someone have compiled tilemaker under Windows?», #857). Альтернатива — сборка из исходников MSVC/vcpkg — непроторённая дорога при нулевом GIS/C++-опыте. Логи: `tools/region-pack/poc/out/tm4–tm7.log`.

### Почему planetiler

- Единственный из зрелых генераторов, работающий на голой Windows: один `planetiler.jar` (88 МБ) + portable Temurin JRE (55 МБ zip, распаковывается в `tools/`, ничего не ставится в систему).
- Схема OpenMapTiles 3.16 из коробки — слои `water/park/transportation/building/poi` и т.д. совместимы с типовыми стилями MapLibre; для WalkQuest своего стиля это самый документированный путь.
- Пишет `.pmtiles` напрямую (без промежуточного mbtiles и конвертации).
- Активно развивается (релиз 0.10.2 — март 2026), отличная документация и CLI.

### Совместимость с приложением (проверено по документации MapLibre)

MapLibre Native Android поддерживает PMTiles-источники нативно с версии 11.7.0 (`pmtiles://file://...` для локальных файлов); spine пинит линию 13.5.x — поддержка есть. Нюанс: в отличие от MapLibre GL JS, URL внутри `pmtiles://` должен быть **абсолютным** — `style.json` в пакете хранит плейсхолдер (`pmtiles://tiles.pmtiles` в PoC), приложение при установке пакета подставляет реальный путь (или добавляет источник программно). Это решение для историй 1.3/1.5, на выбор инструментов не влияет. [MapLibre Android Examples — PMTiles](https://www.maplibre.org/maplibre-native/android/examples/data/PMTiles/)

## Выбранный toolchain (итоговый состав region-pack)

```text
tools/region-pack/
  bin/  planetiler.jar 0.10.2 + jdk-25 (Temurin JRE, portable) + pmtiles.exe (go-pmtiles 1.31.2)
  Python 3.12 + osmium (pyosmium) + shapely   # boundaries, poi, walkability, meta, zip
```

Пайплайн (PoC, Монако):

```bash
# 0. один раз: скачать OSM-вырез региона
curl -L -o data/monaco.osm.pbf https://download.geofabrik.de/europe/monaco-latest.osm.pbf

# 1. векторные тайлы (загрузка источников Natural Earth/water polygons — один раз, ~1.4 ГБ)
jdk/bin/java -Xmx2g -jar bin/planetiler.jar \
  --osm-path=data/monaco.osm.pbf --output=out/tiles.pmtiles --download

# 2. границы, POI, walkability.grid, style.json, meta.json, zip
python build_package.py            # pyosmium + shapely, см. tools/region-pack/poc/build_package.py

# 3. верификация
bin/pmtiles.exe show out/package/tiles.pmtiles          # заголовок
bin/pmtiles.exe show --metadata out/package/tiles.pmtiles  # vector_layers ↔ style.json
```

## PoC: что собрано и проверено практически

Участок: Монако (`monaco-latest.osm.pbf`, 0,7 МБ, Geofabrik). Артефакты: `tools/region-pack/poc/out/`.

| Проверка | Результат |
| --- | --- |
| planetiler полный прогон на Windows | **Да** — 98 сек с холодного старта, включая скачивание 1,4 ГБ источников; сама генерация тайлов ~10 сек |
| `tiles.pmtiles` валиден | **Да** — `pmtiles show`: spec v3, MVT, z0–14, 108 тайлов, схема OpenMapTiles 3.16.0, gzip |
| `boundaries.geojson` | **Да** — 11 адм. границ (admin_level 2–10) из pbf через pyosmium, с kind city/region/district (AD-9) |
| `poi.json` | **Да** — 707 POI из OSM с категорией и `wikidata`-id; описания из Википедии **не реализованы** (поле `description: null`, история 1.3) |
| `walkability.grid` | **Да** — сетка 100 м (параметр), 151×300 тайлов; бинарь: JSON-заголовок + row-major битпак, 5,8 КБ; правило PoC: проходим, если через тайл проходит highway (без motorway/trunk/private) или он в парке, и центр не в воде |
| Вода/парки в сетке | **Да, визуально** — превью `out/walkability-preview.png`: проходимые тайлы только на суше и по дорогам, море исключено, парки включены (354 проходимых = 0,8 % bbox, Монако — в основном вода) |
| `meta.json` | **Да** — formatVersion=1, tileSizeMeters, bbox, sha256 всех файлов; контрольные суммы перепроверены скриптом — OK |
| `style.json` | **Да, структурно** — все 4 использованных source-layer есть в vector_layers pmtiles; **рендер на устройстве не проверялся** (приложения нет до истории 1.2/1.6) |
| Пакет целиком | **Да** — `region-monaco-poc-v1.zip` (421 КБ) содержит все 6 файлов формата spine, `testzip` OK |

Размеры: pmtiles 410 КБ, poi.json 122 КБ, boundaries.geojson 58 КБ, walkability.grid 5,8 КБ, style.json 1 КБ, meta.json 0,9 КБ.

## Что НЕ проверено (по документации / отложено)

- Рендер пакета в MapLibre на Android-устройстве — проверяется историями 1.2/1.6 (структурная валидность подтверждена здесь).
- Выдержки Википедии для POI (FR-18) — источник выбран (API Википедии по собранным `wikidata`-id), реализация в 1.3.
- Размер пакета СПб+Ленобласти — экстраполяция: pbf региона ~0,3–0,4 ГБ, pmtiles z0–14 оценочно 30–60 МБ; **риск порога ≤ 50 МБ (AD-8)** закрывается в 1.3 (maxzoom/детализация — ручки planetiler) и spike 1.11.
- Скорость генерации СПб+Ленобласти: planetiler для планеты — часы; для региона такого размера по документации и бенчмаркам — порядка 10–30 мин, приемлемо для ручной сборки (AD-6).

## Риски и открытые вопросы для истории 1.3 (генератор пакета)

1. **Плейсхолдер URL в style.json**: MapLibre Native требует абсолютный `pmtiles://file://...`; приложение подставляет путь при установке пакета. Зафиксировать конвенцию в 1.3, использовать в 1.5.
2. **Glyphs/sprite для подписей**: PoC-стиль без текстовых слоёв; с подписями MapLibre требует glyphs — решить в 1.3 (поставлять шрифты в пакете или карта без подписей v1).
3. **Правило walkability — минимальное**: не учтены дворы/проходы без highway (площади, пешеходные зоны highway=pedestrian учтены), piers, access-частные случаи; уточняется в 1.3 и полевым spike 1.11.
4. **maxzoom**: PoC на z14 (дефолт OMT); для читаемости дворов может понадобиться z15 — параметр, влияет на размер пакета (связка со spike 1.11).
5. **Окружение автора**: portable JRE и бинарники лежат в `tools/region-pack/poc/bin/`; при переносе в `tools/region-pack` зафиксировать версии и источники (~200 МБ бинарников + ~1,4 ГБ кэша источников planetiler — не коммитить).
6. **tilemaker остаётся запасным** только через WSL/Docker, если planetiler упрётся в размер пакета СПб+ЛО — возврат маловероятен.

## Вывод

Формат пакета из spine **реализуем** выбранным toolchain без изменений spine. Все шесть файлов формата собраны и верифицированы на реальном OSM-участке. Spike закрывает Deferred «Состав инструментов region-pack».
