---
title: 'Туман: биткарта региона и слой на карте'
type: 'feature'
ticket: 6
created: '2026-10-04'
status: built
baseline_revision: 'NO_VCS'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Туман 1.2 — in-memory заглушка на один тайл: состояние не переживает перезапуск, сетки пакета нет, слой — один квадрат на asset-стиле. Без персистентной биткарты по сетке пакета нечего открывать треком (1.7) и не на чем считать процент (1.8).

**Approach:** `data/fog` переписывается на персистентную биткарту «один файл на регион» (AD-4), формат зеркалит walkability.grid (JSON-заголовок + row-major lsb-first битпак, свой formatVersion). Сетка-знаменатель читается из walkability.grid + meta.json установленного пакета. Слой тумана — GeoJsonSource + FillLayer с собственной геометрией поверх style.json пакета (AD-1): полигон bbox с открытыми тайлами-дырками, токен fog-layer (fog #B29ED1, 30% тёмная / 40% светлая). Открытие битов — только через domain (OpenTileAtPoint).

## Boundaries & Constraints

**Always:**
- AD-4: бит = тайл сетки пакета; один файл биткарты на регион; открытие только через domain; UI и слой MapLibre биты не трогают.
- Согласованность сетки с генератором (`tools/region-pack/region_pack/walkability.py`, формат v0): origin=[minlon,minlat] из заголовка grid; dy=tileSizeMeters/111320; dx=tileSizeMeters/(111320·cos(центр bbox по широте)); bbox — из meta.json пакета; index=row·cols+col (row-major), битпак lsb-first.
- Персистентность: запись атомарная (temp-файл → rename, как PackageInstaller 1.5); файл создаётся при первом обращении полностью закрытым (весь регион в тумане).
- AD-10: `data/fog` и `domain` — без импортов android.*/androidx.*/org.maplibre.* (JVM-тестируемо); MapLibre только в `ui/map`.
- UDF по образцу 1.2: MapScreen рендерит MapUiState и шлёт события; ошибки — sealed-результаты, не исключения через границы.
- Слой тумана поверх базовой карты, геометрия своя (не модификация тайлов, AD-1); опacность 0.30 тёмная / 0.40 светлая по `isSystemInDarkTheme`, цвет #B29ED1 обе темы (DESIGN fog-layer).
- Карта: если регион установлен — стиль из `style.json` установленного пакета (file://), камера на центр bbox; иначе — прежний asset://scaffold-style.json (запасной путь).

**Never:**
- Не менять tickets.toml, tools/region-pack, эпики 2–6, data/packages (кроме добавления поля bbox в PackageMeta).
- Не делать процент открытия (1.8), запись трека (1.7), POI (1.9), мульти-регион (1.10) — домашний регион здесь одна константа `spb-city`.
- Не ставить JDK/Android SDK (прогон тестов — HITL); не делать кастомный GL-рендер слоя — FillLayer достаточен, fps меряет гейт 1.12.
- Миграция с in-memory заглушки не нужна — заглушка удаляется.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Первый запуск с пакетом | Файла биткарты нет | Создаётся закрытая биткарта размерности grid; карта полностью в тумане | — |
| Открытие точки | (lat,lon) внутри bbox, бит закрыт | Бит открыт и записан на диск, слой показывает просвет, вернулось Changed | — |
| Идемпотентность | Тот же тайл уже открыт | Состояние не меняется, файл не переписывается, вернулось Unchanged | — |
| Точка вне региона | (lat,lon) вне bbox grid | Бит не открывается, вернулось OutOfRegion, состояние не тронуто | — |
| Перезапуск | Файл биткарты на диске | Открытые биты восстановлены; туман только над закрытыми тайлами | — |
| Битый файл биткарты | Заголовок не JSON / размерность ≠ grid | Ошибка чтения → биткарта пересоздаётся закрытой (туман «на всякий случай»), без краша | FogError.BitmapInvalid, состояние сброшено в закрытое |
| Пакет не установлен | installedPackages() пуст | Карта на asset-стиле без тумана, кнопка тестовой точки скрыта | — |
| Смена темы | dark↔light | fillOpacity 0.30↔0.40 без пересоздания карты | — |

</frozen-after-approval>

## Code Map

- `tools/region-pack/region_pack/walkability.py` — эталон формата grid v0 и формул dx/dy; НЕ менять. Заголовок: {cols, rows, tileSizeMeters, origin:[minlon,minlat], order:"row-major, lsb-first", walkableCount}; битпак `np.packbits(bitorder="little")`.
- `tools/region-pack/out/region-spb-city-v1.zip` — фактические grid (328×307, 100 м, origin [30.042,59.796]) и meta.json (bbox) для Python-зеркала.
- `app/src/main/java/app/walkquest/data/fog/FogRepository.kt` — ПЕРЕПИСАТЬ: заглушка BitSet → персистентные файлы `fog/<regionId>.fog` + парсер walkability.grid.
- `app/src/main/java/app/walkquest/domain/OpenTileAtPoint.kt` — переписать: index через FogGrid установленного пакета вместо заглушки «бит 0»; единственная точка входа открытия (AD-4).
- `app/src/main/java/app/walkquest/data/packages/PackageModels.kt` — добавить `bbox: List<Double>? = null` в PackageMeta (ignoreUnknownKeys уже есть) — нужен для dx.
- `app/src/main/java/app/walkquest/data/packages/RegionPackageRepository.kt` — готовое: getInstalled(regionId), InstalledPackage.walkabilityFile/styleFile; использовать как есть.
- `app/src/main/java/app/walkquest/viewmodel/MapViewModel.kt` — UDF: загрузка пакета+grid+биткарты в MapUiState, событие тестовой точки через OpenTileAtPoint.
- `app/src/main/java/app/walkquest/ui/map/MapScreen.kt` — заменить StubFogMap: стиль пакета (file://) или asset-запас, GeoJsonSource+FillLayer «fog», opacity по теме, камера на bbox.
- `app/src/main/java/app/walkquest/ui/theme/Theme.kt` — готовый FogColor #B29ED1; использовать.
- `app/src/main/java/app/walkquest/di/AppContainer.kt` — проводка FogRepository(fogRoot), OpenTileAtPoint(fogRepository, regionPackageRepository).
- `app/src/test/kotlin/app/walkquest/` — новые тесты data/fog + переписанный domain/OpenTileAtPointTest; TestPackages.kt (1.5) — образец синтетических фикстур.
- `_qa/check-1-5-package-install.py` — образец Python-зеркала для `_qa/check-1-6-fog-bitmap.py`.

## Tasks & Acceptance

**Execution:**
- [x] `data/packages/PackageModels.kt` — поле `bbox` в PackageMeta — dx сетки считается по центру bbox, как в генераторе.
- [x] `data/fog/FogGrid.kt` — @Serializable заголовок grid + FogGrid(cols, rows, tileSizeMeters, originLon/Lat, bbox): indexAt(lat,lon):Int?, tileRing(index): lon/lat-кольцо, bboxRing — чистая JVM-геометрия по формулам генератора.
- [x] `data/fog/FogRepository.kt` — переписать: loadGrid(installedPackage) (парсинг walkability.grid + meta.bbox), loadBitmap(regionId, grid) (файл или закрытая новая; битый файл → закрытая + FogError), open(regionId, grid, index) с атомарной записью; BitSet внутри, формат файла = JSON-заголовок (formatVersion=1, regionId, поля grid) + lsb-first битпак.
- [x] `domain/OpenTileAtPoint.kt` — переписать: openAt(lat,lon) берёт FogGrid домашнего региона (через RegionPackageRepository.getInstalled + fogRepository.loadGrid), indexAt → sealed OpenResult(Changed/Unchanged/OutOfRegion/NoRegion) — единственный путь открытия.
- [x] `viewmodel/MapViewModel.kt` — MapUiState(regionId?, styleUri, cameraTarget/Zoom, grid, openedIndices:Set<Int>, fogEpoch) + загрузка при init + onTraceTestPoint через use-case — UDF-связка.
- [x] `ui/map/MapScreen.kt` — заменить заглушку: стиль пакета/asset, fog GeoJsonSource (полигон bbox + дырки открытых тайлов), FillLayer цвета FogColor, opacity 0.30/0.40 по теме, обновление source при смене openedIndices — кастомная геометрия по AD-1 и токену fog-layer.
- [x] `di/AppContainer.kt` — fogRepository(fogRoot=File(filesDir,"fog")), openTileAtPoint(fogRepository, regionPackageRepository) — проводка, константа HOME_REGION_ID="spb-city".
- [x] `app/src/test/.../data/fog/FogGridTest.kt` + `FogRepositoryTest.kt` — JVM-тесты: парсинг grid-заголовка, indexAt (углы, границы bbox, вне bbox), открытие/идемпотентность/персистентный roundtrip, формат файла (заголовок + lsb-first), битый файл → закрытая биткарта.
- [x] `app/src/test/.../domain/OpenTileAtPointTest.kt` — переписать: точка открывает тайл через grid, повтор идемпотентен, точка вне региона → OutOfRegion, без пакета → NoRegion (синтетический grid, TestPackages-образец).
- [x] `_qa/check-1-6-fog-bitmap.py` — зеркало против фактического zip: формулы dx/dy воспроизводят cols/rows заголовка; lsb-first-упаковка тестовых индексов совпадает с numpy; формат fog-файла читается тем же read_grid-приёмом; grep AD-10 — проверка без SDK.

**Acceptance Criteria:**
- Given установленный пакет региона и чистое хранилище, when приложение стартует, then карта из style.json пакета полностью покрыта туманом #B29ED1 (30% тёмная/40% светлая) по сетке grid (покрыто JVM-тестами логики + зеркалом; визуал — HITL).
- Given открытый ранее тайл, when приложение перезапущено, then бит восстановлен из файла и просвет на месте (JVM roundtrip-тест; HITL — на устройстве).
- Given код domain и data/fog, when проверены импорты, then нет android.*/androidx.*/org.maplibre.* (grep, AD-10).
- Given устройство с SDK (HITL), when пан/зум/поворот и смена темы, then туман движется с картой плавно, опacность по токену (помечено HITL — в среде нет SDK/устройства).

## Implementation Notes

- Реализовано по плану без отклонений по составу файлов. Единственная разница в деталях API: битый файл биткарты не возвращает отдельный `FogError.BitmapInvalid`, а восстанавливается в закрытое состояние с флагом `BitmapResult.Ok.recoveredFromCorrupt=true` — поведение матрицы («пересоздаётся закрытой, без краша») сохранено, UI краха не видит.
- In-memory заглушка 1.2 полностью заменена: `FogRepository` пишет `fog/<regionId>.fog` (JSON-заголовок formatVersion=1 + row-major lsb-first битпак, `BitSet.toByteArray`), атомарная запись temp→rename; чтение — `BitSet.valueOf`. Кэш в памяти на регион, диск — источник истины между запусками.
- Проверки без SDK (2026-10-04): `python _qa/check-1-6-fog-bitmap.py` → 23 PASS / 0 FAIL против фактического `tools/region-pack/out/region-spb-city-v1.zip` (сетка 328×307, 100 м, origin [30.042, 59.796]): формулы dx/dy воспроизводят cols/rows заголовка, `BitSet.toByteArray` ≡ `np.packbits(little)`, indexAt по центрам 200 случайных тайлов фактической сетки — 100% попаданий, формат fog-файла читается read_grid-приёмом. `python _qa/check-1-6-kotlin-balance.py` → все 11 файлов OK. Grep AD-10: `domain/` и `data/fog/` без android.*/androidx.*/org.maplibre.* — чисто.
- Аудит матрицы edge-case: все 8 строк покрыты — 6 строк JVM-тестами (первый запуск, открытие, идемпотентность, вне региона, перезапуск, битый файл), строки «пакет не установлен» (domain-часть тестом `missing package yields NoRegion`, UI-ветка кодом MapScreen) и «смена темы» (LaunchedEffect(darkTheme) → setProperties на живом слое) — UI-часть не JVM-тестируема, визуал HITL.
- HITL (JDK/SDK/устройства в среде нет, не ставить): прогон `gradlew.bat testDebugUnitTest`; визуальная проверка тумана на устройстве; плавность пан/зум/поворот; смена темы 30%↔40%; персистентность между реальными перезапусками.

## Plan Change Log

## Review Triage Log

Линза quick выполнена самим исполнителем (вложенные субагенты в этой среде недоступны — отклонение от процедуры; независимое ревью может прогнать человек в отдельной сессии).

- **medium → patch (исправлено):** `FogRepository.load` — кэш по regionId переиспользовал биткарту без проверки сетки: обновление пакета с другой размерностью в середине сессии работало бы со старой биткартой по новым индексам. Патч: `FogGrid.sameGridAs` + проверка кэша в `load` (несовпадение → чтение с диска → валидация заголовка → сброс в закрытое), тест `stale cached grid reloads from disk instead of reusing old-grid bitmap`. Зеркало и баланс перепроверены зелёными.
- **false:** `MapScreen.updateFogLayer` — `removeLayer`/`removeSource` по несуществующим id (пакет не установлен) в MapLibre no-op с warning, краша нет; источник удаляется после слоя, порядок корректен.
- **false:** `MapScreen` — установка `cameraPosition` до загрузки стиля поддерживается MapLibre (камера сохраняется и применяется к стилю).
- **false:** направление обхода колец (дырки CCW как внешнее кольцо) — конвейер GeoJsonSource MapLibre (earcut/geojson-vt) трактует кольца после первого как дырки позиционно, не по winding; рендер просветов не ломается.
- **low (отклонено):** `MapViewModel.loadRegion` молча остаётся на asset-запасе при битой сетке пакета — маловероятно в обычном использовании (контрольные суммы при установке, 1.5), а вывод ошибки добавляет UI-поверхность вне интента.
- **low (отклонено):** `OpenTileAtPoint.openAt` парсит grid-заголовок на каждый вызов — для ручной тестовой точки несущественно; кэширование сетки добавляет состояние и естественно пересматривается в истории 1.7 (запись трека, вызов на каждую GPS-точку).

## Design Notes

- Формат fog-файла копирует grid v0 (строка JSON-заголовка + row-major lsb-first битпак), плюс formatVersion и regionId в заголовке (Consistency Conventions: версионируемые форматы). Бит i в обоих файлах = один и тот же тайл (index=row·cols+col) — знаменатель процента 1.8 и туман никогда не расходятся по индексации.
- Слой: один GeoJsonSource, Feature = Polygon(exterior=bbox grid, holes=кольца открытых тайлов). Изначально дырок нет — весь регион под завесой; при открытии source.setGeoJson перестраивается. Кольца соседних открытых тайлов могут давать волосяные швы — принято для v1, fps и визуал меряет гейт 1.12; запасной путь — склейка пробежек по строкам, если 1.12 покажет проблему.
- dx считается по центру bbox из meta.json (как генератор), а не выводится из cols: последняя колонка частичная (ceil), обратный расчёт дал бы смещение на краях региона.

## Verification

**Commands:**
- `gradlew.bat testDebugUnitTest` — expected: FogGridTest + FogRepositoryTest + OpenTileAtPointTest зелёные. **HITL: на машине нет JDK/SDK, не ставить; прогон за человеком.**
- `python _qa/check-1-6-fog-bitmap.py` — зеркало формата против фактического region-spb-city-v1.zip, все проверки PASS.

**Manual checks (if no CLI):**
- grep: нет android.*/androidx.*/org.maplibre.* в `domain/` и `data/fog/` (AD-10).
- **HITL на устройстве:** установить пакет (э2э 1.5 после публикации R2) → карта СПб в тумане; тестовая точка открывает тайл у Дворцовой; перезапуск сохраняет просвет; пан/зум/поворот плавные; смена темы меняет опacность 30%↔40%.
