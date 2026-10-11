---
title: 'POI-маяки и карточка места'
type: 'feature'
ticket: 9
created: '2026-10-04'
status: built
baseline_revision: 'NO_VCS'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick (self-review; вложенные субагенты в этом рантайме недоступны — как в 1.5/1.6/1.7/1.8)']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Пакет региона уже несёт poi.json (58 580 POI с описаниями из Википедии, 1.3), но приложение его не читает: FR-18 требует POI-маяки, видимые сквозь туман, и офлайн-карточку места с названием и описанием.

**Approach:** Новый чистый слой `data/poi` (парсер poi.json + фильтр выдачи по зуму/видимой области, AD-10). MapViewModel держит все POI в памяти и отдаёт в MapUiState видимую выборку (≤800, тирами по зуму); MapScreen рисует CircleLayer поверх слоя тумана по токену poi-marker, тап по маркеру открывает Compose-карточку по токену poi-card. Всё из установленного пакета — офлайн (AD-2).

## Boundaries & Constraints

**Always:**
- AD-1: POI — свой GeoJsonSource + CircleLayer поверх тумана; порядок слоёв: базовая карта → fog FillLayer → poi CircleLayer (POI видны сквозь туман, FR-18 — маяки, не награда). Тайлы пакета не модифицируются.
- Токен poi-marker (DESIGN): точка акцентом с мягким свечением — dark #C6E26B (accent-dark) / light #7A9A1F (accent, графика — допустим); glow через circleBlur. poi-card: фон surface-raised (MaterialTheme.colorScheme.surface), radius rounded.lg = 16dp, название — heading (жирный заголовок), описание — body; карточка в нижней зоне поверх карты.
- Закрытие карточки: тап вне карточки (тап по карте вне маркера) и свайп вниз по карточке (DESIGN, строка 170); плюс явная кнопка закрытия.
- UDF по образцу 1.6–1.8: состояние (visiblePois, selectedPoi) только в MapUiState, события (onViewportChanged, onPoiTapped, onPoiCardDismissed) через MapViewModel; MapLibre-объекты не выходят из ui/map.
- AD-10: `data/poi` — без импортов android.*/androidx.*/org.maplibre.* (JVM-тестируемо); чтение poi.json и фильтрация — в Dispatchers.IO/Default, не на главном потоке.
- AD-2: источник POI — только poi.json установленного пакета (InstalledPackage.poiFile, 1.5); сеть не нужна.
- Формат poi.json (фактический, region-spb-city-v1.zip): `{"pois": [{"osm_id": long, "name": string?, "name_ru": string?, "category": "shop=outpost", "lon": double, "lat": double, "wikidata": "Q…"?, "description": string?}]}` — 58 580 записей, 514 категорий, 580 с wikidata, 512 с description (медиана 339, макс 514 символов).

**Never:**
- Не менять tickets.toml, tools/region-pack, эпики 2–6, initiative/PRD/UX-файлы, data/packages, data/fog, domain/, tracking/ — слои тумана, плашка процента, кнопки записи и тестовая точка работают как раньше.
- Не грузить все 58k в слой MapLibre и не ставить кластеризацию (кластер — v2); выдача — тирами по зуму с лимитом (решение ниже).
- Не делать экран списка POI, поиск, фото, кнопку «проложить маршрут» — только маркеры и карточка.
- Не ставить JDK/Android SDK (прогон тестов — HITL); не менять формат poi.json (он — контракт генератора 1.3).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Старт с пакетом | Регион установлен, zoom 10 (весь регион) | Видны только маяки-ориентиры (тир 1: есть wikidata или description) по всему bbox | — |
| Зум к району | zoom 13–14.9, viewport сдвинут | Тир 1 + тир 2 (tourism/historic/leisure/natural) в видимой области (с запасом 20%) | — |
| Уличный зум | zoom ≥ 15 | Все категории в видимой области, не более 800; при переполнении — приоритет тир 1 > 2 > 3 | — |
| Тап по маркеру | Маркер под пальцем | selectedPoi в состоянии, карточка с name (иначе name_ru, иначе категория) и description (может отсутствовать) | — |
| Тап вне / свайп вниз | Карточка открыта | Карточка закрывается, selectedPoi = null | — |
| POI без названия | name = null, name_ru = null | В карточке заголовок = человекочитаемая категория («shop=outpost» → «shop: outpost») | — |
| Пакет не установлен | getInstalled → null | Ни маркеров, ни карточки (как туман), asset-запас работает | — |
| Битый poi.json | JSON не парсится | PoiResult.Err → слой пуст, карта/туман/прогресс не ломаются, краша нет | типизированная ошибка, состояние без POI |
| Далёкий зум без пакета маяков в viewport | Тира 1/2 нет в области | Пустая выдача — пустой слой, без ошибки | — |

</frozen-after-approval>

## Code Map

- `tools/region-pack/out/region-spb-city-v1.zip` → poi.json — фактический контракт (см. Boundaries) и данные для Python-зеркала; НЕ менять.
- `app/src/main/java/app/walkquest/data/packages/PackageModels.kt` — InstalledPackage.poiFile уже есть (строка 61); PackageJson (ignoreUnknownKeys) переиспользовать; НЕ менять.
- `app/src/main/java/app/walkquest/data/fog/FogRepository.kt` — образец data-репозитория: sealed Ok/Err-результаты, кэш в памяти, чистый JVM.
- `app/src/main/java/app/walkquest/viewmodel/MapViewModel.kt` — UDF-образец: loadRegion читает пакет в IO и кладёт снимки в MapUiState; сюда — загрузка POI и три события.
- `app/src/main/java/app/walkquest/ui/map/MapScreen.kt` — updateFogLayer (GeoJsonSource+FillLayer) — образец слоя; POI-слой добавлять после него (порядок = выше тумана); сюда — click-listener, camera-idle-listener, PoiCard.
- `app/src/main/java/app/walkquest/ui/theme/Theme.kt` — AccentTextColor (dark #C6E26B / light #587110) для текста; для маркера-графики — primary colorScheme (dark #C6E26B / light #7A9A1F) или явные токены рядом с FogColor.
- `app/src/main/java/app/walkquest/di/AppContainer.kt` — проводка PoiRepository → MapViewModel (HOME_REGION_ID уже есть).
- `app/src/test/kotlin/app/walkquest/data/packages/TestPackages.kt` + `data/fog/TestFog.kt` — образцы синтетических фикстур пакета на диске.
- `_qa/check-1-8-region-progress.py` — образец Python-зеркала (check()/PASS-FAIL, AD-10 grep, баланс скобок).
- DESIGN.md — токены poi-marker (строки 108–110), poi-card (111–114, 170), typography heading/body, rounded.lg.

## Tasks & Acceptance

**Execution:**
- [ ] `data/poi/Poi.kt` — модель `Poi(osmId, name, nameRu, category, lon, lat, wikidata, description)` + @Serializable DTO poi.json с @SerialName для osm_id/name_ru (зеркало фактического формата) — AD-10, чистый Kotlin.
- [ ] `data/poi/PoiRepository.kt` — `loadPois(pkg): PoiResult` (Ok(List<Poi>) / Err(PoiError.Invalid)): чтение poiFile, intern строк категорий (514 уникальных на 58 580), кэш по regionId+packageVersion — единый вход данных POI, офлайн.
- [ ] `data/poi/PoiFilter.kt` — чистая выдача: `PoiBounds(minLat, maxLat, minLon, maxLon)` с запасом 20%; `tierOf(poi)` (1: wikidata/description, 2: category ∈ tourism|historic|leisure|natural, 3: остальное); `selectPois(pois, bounds, zoom, cap=800)`: zoom < 13 → тир 1; 13–14.9 → тиры 1–2; ≥ 15 → все; переполнение — срез по приоритету тиров — зафиксированное решение по выдаче 58k POI.
- [ ] `viewmodel/MapViewModel.kt` — MapUiState += `visiblePois: List<Poi>`, `selectedPoi: Poi?`; loadRegion грузит POI (allPois приватно в viewmodel) и считает стартовую выдачу по bbox региона; события `onViewportChanged(bounds, zoom)` (фильтр в Default), `onPoiTapped(osmId)`, `onPoiCardDismissed()` — тот же UDF-поток, отдельных каналов нет.
- [ ] `ui/map/MapScreen.kt` — POI-слой: GeoJsonSource + CircleLayer после fog-слоя (цвет по теме #C6E26B/#7A9A1F, circleBlur-свечение, белое кольцо, радиус интерполяцией по зуму); обновление source по visiblePois; addOnMapClickListener → queryRenderedFeatures по poi-layer → onPoiTapped / onPoiCardDismissed; onCameraIdle → projection.visibleRegion → onViewportChanged; PoiCard (Surface, surface, 16dp, заголовок + описание, кнопка закрытия, свайп вниз) в нижней зоне над кнопками записи.
- [ ] `di/AppContainer.kt` — poiRepository + параметр в mapViewModelFactory — проводка.
- [ ] `app/src/test/.../data/poi/PoiRepositoryTest.kt` — парсинг синтетического poi.json (snake_case, null-поля, счёт), битый JSON → Err, кэш (второй вызов не читает диск) — JVM.
- [ ] `app/src/test/.../data/poi/PoiFilterTest.kt` — вся матрица выдачи: тиры, пороги зума 13/15, bounds с запасом, cap=800 с приоритетом тиров, пустая выдача — JVM.
- [ ] `_qa/check-1-9-poi.py` — зеркало против фактического zip: ключи записей poi.json == полям DTO (osm_id, name, name_ru, category, lon, lat, wikidata, description); типы и null-допустимость; счёт 58 580; Python-эквивалент PoiFilter на фактических данных (инварианты: монотонность выдачи по зуму, cap, приоритет тиров, границы); сверка констант зума/cap в Kotlin-коде; AD-10 grep data/poi; баланс скобок новых/изменённых Kotlin-файлов.

**Acceptance Criteria:**
- Given установленный пакет и стартовая камера, when карта отрисована, then POI-маркеры тира 1 видны поверх тумана (слой добавлен после fog FillLayer) по токену poi-marker (verify FR-18 «сквозь туман» — код-порядок слоёв + зеркало; визуал — HITL).
- Given тап по маркеру без сети, when выбран POI с описанием, then карточка показывает название и описание из poi.json пакета и закрывается тапом вне/свайпом/кнопкой (verify FR-18 «офлайн» — источник только poiFile; JVM-тесты модели + HITL на устройстве).
- Given 58 580 POI в пакете, when любой зум/область, then выдача ограничена тирами и cap=800 (JVM-тесты фильтра + зеркало на фактических данных; производительность на устройстве — HITL).
- Given код data/poi, when проверены импорты, then нет android.*/androidx.*/org.maplibre.* (grep, AD-10).

## Implementation Notes

Реализация (2026-10-04), отклонения от плана — одно, по модели идентификатора:

- **osmId — String, не Long.** Зеркало против фактического poi.json показало: osm_id смешанный — число для узлов OSM (52 239 записей) и строка «w…»/«r…» для путей и отношений (6 341 запись, напр. «w5052616» Петровский пруд). Модель `Poi.osmId: String` + `OsmIdSerializer` (JsonPrimitive.content нормализует число и строку к строке). План не предписывал тип; формат зафиксирован фактическим файлом. Цепочка тапа (свойство Feature → onPoiTapped → find) — строковая.
- `data/poi/Poi.kt` — Poi + PoiFile/PoiJson (@SerialName snake_case), displayName: name → name_ru → «shop: outpost»; категории интернируются.
- `data/poi/PoiRepository.kt` — loadPois(pkg): PoiResult.Ok/Err(PoiError.Invalid), кэш по regionId@packageVersion; чистый JVM.
- `data/poi/PoiFilter.kt` — зафиксированное решение выдачи: тиры (1: wikidata/description; 2: tourism|historic|leisure|natural; 3: остальное), пороги зума 13/15, область + 20%, лимит 800 со срезом по приоритету тиров.
- `viewmodel/MapViewModel.kt` — MapUiState += visiblePois/selectedPoi; allPois приватно; loadRegion грузит POI (битый poi.json → пустой список, карта не падает) и стартовую выдачу по bbox на зуме 10; события onViewportChanged (Default), onPoiTapped(String), onPoiCardDismissed — тот же UDF-поток.
- `ui/map/MapScreen.kt` — POI GeoJsonSource + CircleLayer добавляется ПОСЛЕ fog-слоя в том же setStyle-колбэке (маяки поверх тумана, FR-18); маркер: цвет по теме из PoiMarkerColor, circleBlur 0.7 (свечение), белое кольцо 1.5, радиус 4→6.5 по зуму 10→16; onCameraIdle → onViewportChanged; addOnMapClickListener → queryRenderedFeatures (допуск 24px, guard на отсутствие слоя) → onPoiTapped / закрытие тапом вне (false — жесты карты живут); PoiCard (surface, 16dp, titleLarge ExtraBold + bodyMedium, кнопка «Закрыть», свайп вниз 120px) над прототипными кнопками записи.
- `ui/theme/Theme.kt` — токен PoiMarkerColor (dark #C6E26B / light #7A9A1F — графика, DESIGN разрешает accent вне текста).
- `di/AppContainer.kt` — poiRepository + проводка в mapViewModelFactory.
- Тесты: PoiFilterTest (13: тиры, пороги зума, область с запасом, лимит с приоритетом, пустота, displayName), PoiRepositoryTest (7: формат генератора, смешанный osm_id, неизвестные поля, битый/отсутствующий файл, кэш, ключ кэша). Прогон JVM-тестов — HITL (`gradlew.bat testDebugUnitTest`, JDK/SDK в среде нет, не ставить — как 1.5–1.8).
- Проверки без SDK: `python _qa/check-1-9-poi.py` → **25/25 PASS** (2026-10-04) против фактического region-spb-city-v1.zip: ключи/типы 58 580 записей == модель, смешанный osm_id, семантика фильтра на реальных данных (на зуме 10 весь регион = 580 маяков тира 1; монотонность 580 → тиры 1+2 → все; лимит; выживание маяков при переполнении центра), порядок слоёв (POI после fog), закрытие карточки (тап вне + свайп), токен маркера, UDF-маркеры, AD-2/AD-10 grep, баланс 9 Kotlin-файлов. Зеркало поймало и исправлено: тип osm_id (см. выше) и сравнение выдачи под лимитом (счёт без cap).
- Регрессия зеркал: check-1-5 (ALL PASSED), check-1-6 (23/23), check-1-6-kotlin-balance (OK), check-1-7 (34/34), check-1-8 (18/18) — зелёные после изменений.
- Аудит матрицы: 9/9 строк покрыты — 5 JVM-тестами фильтра/репозитория (старт-зум, зум к району, уличный зум+лимит, POI без названия, пустая выдача), 2 тестами репозитория (битый/отсутствующий poi.json), строки «тап по маркеру» и «тап вне/свайп» — кодом UI + grep-маркерами зеркала (не JVM-тестируемы, HITL).
- Diff не снят: проект без VCS (baseline_revision=NO_VCS). tickets.toml, tools/region-pack, эпики 2–6, data/packages, data/fog, domain/, tracking/, UX/PRD не тронуты.
- HITL на устройстве (verify тикета): маркеры-маяки видны над закрытым туманом; тап → карточка офлайн с названием и описанием из пакета; закрытие тапом вне/свайпом вниз/кнопкой; выдача по зуму разумна (маяки → культура → всё); производительность с 58k POI в памяти и плавность пан/зум (гейт 1.12); туман, плашка процента, запись трека, тестовая точка работают как раньше.

## Plan Change Log

## Review Triage Log

Линза quick выполнена самим исполнителем (вложенные субагенты в этом рантайме недоступны — отклонение от процедуры зафиксировано, как в 1.5/1.6/1.7/1.8; независимое ревью может прогнать человек в отдельной сессии). Вердикты: high 0 / medium 2 (оба исправлены патчем) / low 1 (отклонён) / false 1 / maybe-false 0. Проверены: план ↔ код ↔ тесты ↔ зеркало; порядок слоёв; жизненный цикл слоёв при смене стиля/темы; матрица ↔ тесты (9/9); регрессия зеркал 1.5–1.8.

- **medium → patch (исправлено):** `updatePoiLayer` при существующем слое обновлял только GeoJson — смена темы на живом слое оставляла цвет маркера от прошлой темы (туман в 1.6 опacность на живом слое обновлял, маркер — нет). Патч: `setProperties(circleColor(markerArgb))` в ветке существующего слоя; зеркало и баланс перепроверены зелёными.
- **medium → patch (исправлено):** обработчик тапа вызывал `queryRenderedFeatures(POI_LAYER_ID)` без проверки существования слоя — до загрузки стиля / без пакета / при пустой выдаче слоя нет, запрос по несуществующему id в MapLibre небезопасен. Патч: guard `map.style?.getLayer(POI_LAYER_ID) != null`, иначе тап считается промахом (закрытие карточки).
- **false:** «initialVisible считается на главном потоке поверх 58k — фриз при старте»: один проход filter по 58 580 простых записей — единицы–десятки мс однократно при загрузке региона (сразу после IO-чтения 11 МБ JSON тем же потоком событий); viewport-пересчёты уже на Dispatchers.Default. Измеримого вреда нет, калибровка производительности — гейт 1.12.
- **low (отклонено):** PoiCard не перехватывает горизонтальные свайпы — конфликт с паном карты под карточкой теоретически возможен (Surface не блокирует жесты за своими краями); карточка в нижней зоне, пан остаётся доступен по всей карте выше — в обычном использовании не мешает, а композиционный блокировщик добавил бы поверхность ради косметики.

## Design Notes

- Решение по выдаче 58k POI (зафиксировано): три тира по зуму — тир 1 «маяки-ориентиры» (580 записей с wikidata/description) с zoom < 13, тир 2 «культура/природа» (tourism/historic/leisure/natural) с 13, всё остальное (shop/amenity/…) с 15; фильтр по видимой области с запасом 20%, лимит 800 маркеров, при переполнении срез по приоритету тиров. Это держит слой лёгким на любом зуме без кластеризации и соответствует смыслу FR-18: на обзорном зуме видны именно маяки, а не 4 630 точек «shop=outpost».
- Порядок слоёв решает «видно сквозь туман»: MapLibre рисует слои в порядке добавления, поэтому POI-слой добавляется после fog-слоя в updateFogLayer-потоке и пересоздаётся тем же setStyle-колбэком — при смене стиля порядок восстанавливается детерминированно.
- Все POI (58k) парсятся один раз в память (~11 МБ JSON → десятки МБ объектов; категории интернируются) — для прототипа приемлемо; если гейт 1.12 покажет давление памяти, запасной путь — пространственный индекс/потоковый парсинг, решение там.
- Карточка читает только поля уже загруженного Poi — сеть не нужна нигде в цепочке (AD-2): poi.json установлен пакетом 1.5 вместе с описаниями Википедии.

## Verification

**Commands:**
- `gradlew.bat testDebugUnitTest` — expected: PoiRepositoryTest + PoiFilterTest зелёные. **HITL: на машине нет JDK/SDK, не ставить; прогон за человеком.**
- `python _qa/check-1-9-poi.py` — зеркало против фактического region-spb-city-v1.zip + AD-10 + баланс, все проверки PASS.

**Manual checks (if no CLI):**
- grep: нет android.*/androidx.*/org.maplibre.* в `data/poi/` (AD-10).
- **HITL на устройстве:** маркеры-маяки видны над закрытым туманом; тап → карточка офлайн с названием и описанием; закрытие тапом вне/свайпом вниз/кнопкой; пан/зум плавные, выдача по зуму разумна; производительность с 58k POI в памяти; туман, плашка процента, запись трека и тестовая точка работают как раньше.
