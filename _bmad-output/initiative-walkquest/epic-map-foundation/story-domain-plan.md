---
title: 'Процент открытия региона в domain и индикатор на карте'
type: 'feature'
ticket: 8
created: '2026-10-04'
status: built
baseline_revision: 'NO_VCS'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick (self-review; вложенные субагенты в этом рантайме недоступны — как в 1.5/1.6/1.7)']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Туман открывается треком и тестовой точкой (1.6/1.7), но «честный процент» FR-2 не считается нигде: AD-4 требует единый владелец процента в domain — открытые проходимые биты / все проходимые биты пакета, — а на экране карты индикации нет.

**Approach:** domain получает чистый вычислитель доли (пересечение снимка открытых битов с проходимой маской пакета) и use-case снимка прогресса; data/fog учится отдавать проходимую маску из walkability.grid. MapUiState несёт готовую долю по тому же UDF-потоку (обновляется вместе с fogEpoch), MapScreen только форматирует («17,4%») и показывает индикатор по токенам DESIGN.

## Boundaries & Constraints

**Always:**
- AD-4: процент = открытые проходимые биты / все проходимые биты пакета, вычисляется только в domain; UI и лидерборд берут готовое значение. Знаменатель — проходимые биты walkability.grid пакета (payload, lsb-first; walkableCount заголовка — контрольная сумма маски).
- Consistency Conventions: доля хранится как доля (Double 0..1), форматирование «17,4%» — только на UI-слое; ошибки через границы — sealed-результаты.
- AD-10: domain и новый код data/fog — без android.*/androidx.*/org.maplibre.* (JVM-тестируемо).
- UDF по образцу 1.6/1.7: индикатор обновляется тем же потоком, что openedIndices (загрузка региона, тестовая точка, fogEpoch шины записи) — отдельных каналов нет; OpenTileAtPoint и TrackingService не меняются, оба пути обновляют процент через общий снимок.
- Краевой случай: открытые биты вне проходимой маски (теоретически — смена пакета/сетки) процент не завышают — счёт по пересечению открытых битов с маской; пустой знаменатель (0 проходимых тайлов) → доля 0.

**Never:**
- Не менять tickets.toml, tools/region-pack, эпики 2–6, initiative/PRD/UX-файлы, tracking/, domain/OpenTileAtPoint, data/packages.
- Не делать экран «Прогресс», достижения по проценту (AD-9), мульти-регион (1.10) — только домашний регион и индикатор на карте.
- Не ставить JDK/Android SDK (прогон тестов — HITL); не форматировать строки процента в domain/viewmodel.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Пустой регион | 0 открытых битов, маска N>0 | Доля 0.0 | — |
| Частичное открытие | k открытых битов ⊆ маски N | Доля k/N | — |
| Полное открытие | Открыты все N проходимых | Доля 1.0 | — |
| Биты вне маски | Открытые биты включают непроходимые индексы | Знаменатель и числитель только по маске — завышения нет | — |
| Пустой знаменатель | Маска без единого проходимого бита | Доля 0.0 (деления на ноль нет) | — |
| Порядок открытий | Один и тот же набор битов в любом порядке | Одна и та же доля (побитовое пересечение) | — |
| Пакет не установлен | getInstalled → null | ProgressResult.NoRegion; UI индикатор скрыт (как туман) | — |
| Битая сетка/маска | walkability.grid не парсится | ProgressResult.Failure(FogError), доля не обновляется | типизированная ошибка |

</frozen-after-approval>

## Code Map

- `app/src/main/java/app/walkquest/domain/OpenTileAtPoint.kt` — образец use-case (getInstalled → loadGrid → sealed-результат); НЕ менять, общий путь открытия уже через него.
- `app/src/main/java/app/walkquest/data/fog/FogRepository.kt` — добавить `loadWalkableMask(pkg)`: парсинг заголовка + payload walkability.grid в BitSet (`BitSet.valueOf`, длина (cols*rows+7)/8 — тот же приём readBitmap); sealed MaskResult рядом с GridResult.
- `app/src/main/java/app/walkquest/data/fog/FogGrid.kt` — эталон формул сетки; не менять.
- `tools/region-pack/region_pack/walkability.py` — подтверждено: payload = np.packbits(walkable-матрицы, little), бит 1 = проходимый тайл; НЕ менять.
- `app/src/main/java/app/walkquest/viewmodel/MapViewModel.kt` — MapUiState += `progressFraction: Double?`; обновлять в loadRegion, onTraceTestPoint(Changed), observeTracking(fogEpoch) — там же, где перечитывается openedIndices, через domain use-case.
- `app/src/main/java/app/walkquest/ui/map/MapScreen.kt` — индикатор: плавающая плашка над картой (TopCenter, Surface токен surface, текст акцентом), видна при regionInstalled && progressFraction != null; форматирование здесь.
- `app/src/main/java/app/walkquest/ui/theme/Theme.kt` — токен accent-текста: dark #C6E26B / light #587110 (DESIGN: значения метрик акцентом; #7A9A1F текстом запрещён).
- `app/src/main/java/app/walkquest/di/AppContainer.kt` — проводка ComputeRegionProgress(fogRepository, regionPackageRepository, HOME_REGION_ID) → MapViewModel.
- `app/src/test/kotlin/app/walkquest/data/fog/TestFog.kt` — фикстуры: gridFileBytes с заданной маской (битпак lsb-first), walkableCount в заголовке.
- `app/src/test/kotlin/app/walkquest/domain/OpenTileAtPointTest.kt` — образец domain-теста с TestFog.installPackage.
- `_qa/check-1-6-fog-bitmap.py`, `_qa/check-1-7-tracking.py` — образцы Python-зеркала/баланса без SDK.

## Tasks & Acceptance

**Execution:**
- [ ] `domain/FogProgress.kt` — чистый вычислитель: `fraction(opened: BitSet, walkableMask: BitSet): Double` (clone+and, cardinality/кардинальность маски, маска=0 → 0.0) + use-case `ComputeRegionProgress` (getInstalled → loadGrid → loadWalkableMask → openedIndices-снимок → fraction) с sealed ProgressResult (Ok(fraction, openedWalkable, walkableTotal) / NoRegion / Failure) — AD-4, единый владелец процента.
- [ ] `data/fog/FogRepository.kt` — `loadWalkableMask(pkg): MaskResult` (Ok(BitSet) с cardinality == header.walkableCount при walkableCount>0 — контроль; Err(FogError.GridInvalid)) — маска-знаменатель из пакета.
- [ ] `viewmodel/MapViewModel.kt` — MapUiState += progressFraction (null = нет пакета/ошибка); единый refresh-помощник: openedIndices (слой) + progressFraction (domain) обновляются вместе во всех трёх точках — тот же UDF-поток.
- [ ] `ui/theme/Theme.kt` — `AccentTextColor` (dark #C6E26B / light #587110 по isSystemInDarkTheme) — токен значения метрики.
- [ ] `ui/map/MapScreen.kt` — индикатор процента: Surface-плашка TopCenter, «17,4%» акцентом + подпись «открыто» (паттерн statcol из DESIGN-макета, формат ru-локаль, 1 знак, запятая); только форматирование, без вычислений.
- [ ] `di/AppContainer.kt` — computeRegionProgress + параметр в mapViewModelFactory — проводка.
- [ ] `app/src/test/.../data/fog/TestFog.kt` — фикстура маски: gridFileBytes(walkable: Set<Int>) с честным битпаком и walkableCount — основа тестов.
- [ ] `app/src/test/.../domain/FogProgressTest.kt` — JVM-тесты вычислителя: все строки матрицы (пусто → 0; частичное; полное → 1; биты вне маски; пустой знаменатель; инвариантность к порядку) + use-case: NoRegion без пакета, доля через TestFog-фикстуру, Failure на битой сетке.
- [ ] `app/src/test/.../data/fog/FogRepositoryTest.kt` — += тесты loadWalkableMask: roundtrip маски из фикстуры, cardinality == walkableCount, битый файл → Err.
- [ ] `_qa/check-1-8-region-progress.py` — зеркало без SDK: маска фактического region-spb-city-v1.zip (cardinality == walkableCount), формула доли против numpy для случайных сценариев (включая биты вне маски), форматирование «17,4%» (запятая, 1 знак), grep AD-10, баланс Kotlin.

**Acceptance Criteria:**
- Given установленный пакет и биткарта с открытыми битами, when запрошен прогресс, then domain возвращает долю по пересечению с проходимой маской пакета; тот же снимок даёт одно значение на всех потребителях (verify FR-2; JVM-тесты + зеркало).
- Given открытие бита тестовой точкой или треком, when MapUiState обновляется, then progressFraction меняется предсказуемо (+1/знаменатель) тем же fogEpoch-потоком (код + тест use-case; э2э — HITL).
- Given код domain и data/fog, when проверены импорты, then нет android.*/androidx.*/org.maplibre.* (grep, AD-10).
- Given устройство (HITL), when карта открыта с пакетом, then индикатор показывает «17,4%»-формат и обновляется от тестовой точки и записи трека.

## Implementation Notes

Реализация (2026-10-04), без отклонений от плана по составу файлов:

- `domain/FogProgress.kt` — `FogProgressSnapshot(fraction, openedWalkable, walkableTotal)`; чистый вычислитель `FogProgress.compute(openedIndices: Set<Int>, walkableMask: BitSet)`: числитель — count по пересечению с маской (BitSet.get вне длины → false, биты вне маски не завышают), пустой знаменатель → 0.0; use-case `ComputeRegionProgress.compute()` (getInstalled → loadGrid → loadWalkableMask → openedIndices → FogProgress) с sealed ProgressResult (Ok/NoRegion/Failure).
- `data/fog/FogRepository.kt` — `loadWalkableMask(pkg): MaskResult` (Ok(BitSet)/Err(FogError.GridInvalid)): заголовок + payload walkability.grid, длина битпака ceil(n/8), контроль cardinality == header.walkableCount при walkableCount > 0 (фикстуры 1.6 с walkableCount=0 контроль пропускают — обратная совместимость).
- `viewmodel/MapViewModel.kt` — MapUiState += `progressFraction: Double?` (null = нет пакета/снимка); доля перечитывается через `computeRegionProgress.compute()` ровно в тех же трёх точках, что openedIndices (loadRegion, onTraceTestPoint-Changed, observeTracking по fogEpoch шины 1.7) — отдельного канала нет; при Failure/NoRegion прежнее значение сохраняется.
- `ui/theme/Theme.kt` — токен `AccentTextColor` (dark #C6E26B / light #587110, DESIGN: #7A9A1F текстом запрещён).
- `ui/map/MapScreen.kt` — `RegionProgressBadge` (Surface-плашка TopCenter, значение акцентом + «открыто», паттерн statcol макета); форматирование `formatPercent` — единственное место: String.format(Locale.forLanguageTag("ru-RU"), "%.1f%%", fraction*100) → «17,4%»; видна при progressFraction != null.
- `di/AppContainer.kt` — computeRegionProgress + параметр в mapViewModelFactory. OpenTileAtPoint, TrackingService, tracking/ не тронуты — оба пути (тестовая точка, трек) сходятся в FogRepository.open, а прогресс читается снимком из domain.
- Тесты: `domain/FogProgressTest.kt` (11: вся матрица вычислителя + use-case через TestFog-маску, единый путь с OpenTileAtPoint, NoRegion, Failure, контроль walkableCount), `FogRepositoryTest.kt` += 3 (roundtrip маски lsb-first, контроль walkableCount, битый файл), `TestFog.kt` += фикстура маски (параметры с умолчаниями — существующие тесты не тронуты).
- Прогон JVM-тестов отложен (нет JDK/SDK, HITL `gradlew.bat testDebugUnitTest`) — как в 1.5/1.6/1.7. Вместо прогона — зеркало `_qa/check-1-8-region-progress.py`: **18/18 PASS** (2026-10-04): знаменатель фактического пакета (cardinality == walkableCount), формула доли против numpy на 200 случайных сценариях с битами вне маски, все краевые случаи, формат «17,4%», форматирование только на UI (domain/viewmodel чисты), AD-10 grep, баланс Kotlin 9 файлов. Регрессия: check-1-6 (23/23), check-1-6-kotlin-balance (все OK), check-1-7 (34/34) — зелёные после изменений.
- Аудит матрицы: 8/8 строк покрыты — 7 JVM-тестами (прогон HITL), строка «пакет не установлен» дополнительно кодом UI (плашка скрыта при null); «битая сетка/маска» — двумя тестами Failure.
- Diff не снят: проект без VCS (baseline_revision=NO_VCS). Изменения: новые domain/FogProgress.kt, FogProgressTest.kt, _qa/check-1-8-region-progress.py; правки FogRepository.kt, MapViewModel.kt, MapScreen.kt, Theme.kt, AppContainer.kt, TestFog.kt, FogRepositoryTest.kt. tickets.toml, tools/region-pack, tracking/, data/packages, UX/PRD не тронуты.

## Plan Change Log

## Review Triage Log

Quick self-review (вложенные субагенты в этом рантайме недоступны — отклонение от процедуры зафиксировано, как в 1.5/1.6/1.7; независимое ревью может прогнать человек). Вердикты: high 0 / medium 0 / low 2 (оба отклонены) / false 0 / maybe-false 0. Проверены: план ↔ код ↔ тесты ↔ зеркало; AD-4 (вычисление только в domain, viewmodel берёт готовую долю — grep-маркеры зеркала); форматирование только на UI; матрица ↔ тесты (8/8); регрессия зеркал 1.6/1.7 (23/23, 34/34).

- **low (отклонено):** `ComputeRegionProgress.compute()` перечитывает grid+маску+биткарту на каждый fogEpoch записи (~13 КБ × 3 на GPS-точку, 1 Гц) — тот же класс нагрузки, что и перечитывание openedIndices из 1.7; для прототипа несущественно, калибровка производительности — spike 1.11/гейт 1.12, а кэширование маски добавляет состояние (ср. отклонённый low про кэш сетки в 1.6).
- **low (отклонено):** `MapViewModel` читает openedIndices и fraction двумя снимками подряд — бит, открытый между чтениями (запись трека на HandlerThread), дал бы рассинхрон «просвет/процент» на один кадр; самолечится следующей точкой/fogEpoch, а слияние чтений в один вызов связало бы data/fog новым комбинированным API ради косметики.

## Design Notes

- Доля — Double 0..1 в MapUiState (Consistency Conventions); строка «17,4%» рождается только в MapScreen: `String.format(Locale("ru"), "%.1f%%", fraction * 100)` даёт запятую; индикатор скрыт при progressFraction == null (нет пакета — нет и тумана, зеркалит regionInstalled).
- Пересечение, а не «открыто & walkableCount заголовка»: opened-биткарта живёт своим файлом и теоретически может содержать биты вне актуальной маски (смена сетки пакета) — числитель по AND с маской не завышается никогда; walkableCount заголовка используется только как контроль целостности маски при чтении.
- Знаменатель читается из того же walkability.grid, что и сетка тумана (один источник бит-индексации, 1.6) — процент и просветы не расходятся по тайлам.

## Verification

**Commands:**
- `gradlew.bat testDebugUnitTest` — expected: FogProgressTest + новые тесты FogRepositoryTest зелёные. **HITL: на машине нет JDK/SDK, не ставить; прогон за человеком.**
- `python _qa/check-1-8-region-progress.py` — зеркало против фактического region-spb-city-v1.zip + форматирование + AD-10 + баланс, все проверки PASS.

**Manual checks (if no CLI):**
- grep: нет android.*/androidx.*/org.maplibre.* в `domain/` и новом коде `data/fog/` (AD-10).
- **HITL на устройстве:** индикатор процента на карте; тестовая точка и запись трека меняют его предсказуемо; одно значение до/после перезапуска (verify «одно значение на всех экранах» — пока экран один, источник един: domain + StateFlow).
