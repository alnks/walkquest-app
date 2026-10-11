---
title: 'Чёткие контуры водоёмов из OSM + debug-режим карты с ползунками тумана'
type: 'feature'
ticket: ''
created: '2026-10-09'
status: 'in-review'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: []
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Контуры водоёмов на карте размыты: вдоль берега лежит haze-линия с lineBlur шириной ~90 м (0,9 тайла), а завеса вырезает воду грубыми клетками 100 м (water.grid) — берег «плывёт» и затуманен. Отладочные параметры кромки тропы и плотности тумана зашиты константами — подбор требует пересборки.

**Approach:** Вода рисуется только точными полигонами OSM: векторный FillLayer (source-layer `water` пакета, схема OpenMapTiles) поверх завесы и поверх эффектов тропы; сеточные просветы воды и blur-кромка воды убираются. В debuggable-сборке на карте появляется видимый тогл debug-режима; в режиме — ползунки радиуса и силы размытия кромки тропы и прозрачности тумана с живыми числовыми значениями рядом (их владелец потом перенесёт в константы).

## Boundaries & Constraints

**Always:**
- Границы воды — только из Open map (векторные полигоны source-layer `water` источника `region`, данные OSM/OMT из пакета); сетка 100 м для рендера воды больше не используется.
- Вода чёткая всегда: водяной слой непрозрачный, рисуется выше завесы, ореола и пера кромки (glow/feather не заливают береговую линию).
- Туман над водой не виден: водяной слой перекрывает завесу точно по полигону, при любом значении ползунка прозрачности тумана.
- Debug-режим — только debuggable-сборки (ApplicationInfo.FLAG_DEBUGGABLE, как оверлей 1.14); в release ни тогла, ни панели.
- UDF/AD-10: domain и data не трогаем по импортам; изменения рендера — в `ui/map`, состояние debug-параметров — память композиции (без персистентности).
- Значения по умолчанию ползунков = текущие константы (14 м / 0,55 / 1,0): без действий пользователя картинка не меняется, кроме убранного размытия воды.
- Обратная совместимость: пакет без векторного источника (asset-запас) — водяного слоя нет, поведение как сейчас без воды.

**Never:**
- Не менять tools/region-pack, формат пакета, water.grid в пакете, учёт открытия (биткарта/процент, AD-4) и запись трека.
- Не удалять data-примитивы `WaterMask.kt`/`FogRepository.loadWaterMask`/`FogGrid.runRing` и их JVM-тесты (перестают использоваться UI, остаются покрытыми возможностями data-слоя).
- Не делать персистентность debug-параметров и не выводить ползунки в release.
- Не добавлять новых зависимостей; субагенты в этой среде недоступны — реализация напрямую.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Вода с пакетом | Установленный регион, стиль пакета | Береговая линия — точный полигон OSM, без blur-полосы и сеточных ступеней; завеса доходит до берега | — |
| Тропа у воды | Запись вдоль/над водой | Просвет тропы виден и над водой; ореол/перо не мажут берег (вода выше их слоёв) | — |
| Asset-запас | Пакет не установлен | Ни тумана, ни водяного слоя, как прежде | — |
| Старый пакет v1/v2 | Нет water.grid, есть источник `region` | Вода чёткая из вектора (маска больше и не нужна) | — |
| Тогл debug | debuggable-сборка, тап по кнопке | Панель: статистика 1.14 + 3 ползунка с значениями; повторный тап скрывает | — |
| Ползунок радиуса | 0..40 м | lineBlur пера кромки = значение (внешний хвост × 34/14); значение показано рядом | — |
| Ползунок силы | 0..1 | lineOpacity пера = значение (внешний × 0,4); значение показано | — |
| Ползунок тумана | 0..1 | fillOpacity завесы = значение; вода остаётся непрозрачной и чёткой | — |
| Release-сборка | не debuggable | Тогла и панели нет; параметры — константы по умолчанию | — |

</frozen-after-approval>

## Code Map

- `app/src/main/java/app/walkquest/ui/map/MapScreen.kt` — ВСЕ изменения рендера и debug-UI: `fogPolygon` (выкинуть waterRuns), `updateFogLayer` (fillOpacity параметром), `updateWaterVectorLayer` (порядок выше glow/feather), `updateFogGlowLayer`/`updateTrailFeatherLayer` (addLayerBelow водяного слоя; blur/opacity пера параметрами), УДАЛИТЬ `updateWaterHazeLayer` + константы WATER_HAZE_*; новый `DebugFogParams` + тогл-кнопка + панель ползунков; DebugOverlay (1.14) встраивается в панель.
- `app/src/main/java/app/walkquest/viewmodel/MapViewModel.kt` — убрать `waterRuns`/`waterEdges` из MapUiState, LoadedRegion и loadRegion (импорты RowRun/GridEdge/toRowRuns/toBoundaryEdges, вызов loadWaterMask).
- `app/src/main/java/app/walkquest/data/fog/WaterMask.kt`, `FogRepository.kt` (`loadWaterMask`, MaskResult), `FogGrid.kt` (`runRing`) — НЕ менять (MaskResult нужен FogProgress через loadWalkableMask).
- `app/src/main/java/app/walkquest/ui/theme/Theme.kt` — токены FogCoveredColor/FogGlowColor; использовать как есть.
- `_qa/wq.py` + `_qa/README.md` — цикл эмулятора (doctor/emu-start/install/seed-package/launch/replay/shot); JDK 17 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` для gradle.
- `app/src/test/kotlin/app/walkquest/data/fog/WaterMaskTest.kt` — тесты data-примитивов, остаются зелёными без изменений.
- Константы-тюнеры в MapScreen: FEATHER_BLUR_METERS=14.0, FEATHER_OPACITY=0.55, FEATHER_OUTER_BLUR_METERS=34.0, FEATHER_OUTER_OPACITY=0.22, FOG_OPACITY=1.0 — дефолты ползунков.

## Tasks & Acceptance

**Execution:**
- [ ] `ui/map/MapScreen.kt` — вода: fogPolygon без водяных прогонов; updateWaterVectorLayer добавляется после glow/feather (вода выше), те при создании кладутся addLayerBelow(WATER_VECTOR_LAYER_ID), если слой есть; удалить updateWaterHazeLayer и его вызовы/константы — чёткая вода из OSM-полигонов без блюра.
- [ ] `ui/map/MapScreen.kt` — debug-режим: data class DebugFogParams(featherBlurMeters=14, featherStrength=0.55, fogOpacity=1.0); видимый тогл (кнопка-жук, BottomStart) при FLAG_DEBUGGABLE; панель = статистика 1.14 + 3 Slider с подписями и живыми значениями (ru-формат); 5-таповый жест сохраняется как второй тогл.
- [ ] `ui/map/MapScreen.kt` — применение параметров: updateFogLayer принимает fogOpacity (fillOpacity и при создании, и в setProperties обновления); updateTrailFeatherLayer принимает blurMeters/strength (outer = ×34/14 и ×0,4 соответственно); LaunchedEffect-ы слоёв ключуются на DebugFogParams.
- [ ] `viewmodel/MapViewModel.kt` — вычистить waterRuns/waterEdges из состояния и загрузки (маска воды UI больше не потребляет).
- [ ] Прогон `gradlew.bat testDebugUnitTest` (JAVA_HOME=JDK 17) — все тесты зелёные, включая WaterMaskTest без правок.
- [ ] Эмулятор (`_qa/wq.py`): seed v5, replay трека, скриншоты — берег Невы чёткий; панель с ползунками; сдвиг ползунков меняет картинку живьём.

**Acceptance Criteria:**
- Given установленный пакет v5, when карта показывает Неву/залив, then контур воды — точная полилиния OSM без blur-полосы, сеточных ступеней и тумана поверх воды (скриншот-проверка на эмуляторе).
- Given тропа записана вдоль берега/моста, when смотрим кромку, then ореол и перо не перекрывают водяной полигон (вода выше их слоёв).
- Given debuggable-сборка, when тап по тоглу, then видна панель с ползунками радиуса блюра кромки (м), силы блюра и прозрачности тумана; рядом — текущие числовые значения; движение ползунка немедленно меняет рендер.
- Given release-сборка (не debuggable), when смотрим карту, then ни тогла, ни панели; рендер на дефолтных константах.
- Given изменения data/domain, when `testDebugUnitTest`, then весь набор зелёный без правок существующих тестов.

## Implementation Notes

- Реализовано по плану. Вода: `fogPolygon` больше не вырезает водяные прогоны сетки — завеса лежит и над водой; единственный рендер воды — непрозрачный векторный `water-veil-layer` (полигоны OSM/OMT из пакета) ПОВЕРХ завесы, ореола и пера (glow/feather при создании кладутся `addTrailEffectLayer` = addLayerBelow под водяной, если тот есть). Haze-слой (`updateWaterHazeLayer`, ~90 м blur-полоса вдоль берега) удалён вместе с константами WATER_HAZE_*; `waterRuns`/`waterEdges` вычищены из MapUiState/loadRegion (data-примитивы WaterMask/FogGrid.runRing/FogRepository.loadWaterMask оставлены с тестами).
- Debug-режим: кнопка-тогл «DBG» (BottomStart, только FLAG_DEBUGGABLE) + прежний жест 5 тапов; панель = статистика 1.14 + 3 ползунка (блюр кромки 0..40 м, сила 0..1, прозрачность тумана 0..1) с живыми значениями в подписях. `DebugFogParams` живёт в remember композиции; применение — через LaunchedEffect(debugParams) в FogMap; дефолты = константы (14,0 м / 0,55 / 1,00). Внешний хвост пера следует за ползунками отношениями ×34/14 и ×0,22/0,55.
- Drive-by фикс (блокировал оценку блюра после перезапуска): `TrackJournal.JournalHeader.startedAtEpochMillis` имел дефолт `System.currentTimeMillis()` — при encodeDefaults=false заголовок писался как `{}` (подтверждено побайтово на журналах эмулятора), при чтении startedAt = «сейчас чтения» → replay TrackHistory отсекал все точки как StaleFix → тропа не восстанавливалась после перезапуска. Дефолт заменён на 0: при записи значение всегда кодируется, легаси `{}`-журналы читаются со startedAt=0 (правило StaleFix выключено, точки replay'ятся). Регрессионные тесты в TrackJournalTest (+2).
- Проверка (2026-10-09): `gradlew.bat testDebugUnitTest` — 224 теста, 0 падений. На эмуляторе (прогон water-debug-verify до распоряжения владельца прекратить эмуляторные тесты): чёткий берег Невы/залива без haze-полосы на скриншотах e2/e12; панель ползунков работает (значения меняются живьём); A/B пера 14 м vs 0,7 м — попиксельный diff показывает изменения ровно вдоль кромки тропы (diff-e14-e15.png) → механика блюра работает; на обзорном зуме (z10–11) блюр в метрах субпикселен — поэтому на телефоне казался «не работающим».
- Телефон владельца (RZGL41TWZ6T): `wq.py phone-install` 2026-10-09 16:56, запущено; данные (пакет v5, треки) сохранены.

## Plan Change Log

## Review Triage Log

Линза quick выполнена самим исполнителем (субагенты в этой среде недоступны — как в плане 1.6).

- **medium → patch (исправлено, drive-by):** TrackJournal — пустой заголовок `{}` ломал восстановление тропы (см. Implementation Notes); патч + 2 регрессионных теста.
- **false:** «блюр кромки тропы не работает» (репорт владельца) — опровергнуто попиксельным diff e14/e15: кромка меняется с ползунком; эффект незаметен на обзорном зуме, т.к. задан в метрах.
- **low (принято):** во время драга ползунка updateFogLayer перестраивает кольца тропы на каждый тик — геометрия мала (сотни вершин), для debug-сессии допустимо.
- **low (принято):** ползунки без персистентности — выбранные значения переносятся в константы вручную, как и задумано.

## Design Notes

- Вода из вектора уже существует (`water-veil-layer`): план не добавляет новый источник, а делает его ЕДИНСТВЕННЫМ рендером воды — завеса перестаёт вырезать воду сеткой (ступени 100 м у берега исчезают), haze-полоса 90 м удаляется. Непрозрачный FillLayer поверх завесы = «без тумана» при любой прозрачности завесы.
- Инвариант порядка слоёв: glow/feather при создании — addLayerBelow(WATER_VECTOR_LAYER_ID) (иначе слой, созданный mid-session поверх воды, зальёт берег); вода после них в setStyle-колбэке; POI/«я» — выше воды как прежде.
- DebugFogParams живёт в remember композиции: подбор параметров — сессионная работа, персистентность не нужна; выбранные значения владелец переносит в константы правкой кода (поэтому значения показаны рядом с ползунками).
- Внешний хвост пера привязан к главному ползунку фиксированными отношениями (×34/14 по радиусу, ×0,4 по силе) — два ползунка вместо четырёх, пропорции текущей калибровки сохранены.

## Verification

**Commands:**
- `set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot && gradlew.bat testDebugUnitTest` — expected: все тесты зелёные.
- `python _qa/wq.py doctor && python _qa/wq.py emu-start && python _qa/wq.py install && python _qa/wq.py clear && python _qa/wq.py seed-package && python _qa/wq.py launch` — expected: приложение на эмуляторе с пакетом v5.
- `python _qa/wq.py replay <фикстура fix7-walk.track>` + `python _qa/wq.py shot water-crisp.png` — expected: чёткий берег; далее тогл debug и скриншоты ползунков на разных значениях.

**Manual checks (if no CLI):**
- grep: WATER_HAZE_/updateWaterHazeLayer отсутствуют; в MapUiState нет waterRuns/waterEdges.
- Скриншоты `_qa/emulator-run/water-debug-verify/`: берег чёткий; панель видна только в debug; значения у ползунков читаются.
