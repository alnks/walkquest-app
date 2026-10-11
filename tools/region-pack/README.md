# tools/region-pack — генератор пакета региона WalkQuest

История 1.3 (epic-map-foundation), по решению spike 1.1. Собирает **одной командой**
пакет `region-{id}-v{n}.zip` формата spine (Structural Seed, AD-6):

```text
region-{id}-v{n}.zip
  style.json + tiles.pmtiles   # векторная карта для MapLibre
  walkability.grid             # проходимые тайлы (знаменатель процента, FR-2)
  water.grid                   # маска воды: реки/залив сквозь туман (пакет v3+, опционально)
  boundaries.geojson           # границы города/региона/районов (AD-9)
  poi.json                     # POI + выдержки Википедии (FR-18) + ссылки на статьи (v5+) и фото
  photos/poi-<osmId>.jpg       # фото POI (Wikidata P18, ~320px; пакет v2+, опционально)
  meta.json                    # formatVersion, размер тайла, контрольные суммы
```

## Требования

- Python 3.12+ с `osmium`, `shapely`, `numpy`, `requests`, `matplotlib`.
- Бинарники в `bin/` (portable, ничего не ставится в систему; **не коммитим**):
  - `planetiler.jar` 0.10.2 — <https://github.com/onthegomap/planetiler/releases>
  - `jdk-25.0.4.1+1-jre/` — Temurin JRE portable, <https://adoptium.net>
  - `pmtiles.exe` — go-pmtiles CLI 1.31.2, <https://github.com/protomaps/go-pmtiles/releases>
- При первом прогоне planetiler скачивает источники (Natural Earth, water polygons,
  ~1.4 ГБ) в `data/sources/` — один раз, дальше из кэша.

## Запуск

```bash
# Полный прогон СПб + Ленобласть (машина автора): дамп СЗФО Geofabrik (~650 МБ),
# клип по bbox [27.5, 58.6, 36.0, 61.9]. Ожидаемое время: скачивание + 10–40 мин.
python tools/region-pack/build.py --region spb-lo

# Пресет spb-city (пакет v4): bbox зафиксирован в пресете —
# [29.55, 59.796, 30.63, 60.071] (с v4 западная граница 29.55 вместо 30.042:
# Невская губа и восток Финского залива до Лисьего Носа целиком в кадре).
# Источник с v4 — дамп СЗФО Geofabrik (~650 МБ) с клипом по bbox: экстракт
# BBBike оканчивается на 30.042 и западнее данных не содержит.
python tools/region-pack/build.py --region spb-city --version 4

# То же, но из уже скачанного дампа и с повторным использованием тайлов:
python tools/region-pack/build.py --region spb-lo \
    --pbf tools/region-pack/data/northwestern-fed-district-latest.osm.pbf \
    --skip-tiles

# Произвольный участок:
python tools/region-pack/build.py --pbf data/SanktPetersburg.osm.pbf \
    --region-id spb-city --bbox 29.4,59.6,31.1,60.4 --tile-size 100 --maxzoom 14
```

Результат: `tools/region-pack/out/region-{id}-v{n}.zip` + превью сеток
`out/{id}/walkability-preview.png` (визуальная проверка: вода синим, парки
зелёным, проходимые тайлы точками) и `out/{id}/water-preview.png`
(маска воды: водные тайлы синим поверх полигонов-источников).

### Параметры

| Флаг | Дефолт | Назначение |
| --- | --- | --- |
| `--region` / `--pbf` / `--url` | — | источник OSM: пресет (`spb-lo`, `spb-city`), локальный файл или URL |
| `--bbox` | из пресета или заголовка pbf | клип и границы сетки: `minlon,minlat,maxlon,maxlat` |
| `--tile-size` | 100 (м) | размер тайла сетки тумана; финал даёт spike 1.11, пакет перегенерируется |
| `--maxzoom` | 14 | maxzoom векторных тайлов (ручка веса пакета, связка со spike 1.11) |
| `--version` | 1 | `n` в `region-{id}-v{n}.zip` |
| `--no-wiki` | выкл. | пропустить выдержки Википедии |
| `--no-photos` | выкл. | пропустить фото POI (Wikidata P18) |
| `--photos-all` | выкл. | фото для всех POI с wikidata-id; дефолт — только для POI с описанием (контроль веса пакета, AD-8) |
| `--skip-tiles` | выкл. | переиспользовать `out/<id>/tiles.pmtiles` |
| `--heap` | 4g | heap JVM для planetiler |

## Конвенции формата (для историй 1.5/1.6)

- **pmtiles URL**: в `style.json` плейсхолдер `pmtiles://tiles.pmtiles`
  (`meta.json → pmtilesUrlPlaceholder`). MapLibre Native требует абсолютный URL —
  приложение при установке пакета подставляет `pmtiles://file://<локальный путь>`
  или добавляет источник программно.
- **glyphs/sprite**: стиль v1 без symbol-слоёв (карта без подписей) — шрифты и спрайты
  не поставляются. Добавление подписей = новый `formatVersion` и поставка glyphs.
- **walkability.grid v0**: строка JSON-заголовка `{cols, rows, tileSizeMeters, origin,
  order, walkableCount}` + row-major битпак (lsb-first, 1 бит = тайл). Правило:
  тайл проходим, если через него проходит проходимая линия (`highway`, кроме
  motorway/trunk/construction/proposed и access=private/no) или центр тайла в
  walkable-полигоне (парк/сквер/заказник/`place=square`/пешеходная площадь),
  и центр тайла не в воде. Дворы без highway осознанно не покрыты — spike 1.11.
- **water.grid v0 (пакет v3)**: ровно тот же бинарный формат, что walkability.grid;
  поле счётчика заголовка — `waterCount` (аналог walkableCount). Правило: тайл =
  вода, если его центр в водном полигоне — те же источники, что исключают воду
  из walkability (`natural=water/bay`, `waterway=riverbank`,
  `landuse=reservoir/harbour/basin`). Назначение: вода (Нева, Невская губа,
  каналы) видна сквозь туман как ориентир. Приложение читает файл опционально —
  пакеты v1/v2 без water.grid остаются валидными. Геометрия сетки (cols, rows,
  origin, tileSizeMeters) совпадает с walkability.grid; sha256 файла — в
  `meta.json → checksums`, заголовок дублируется в `meta.json → waterGrid`.
- **Википедия**: `poi.json → description` заполняется по `wikidata`-id
  (Wikidata sitelinks → intro-extract ruwiki/enwiki, ≤ 500 символов). При недоступности
  API сборка не падает: `description: null`, статус в `meta.json → wikipedia.status`
  (`ok` / `partial` / `unavailable` / `skipped`). Кэш — `cache/wiki/`.
- **Ссылки на статьи (пакет v5)**: `poi.json → url` — опциональное поле
  (схема аддитивная, обратная совместимость): ссылка на статью Википедии
  `https://<lang>.wikipedia.org/wiki/<Title>` (ru, если выдержка из ruwiki;
  иначе en — тот же sitelink, что дал description). Поле только у POI с
  описанием — для ссылки «Подробнее» в карточке POI. Запись кэша wiki —
  `{"description", "url"}`; для старых записей добирается только sitelink
  (батчи 50), extract заново не запрашивается. Счётчик —
  `meta.json → wikipedia.withUrl`.
- **Фото POI (пакет v2)**: `poi.json → photo` — опциональное поле (схема аддитивная,
  обратная совместимость: у POI без фото поля нет), относительный путь от корня
  пакета `photos/poi-<osmId>.jpg` (jpeg, миниатюра ~320px). Источник — Wikidata P18
  → миниатюра Wikimedia Commons (`region_pack/photos.py`, батчи, ретраи, кэш
  `cache/photos/`). Дефолтный режим `described`: фото только у POI с описанием —
  полный набор раздувает пакет (AD-8). При недоступности API сборка не падает:
  фото отсутствуют, статус в `meta.json → photos.status` (те же значения, что
  у `wikipedia.status`).

## Проверка

```bash
python tools/region-pack/verify.py tools/region-pack/out/region-spb-lo-v1.zip
```

Проверяет состав zip (7 файлов формата, пакет v3), sha256 против meta.json,
валидность pmtiles и соответствие слоёв стиля vector_layers, roundtrip
walkability.grid и water.grid; печатает вес и maxzoom.
Порог веса пакета — ≤ 50 МБ (AD-8); превышение — риск, не блокер генератора.

## Отбор POI (whitelist — ядро достопримечательностей, пакет v2)

POI-маяки (FR-18) — ядро «классических достопримечательностей» по уточнению
владельца (2026-10-05): музеи, театры, памятники/мемориалы, дворцы, усадьбы,
доходные дома/heritage-здания + парки/сады/заказники (завязаны на ачивки
A15/A16 контент-каталога). Места для спальных районов владелец добавляет
вручную поверх этого ядра. Отбор — **whitelist** в
`region_pack/collect.py → POI_TAGS`; всё вне списка в пакет не попадает:

- **tourism**: attraction, museum, gallery, artwork, viewpoint (размещение
  hotel/hostel/apartment и развлекательное zoo/aquarium/theme_park — исключены);
- **historic**: memorial, monument, tomb, castle, palace, ruins, battlefield,
  archaeological_site, church, wayside_cross/shrine, manor, gate, milestone,
  boundary_stone, heritage(_building), building, house, монументальная техника
  (locomotive, railway_car, cannon, anchor, aircraft, ship, vehicle, exhibit,
  shield), bridge;
- **leisure**: park, garden, nature_reserve (ачивки A15/A16; спорт, досуг и
  bandstand/common — исключены);
- **amenity**: place_of_worship, theatre, arts_centre (библиотеки, планетарии,
  фонтаны, монастыри как amenity — исключены из ядра; еда/аптеки/банки/салоны/
  школы — исключены);
- **man_made**: lighthouse, bridge, tower, obelisk (знаковые; water_tower,
  cross, инфраструктура — исключены);
- **natural=\*** — исключён целиком (beach/spring/cliff — не «классические
  достопримечательности»);
- **shop=\*** — исключён целиком.

Поле `category` по-прежнему `key=value` — контракт poi.json и парсер
приложения (история 1.9) не менялись; ключи whitelist входят в тиры
PoiFilter (tourism/historic/leisure/natural → тир 2, amenity/man_made →
тир 3, маяки с wikidata/описанием → тир 1). Числа (spb-city, пакет v2,
2026-10): 3 883 POI (v1 после первого фильтра: 4 131; до фильтра: 58 580),
с описаниями 407, с фото 376. Расширение списка — только через обновление
контент-каталога.

## Что не коммитим

`bin/` (бинарники ~230 МБ), `data/` (pbf + кэш источников ~1.4+ ГБ), `cache/wiki/`,
`out/` — всё в `.gitignore`. Исходный PoC и его артефакты — в `poc/` (историческая
справка, spike 1.1).
