---
title: 'Модель территории и мульти-регион'
type: 'feature'
ticket: 10
created: '2026-10-05'
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

**Problem:** Приложение прибито к одному константному региону (AppContainer.HOME_REGION_ID): ни выбора/смены домашнего региона, ни открытия тумана вне его, ни предложения загрузить регион по фактической локации, ни вкладки регионов — FR-4 и AD-9 не реализованы.

**Approach:** Модель территории в domain (AD-10, чистый Kotlin): point-in-polygon по boundaries.geojson пакета определяет регион точки/прогулки; OpenTileAtPoint открывает биты того региона, куда попала точка. Домашний регион — персистентная настройка (data/settings, файл, как FogRepository). Детект «точка в неустановленном регионе» по bbox кэшированного манифеста пакетов → карточка предложения с установкой через существующий InstallRegionPackage (1.5). Вторая вкладка «Регионы»: список установленных со своим процентом из domain (биткарты раздельны с 1.6), выбор домашнего, переход к карте региона.

## Boundaries & Constraints

**Always:**
- AD-9: принадлежность точки региону — только в domain по границам пакета (boundaries.geojson); point-in-polygon (ray casting, Polygon/MultiPolygon с дырками) — чистый Kotlin без android/maplibre (AD-10). Парсинг geojson — data-слой (kotlinx-serialization), domain получает голые кольца.
- Биткарты регионов раздельны (`fog/<regionId>.fog`, 1.6): точка открывает биты только своего региона; прогресс каждого региона считается по его пакету и его биткарте (FogProgress 1.8 без изменения формулы).
- Манифест 1.4 расширяется только опциональными полями (`bbox`, `name` в записи пакета; ignoreUnknownKeys — обратная совместимость); последний скачанный манифест кэшируется на диск рядом с пакетами — детект региона работает офлайн (AD-2).
- Домашний регион: `settings/settings.json` через data/settings (JVM, инжектируемый корень); не задан → первый установленный регион; смена — действие во вкладке «Регионы». Ошибки через границы — sealed-результаты (Consistency Conventions).
- Поток предложения (EXPERIENCE Flow 4): трекинг не прерывается; TrackingService при OpenResult.OutOfRegion спрашивает domain-детект и публикует подсказку в TrackingStateBus; карточка на карте → InstallRegionPackage → карта региона после установки.
- UI по макету remaining-screens.html «Регионы»: строки «процент · название · подпись», домашний процент акцентом (AccentTextColor), чужой — приглушённым лавандовым (#B3A6C9 тёмная / #8D83A6 светлая); форматирование процента только на UI.

**Never:**
- Не менять tickets.toml, эпики 2–6, исходники tools/region-pack (генерировать пакет — можно), publish.md, формат walkability.grid и биткарт 1.6, PoiFilter/PoiRepository 1.9, фильтр/журнал 1.7.
- Не вводить новых gradle-зависимостей (вкладки — material3 NavigationBar, без navigation-compose; DataStore не нужен — json-файл).
- Не смешивать прогресс регионов ни в одном состоянии; не делать авто-установку пакетов — только явным действием пользователя.
- Не ставить JDK/Android SDK: прогон JVM-тестов и verify на устройстве — HITL, фиксируется в плане.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Точка в СПб | Установлен spb-city; точка внутри полигона округа | Бит открыт в spb-city, прогресс СПб растёт | — |
| Точка во втором регионе | Установлены spb-city + gatchina; точка в Гатчине | Бит открыт только в gatchina; прогресс СПб не меняется | — |
| Точка вне всех регионов | Установлен spb-city; точка за границами | OpenResult.OutOfRegion, биткарта не тронута | — |
| Точка на границе/в дырке полигона | Реальные boundaries.geojson | Ray casting: строгое внутри/снаружи, дырки (вода) вычитаются | — |
| Чужой регион по манифесту | Точка вне установленных, но в bbox записи кэш-манифеста | Suggestion(regionId, name) → карточка «Загрузить?» | Нет кэша/совпадений → подсказки нет |
| Установка из предложения | Тап «Загрузить» | InstallRegionPackage (1.5): состояние «загружается» → успех → регион в списке и активен на карте | Failure → error.message на карточке, состояние не портится |
| Домашний не выбран | settings.json отсутствует | Домашний = первый установленный; без пакетов — null (запасная камера СПб) | — |
| Смена домашнего | Тап «Сделать домашним» в списке | settings.json перезаписан атомарно; маркер «домашний регион» переехал; карта по умолчанию — новый домашний | Битый settings.json → дефолт, не краш |
| Битый boundaries.geojson | Пакет установлен, файл не парсится | Регион работает по bbox meta.json (деградация), остальные не страдают | BoundaryResult.Err → bbox-фолбэк |
| Вкладка при двух регионах | Оба установлены, биты в обоих | Каждая строка — свой процент (своя маска+биткарта); суммы/смешения нет | Ошибка чтения одного → его строка «—», список жив |

</frozen-after-approval>

## Code Map

- `tools/region-pack/out/region-spb-city-v1.zip` — фактический boundaries.geojson: 77 Feature kind=city (admin 8, округа), MultiPolygon; meta.json bbox [30.042,59.796,30.63,60.071]. Формат: `{type:FeatureCollection, features:[{properties:{admin_level,name,kind}, geometry}]}` — эталон парсера.
- `tools/region-pack/build.py` — генератор 1.3: `--pbf data/SanktPetersburg.osm.pbf --region-id <id> --bbox …` (локальный pbf центра СПб второй город НЕ покрывает — нужен свой pbf, BBBike-экстракт малого города). Результат в `out/region-<id>-v1.zip`.
- `app/.../data/packages/PackageModels.kt` — ManifestPackage += `bbox: List<Double>? = null`, `name: String? = null` (опциональные); InstalledPackage.boundariesFile уже есть.
- `app/.../data/packages/RegionPackageRepository.kt` — после успешного fetchManifest писать `packagesRoot/manifest-cache.json`; добавить `cachedManifest(): PackageManifest?` (только диск). Поведение installRegion не менять.
- `app/.../domain/OpenTileAtPoint.kt` — переделать на мульти-регион: локатор границ → регион точки → его сетка/биткарта; сигнатуры openAt и OpenResult сохранить (1.7 не ломать).
- `app/.../domain/FogProgress.kt` — ComputeRegionProgress: `compute(regionId)` вместо связки конструктором; формула не трогаем.
- `app/.../data/fog/FogRepository.kt` — loadGrid/loadWalkableMask/open/openedIndices уже параметризованы regionId/пакетом — переиспользовать, не менять.
- `app/.../tracking/TrackingService.kt` + `TrackingStateBus.kt` — при OutOfRegion вызвать DetectForeignRegion, опубликовать подсказку; snapshot += `suggestedRegionId: String?` (сброс на recordingStarted и при установке).
- `app/.../viewmodel/MapViewModel.kt` — вместо HOME_REGION_ID: activeRegionId (дефолт = домашний), `onRegionSelected`, состояния подсказки/установки; перечтение сетки/тумана/POI/прогресса по активному региону — тот же UDF-поток.
- `app/.../ui/map/MapScreen.kt` — карточка предложения региона (под плашкой процента): название, «Загрузить», прогресс/ошибка; POI/туман не трогаем.
- `app/.../MainActivity.kt` — NavigationBar «Карта»/«Регионы» (material3), локальный state вкладки; переход к карте региона = выбор во viewmodel + переключение вкладки.
- `app/.../di/AppContainer.kt` — HOME_REGION_ID удалить; проводка SettingsRepository(filesDir/settings), BoundaryRepository, RegionLocator, DetectForeignRegion, RegionsViewModel; MapViewModel получает settings.
- `_qa/check-1-9-poi.py` — образец зеркала; `_qa/check-1-5-package-install.py` — локальный HTTP-сервер из out/ для проверки установки (паттерн переиспользовать в зеркале 1.10).
- `app/src/test/.../data/packages/TestPackages.kt` — фикстуры zip; добавить regionId/bbox/boundaries-варианты для двух регионов.

## Tasks & Acceptance

**Execution:**
- [ ] `tools/region-pack` — сгенерировать второй малый пакет: скачать BBBike-pbf города-спутника (Гатчина; фолбэк — Выборг), `--bbox` малого контура города → `out/region-gatchina-v1.zip`; verify.py зелёный. Вес и время зафиксировать.
- [ ] `data/boundaries/BoundaryRepository.kt` — парсинг boundaries.geojson (Polygon/MultiPolygon, кольца lon/lat) установленного пакета → RegionBoundary(regionId, bbox, polygons); кэш; sealed BoundaryResult с bbox-фолбэком. JVM, без android.
- [ ] `domain/territory/PointInPolygon.kt` — ray casting по кольцам (внешнее − дырки, multipolygon); чистый объект + RegionLocator.locate(lat, lon, List<RegionBoundary>): bbox-префильтр → PIP; пустые полигоны → bbox-тест.
- [ ] `domain/territory/DetectForeignRegion.kt` — locate по установленным; промах → поиск по bbox кэш-манифеста → Suggestion(regionId, name)?; sealed не нужен, nullable достаточно (нет ошибок наружу).
- [ ] `data/settings/SettingsRepository.kt` — settings.json {homeRegionId}, атомарная запись (temp→rename), битый файл → дефолт; resolveHomeRegion(installed): String?.
- [ ] `data/packages` — ManifestPackage += bbox/name; RegionPackageRepository: кэш манифеста + cachedManifest().
- [ ] `domain/OpenTileAtPoint.kt` — мульти-регион через RegionLocator (пакеты из RegionPackageRepository.installedPackages()); `domain/FogProgress.kt` — compute(regionId).
- [ ] `tracking/TrackingStateBus.kt` + `TrackingService.kt` — подсказка чужого региона в snapshot; сервис вызывает детект при OutOfRegion (не на каждую точку: кэш последнего решения).
- [ ] `viewmodel/MapViewModel.kt` — активный/домашний регион, подсказка+установка (installState: Idle/Installing/Error), onRegionSelected, onInstallSuggested, onDismissSuggestion.
- [ ] `viewmodel/RegionsViewModel.kt` + `ui/regions/RegionsScreen.kt` — список установленных: процент (domain compute по каждому), имя (манифест-кэш → regionId), «домашний регион», действия «Сделать домашним» и переход к карте.
- [ ] `MainActivity.kt` — две вкладки material3; `di/AppContainer.kt` — проводка, удаление HOME_REGION_ID.
- [ ] `app/src/test/.../domain/territory/PointInPolygonTest.kt` — синтетика (квадрат, дырка, multipolygon, снаружи, ребро) + фактические boundaries.geojson обоих zip, если файлы доступны (Assume-пропуск без них).
- [ ] `app/src/test/.../domain/territory/RegionLocatorTest.kt` + `DetectForeignRegionTest.kt` — два региона, промах, подсказка по bbox манифеста, приоритет установленного.
- [ ] `app/src/test/.../data/settings/SettingsRepositoryTest.kt` — roundtrip, битый файл → дефолт, resolveHomeRegion (пусто/один/два региона).
- [ ] `app/src/test/.../domain/MultiRegionProgressTest.kt` — два пакета-фикстуры: открытие в A не меняет снимок B и наоборот (раздельность биткарт).
- [ ] `_qa/check-1-10-territory.py` — зеркало: PIP (shapely) против фактических boundaries.geojson обоих пакетов (точки центров городов внутри своих границ и вне чужих), формат settings.json, раздельность fog-файлов, кэш-манифест с bbox обоих, grep AD-10 на новых файлах, баланс скобок.
- [ ] HITL (пометить, не выполнять): `gradlew testDebugUnitTest` (JDK отсутствует); устройство — предложение загрузки по реальной геолокации, вкладка регионов, установка двух пакетов через локальный HTTP из out/.

**Acceptance Criteria:**
- Given два установленных региона, when точка трека/тестовая точка попадает в один из них, then бит открывается только в её регионе, прогресс другого неизменен (JVM-тесты + зеркало; verify FR-4 «прогресс не смешивается»).
- Given сохранённый домашний регион, when приложение перезапущено, then домашний тот же; смена во вкладке персистентна (JVM-тест settings; verify «выбирается и сохраняется»).
- Given точка вне установленных регионов, но в bbox записи кэш-манифеста, when детект срабатывает, then UI получает предложение загрузить этот регион и кнопка ставит его через InstallRegionPackage (код + JVM-тест детекта; э2э на устройстве — HITL).
- Given вкладка «Регионы» при двух установленных, when она открыта, then обе строки со своими процентами из domain, домашний помечен, тап ведёт к карте региона (код; устройство — HITL).

## Implementation Notes

- Реализовано (2026-10-05): domain/territory (PointInPolygon ray casting + GeoPolygon/RegionBoundary + RegionLocator + HomeRegionPolicy), DetectForeignRegion; data/boundaries (парсинг boundaries.geojson, bbox-фолбэк, кэш); data/settings (settings.json, атомарная запись, formatVersion); data/packages (ManifestPackage += опциональные bbox/name; кэш манифеста manifest-cache.json + cachedManifest()); OpenTileAtPoint мульти-регион через RegionLocator; ComputeRegionProgress.compute(regionId); TrackingStateBus/TrackingService — подсказка чужого региона (детект не чаще ~500 м); MapViewModel (активный/домашний регион, карточка предложения с установкой через InstallRegionPackage 1.5); RegionsViewModel + ui/regions/RegionsScreen (вкладка по макету rg-row); MainActivity — две вкладки material3; AppContainer — HOME_REGION_ID удалён.
- Отклонение по источнику второго пакета: BBBike (Москва, 85 МБ) отдаёт ~60 Б/с — непригодно; скачан дамп СЗФО Geofabrik (654 МБ, ~14 МБ/с), Гатчина собрана из него с малым bbox 29.95,59.50,30.30,59.65: `out/region-gatchina-v1.zip` = 3.4 МБ, verify генератора PASS (11503/33066 проходимых, maxzoom 14), сборка 48 мин (тупик — pyosmium по 654 МБ).
- Находка на фактических данных (зафиксирована в семантике): в boundaries.geojson Гатчины попало отношение «Санкт-Петербург» (admin_level 4), чья геометрия простирается за bbox пакета — сырой point-in-polygon матчит центр СПб. Семантика региона пакета = границы ∩ bbox пакета; bbox-префильтр RegionLocator это закрывает (сетка тумана всё равно живёт только в bbox). Кросс-проверки в зеркале и JVM-тесте идут через локатор (bbox+PIP), не через сырой PIP.
- Зеркало _qa/check-1-10-territory.py прогнано финально: **42 PASS / 0 FAIL** (зеркало PIP == shapely-оракулу на 600 случайных точках фактических границ обоих пакетов; покрытие сетки полигонами: СПб 70%, Гатчина 100%; центры городов относятся к своим регионам).
- VCS отсутствует (NO_VCS): дифф через git не формировался; состав изменений — список CHANGED в зеркале.
- Matrix audit: строки матрицы покрыты JVM-тестами (см. выше) и зеркалом; прогон JVM-тестов — HITL (нет JDK/SDK), строки «установка из предложения» и «вкладка» дополнительно проверяются на устройстве (HITL).

## Plan Change Log

## Review Triage Log

- quick self-review (субагенты недоступны в этом рантайме): high 0 / medium 0 / low 0. Проверены: импорты и сигнатуры всех новых/изменённых файлов, проводка AppContainer ↔ TrackingService/MapViewModel/RegionsViewModel, видимость weight/entries в MainActivity, FQN PaddingValues в RegionsScreen, отсутствие HOME_REGION_ID (grep), обратная совместимость манифеста 1.4 (ignoreUnknownKeys + дефолты), фильтр installedPackages (manifest-cache.json — файл, не папка), Assume-пропуск теста фактических границ. Зеркало подтверждает: AD-10 grep чист, баланс скобок чист, все код-проверки PASS.

## Design Notes

- Почему детект по bbox манифеста, а не по полигонам: манифест 1.4 геометрии не несёт, publish.md заморожен; bbox — опциональное поле записи (обратная совместимость), точности «предложить загрузку» достаточно, решение о границах всё равно принимает domain по полигонам после установки.
- Почему кэш манифеста: AD-2 офлайн-first — после первого скачивания детект не требует сети; запись рядом с пакетами, инвалидация естественная (перезаписывается каждой успешной загрузкой манифеста).
- RegionLocator — не класс с состоянием, а функция над снимком List<RegionBoundary>: состав установленных меняется установкой, кэш границ живёт в data-слое (BoundaryRepository), domain каждый раз работает со свежим списком.
- Имя региона для UI: ManifestPackage.name (опционально) → regionId как фолбэк; красивые имена появятся с публикацией (HITL 1.4), модель это уже допускает.

## Verification

**Commands:**
- `python _qa/check-1-10-territory.py` — expected: все PASS (**прогнано: 42 PASS / 0 FAIL**, оба zip фактические).
- `python tools/region-pack/verify.py tools/region-pack/out/region-gatchina-v1.zip` — expected: PASS (**прогнано генератором: итог PASS, 3.4 МБ**).
- `gradlew testDebugUnitTest` — HITL: JDK/SDK на машине исполнителя нет; прогон человеком, все тесты зелёные (RealBoundariesPointInPolygonTest читает оба zip из tools/region-pack/out — оба на месте).

**Manual checks (if no CLI):**
- HITL на устройстве: локальный HTTP из `tools/region-pack/out` (паттерн _qa/check-1-5), установка обоих пакетов; подстановка геолокации второго региона → карточка предложения → загрузка → вкладка «Регионы» с двумя раздельными процентами; перезапуск — домашний сохранён.
