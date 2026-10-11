---
title: 'Прототип записи трека и открытие тайлов'
type: 'feature'
ticket: 7
created: '2026-10-04'
status: built
baseline_revision: 'NO_VCS'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick (self-review; вложенные субагенты в этом рантайме недоступны — как в 1.5/1.6)']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Туман региона персистентен (1.6), но открывать его может только ручная тестовая кнопка: нет записи реального перемещения, без неё не проверить AD-8 (батарея/fps на живом треке) и нечем кормить фильтрацию точек, которую AD-4 требует до открытия битов.

**Approach:** Пакет `tracking` наполняется прототипом записи: foreground-сервис с постоянным уведомлением (LocationManager GPS, офлайн AD-2) пишет каждую точку в append-only журнал на диск (AD-5), чистый JVM-фильтр отсекает превышение скорости и телепортации, валидные точки идут в domain `OpenTileAtPoint` (AD-4). Минимальный UI старт/стоп по UDF-образцу 1.6.

## Boundaries & Constraints

**Always:**
- AD-4: биты открываются только через domain `OpenTileAtPoint` — сервис вызывает его на каждую принятую точку, ни UI, ни сервис не трогают биткарту напрямую.
- AD-5: каждая точка пишется в журнал немедленно (append + fsync) — убийство процесса теряет секунды, не трек; журнал версионируемый (`formatVersion`, Consistency Conventions), чтение терпимо к усечённому хвосту.
- AD-10: фильтр, модель точки, журнал и шина состояния — чистый Kotlin/JVM без android.* (unit-тестируемо); android-зависимости только в `TrackingService` и UI-обвязке.
- Локация — `android.location.LocationManager` (GPS): новых зависимостей (play-services-location) не вводим, офлайн-first, spine пин Fused не обязателен («актуальная стабильная — пин при создании проекта» — решение исполнителя зафиксировано здесь).
- Разрешения по minSdk 29 / target 36: ACCESS_FINE+COARSE_LOCATION, POST_NOTIFICATIONS (runtime, запрос из UI перед стартом), FOREGROUND_SERVICE + FOREGROUND_SERVICE_LOCATION и `foregroundServiceType="location"` в манифесте.
- UDF по образцу 1.6: MapScreen рендерит MapUiState и шлёт события; состояние записи приходит через StateFlow-шину, viewmodel обновляет `openedIndices` при изменении биткарты.
- Ошибки через границы — sealed-результаты (Consistency Conventions); логи без координат (AD-3).

**Never:**
- Не делать автопаузу, батарейный страж, финальный экран прогулки, статистику, Room-историю прогулок — epic-walk-tracking; журнал прототипа — файл, миграция в Room не входит.
- Не менять tickets.toml, tools/region-pack, эпики 2–6, data/fog, data/packages, domain (OpenTileAtPoint используется как есть).
- Не калибровать лимиты скорости — константы-гипотезы 12/30 км/ч (unknown тикета, spine Deferred, полевые тесты — человек); не ставить JDK/Android SDK (прогон тестов — HITL).
- Не хранить координаты в уведомлении/логах (AD-3); не отправлять трек куда-либо (AD-3).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Запись прогулки | Разрешения даны, GPS-точки идут | Каждая точка — строка в журнале сразу (fsync); валидные открывают тайлы через domain; уведомление висит | — |
| Превышение скорости | implied speed > лимита | Точка в журнале (сырая правда), но Rejected.TooFast; тайл не открывается; якорь фильтра не двигается | — |
| Телепортация | Скачок > порога дистанции (dt любой) | Rejected.Teleport; тайл не открывается | — |
| Немонотонное время | dt ≤ 0 между фиксами | Rejected.NonMonotonicTime; якорь не двигается | — |
| Убийство процесса | Запись шла, процесс убит | Журнал на диске цел до последней fsync-точки; усечённый хвост при чтении отбрасывается без краха | JournalRead.Truncated |
| Битый журнал | Заголовок не читается / formatVersion ≠ 1 | Чтение — типизированная ошибка, исключения наружу не летят | JournalRead.Invalid |
| Повторный старт | Запись уже идёт | ACTION_START игнорируется (идемпотентно) | — |
| Стоп без записи | Запись не идёт | ACTION_STOP — no-op | — |
| Нет разрешений | Пользователь отказал | Запись не стартует, кнопка остаётся «Старт» | состояние без краша |
| Точка вне региона / без пакета | OpenResult.OutOfRegion/NoRegion | Точка принята фильтром и записана, тайл не открывается — штатно | — |

</frozen-after-approval>

## Code Map

- `app/src/main/java/app/walkquest/tracking/Placeholder.kt` — УДАЛИТЬ: заглушка границы пакета 1.2, наполнение — эта история.
- `app/src/main/java/app/walkquest/domain/OpenTileAtPoint.kt` — единственный путь открытия битов (AD-4); сервис вызывает `openAt(lat, lon)` на принятых точках. Не менять.
- `app/src/main/java/app/walkquest/data/fog/FogGrid.kt` — `indexAt` есть; для фильтра нужен только haversine (свой, в tracking) — data/fog не трогаем.
- `app/src/main/java/app/walkquest/viewmodel/MapViewModel.kt` + `MapUiState` — добавить поля записи (isRecording, accepted/rejected счётчики) и сбор шины; `openedIndices` перечитывается при fogEpoch шины.
- `app/src/main/java/app/walkquest/ui/map/MapScreen.kt` — кнопка старт/стоп + permission-launcher (RequestMultiplePermissions); образец UDF и MapView-лайфсайкла — весь файл.
- `app/src/main/java/app/walkquest/di/AppContainer.kt` — проводка шины состояния (синглтон контейнера) и констант лимитов; сервис берёт зависимости через `WalkQuestApplication.container`.
- `app/src/main/AndroidManifest.xml` — разрешения + `<service>` с foregroundServiceType="location", exported=false.
- `app/src/test/kotlin/app/walkquest/data/fog/TestFog.kt` — образец фикстур/стиля тестов; `_qa/check-1-6-fog-bitmap.py` и `check-1-6-kotlin-balance.py` — образцы Python-зеркала и баланса Kotlin без SDK.
- `_bmad-output/.../architecture-walkquest.md` — AD-2, AD-3, AD-4, AD-5, AD-10, Consistency Conventions (formatVersion, sealed-результаты, логи без координат).

## Tasks & Acceptance

**Execution:**
- [x] `tracking/TrackModels.kt` — TrackPoint(lat, lon, epochMillis), haversineMeters, SpeedLimits (WALKING=12.0, CYCLING=30.0 км/ч — гипотезы, unknown тикета/HITL-калибровка), TELEPORT_JUMP константа — чистый Kotlin.
- [x] `tracking/PointFilter.kt` — TrackFilter(limitMps): check(point) → sealed FilterVerdict (Accepted / Rejected.TooFast / Rejected.Teleport / Rejected.NonMonotonicTime); якорь — только принятые точки — сердце verify тикета.
- [x] `tracking/TrackJournal.kt` — append-only журнал `tracks/<name>.track`: строка-заголовок JSON (formatVersion=1) + JSONL точек, append+fsync на каждую; read(): sealed JournalRead (Ok(points, truncatedTail) / Invalid) — терпимость к усечению (AD-5).
- [x] `tracking/TrackingStateBus.kt` — StateFlow-снимок (isRecording, accepted, rejected, fogEpoch) — мост сервис→viewmodel без android.*.
- [x] `tracking/TrackingService.kt` — foreground-сервис: канал+постоянное уведомление, LocationManager GPS на HandlerThread, ACTION_START/STOP идемпотентные, журнал→фильтр→OpenTileAtPoint, обновление шины — вся android-обвязка здесь.
- [x] `AndroidManifest.xml` — 5 разрешений + объявление сервиса (location type, не exported).
- [x] `di/AppContainer.kt`, `viewmodel/MapViewModel.kt` — шина в контейнере; MapUiState += поля записи; viewmodel собирает шину и перечитывает openedIndices по fogEpoch.
- [x] `ui/map/MapScreen.kt` — кнопка «Начать запись»/«Остановить», запрос FINE+COARSE+POST_NOTIFICATIONS, startForegroundService(ACTION_START)/ACTION_STOP.
- [x] `app/src/test/.../tracking/PointFilterTest.kt` — JVM-тесты: валидная цепочка, превышение скорости, телепортация (большой скачок при большом dt и при малом), немонотонное время, граница ровно на лимите принимается, якорь не двигается после reject, первая точка всегда принята.
- [x] `app/src/test/.../tracking/TrackJournalTest.kt` — roundtrip, порядок, усечённый хвост (обрыв mid-line) → Ok с truncatedTail, битый заголовок → Invalid, дозапись после переоткрытия, пустой журнал.
- [x] `_qa/check-1-7-tracking.py` — зеркало без SDK: журнал (писатель/читатель на Python по той же спеке, roundtrip + усечение), формулы фильтра против констант из Kotlin-файла, валидация манифеста (разрешения/сервис), grep AD-10 (android.* только в TrackingService/UI), баланс скобок Kotlin.

**Acceptance Criteria:**
- Given разрешения и пакет региона, when запись идёт и приходят валидные точки, then тайлы открываются только через OpenTileAtPoint, каждая точка в журнале до обработки (JVM-тесты журнала/фильтра; э2э на устройстве — HITL).
- Given точки выше лимита скорости и телепортации, when фильтр их обрабатывает, then они в журнале, но тайлы по ним не открываются (JVM-тесты фильтра).
- Given процесс убит во время записи, when журнал прочитан, then все fsync-точки восстановлены, усечённый хвост отброшен без краха (JVM-тест; поведение на устройстве — HITL).
- Given код tracking/domain, when проверены импорты, then android.* только в TrackingService и ui (grep, AD-10).
- Given устройство (HITL), when офлайн-прогулка записана, then туман расступается по валидным точкам, убийство процесса теряет секунды, не трек (verify тикета — человек).

## Implementation Notes

Реализация (2026-10-04), все решения исполнителя зафиксированы здесь:

- Пакет `tracking` наполнен по плану, без отклонений по составу файлов: `TrackModels.kt` (TrackPoint, haversine R=6371000, SpeedLimits 12/30 км/ч — гипотезы unknown тикета, TELEPORT_WINDOW_SECONDS=120), `PointFilter.kt` (якорь — последняя принятая точка; порядок проверок: немонотонное время → телепортация → скорость; отсечение строгое «>»), `TrackJournal.kt` (заголовок formatVersion=1 + JSONL; FileOutputStream(append) + flush + fd.sync() на каждую точку; read терпим к усечению: decodeToString заменяет битые байты на U+FFFD, строка не парсится → truncatedTail), `TrackingStateBus.kt` (StateFlow-снимок), `TrackingService.kt` (единственный android-файл пакета). `Placeholder.kt` удалён.
- Журнал новой записи — новый файл `tracks/track-<epochMillis>.track`; заголовок материализуется первым append. При ошибке записи журнала сервис останавливает запись (AD-5 невыполним — молча продолжать нельзя).
- Манифест: 5 разрешений + `<service android:name=".tracking.TrackingService" exported=false foregroundServiceType="location">`. Проводка: AppContainer += trackingStateBus; MapViewModel += observeTracking (перечитывает openedIndices только при смене fogEpoch шины); MapScreen += кнопки старт/стоп и RequestMultiplePermissions (FINE+COARSE+POST_NOTIFICATIONS), старт через ContextCompat.startForegroundService.
- Тесты (JVM, без Android): PointFilterTest (10: первая точка, пешая цепочка, превышение скорости, граница «ровно на лимите принимаем / выше — отсекаем» через сам haversine, телепортация при большом и малом dt, немонотонное время ×2, якорь неподвижен после reject, вело-лимит, reset), TrackJournalTest (9: roundtrip, точка на диске сразу после append, усечённый хвост mid-line, мусорный хвост, битый заголовок, formatVersion≠1, файл отсутствует, только заголовок, дозапись после переоткрытия). Прогон отложен (нет JDK/SDK, HITL `gradlew.bat testDebugUnitTest`) — как в 1.5/1.6.
- Вместо прогона — Python-зеркало `_qa/check-1-7-tracking.py`: 34/34 PASS (2026-10-04): формат журнала (писатель/читатель по спеке, усечение, мусор, битые заголовки, совместимость с Kotlin-строкой), фильтр против констант из TrackModels.kt (пеший темп, 80 км/ч, телепортации, немонотонное время, границы, неподвижный якорь), манифест (5 разрешений, сервис location/не exported), AD-10 grep (android.* только в TrackingService; открытие только через openTileAtPoint, FogRepository сервисом не импортируется), баланс скобок 7 файлов. Одна ложная неудача зеркала (якорь t=125 с против точки t=1 с → non_monotonic вместо teleport) — артефакт сценария зеркала, не фильтра; исправлено время точки, 34/34.
- Аудит матрицы: 10/10 строк покрыты — 6 JVM-тестами (скорость, телепортация, время, убийство процесса, битый журнал ×1 строка Invalid), happy-path записи — тестами журнала + зеркалом; «повторный старт», «стоп без записи», «нет разрешений» — кодом сервиса/UI (идемпотентные ветки), не JVM-тестируемо, HITL; «точка вне региона» — OpenResult из 1.6 (OpenTileAtPointTest), сервис трактует не-Changed штатно.
- Diff не снят: проект без VCS (baseline_revision=NO_VCS). Изменения: новые tracking (5 main + 2 test), правки AndroidManifest/AppContainer/MapViewModel/MapScreen, удалён Placeholder.kt, новый `_qa/check-1-7-tracking.py`. domain, data/fog, data/packages, tools/region-pack, tickets.toml не тронуты.

## Plan Change Log

## Review Triage Log

Quick self-review (вложенные субагенты в этом рантайме недоступны — отклонение от процедуры зафиксировано, как в 1.5/1.6; независимое ревью может прогнать человек). Вердикты: high 0 / medium 0 / low 1 / false 0 / maybe-false 0. Проверены: план ↔ код ↔ тесты ↔ зеркало; идемпотентность START/STOP; поток точки журнал→фильтр→domain; AD-3/AD-10; манифест против minSdk 29/target 36; матрица ↔ тесты (10/10).
- `FogRepository.openedIndices` читает BitSet вне монитора `@Synchronized`, мутация в persistWith (clear+or) — сервис 1.7 впервые даёт реальную конкурентность (HandlerThread записи vs IO-поток viewmodel) — **low → defer**: худший исход — моментальный снимок UI без части битов, самолечится следующей точкой/fogEpoch; код из 1.6 (не этого изменения), фикс data/fog вне интента тикета («не менять data/fog»); записано в deferred-work (пересмотреть в epic-walk-tracking при миграции журнала в Room/потоках записи).

## Design Notes

- Журнал — файл в tracking, а не Room: AD-5 требует append-only по мере поступления (файл с fsync это выполняет), а Room-история прогулок — epic-walk-tracking; формат версионируем с первой строки, миграция пути не закрыта.
- Фильтр якорится на последней ПРИНЯТОЙ точке: одиночный глитч не сдвигает якорь, следующая валидная точка сравнивается с последней честной. Отвергнутая точка всё равно пишется в журнал — журнал хранит сырую правду, фильтр решает только открытие битов (AD-4: «после фильтрации»).
- Телепортация отдельно от скорости: скачок > TELEPORT_JUMP_METERS отсекается даже при большом dt (GPS-глитч «прыгнул и вернулся» при выключенном экране); порог — производная гипотезы лимита (limit × 120 с), калибровка — полевые тесты.
- LocationManager, не FusedLocationProvider: офлайн-first (AD-2), ноль новых зависимостей, работает без GMS; колбэки на HandlerThread — дисковая запись не на main-потоке.
- Шина состояния — StateFlow-синглтон контейнера: сервис (android) пишет снимок, viewmodel собирает — сервис и UI не знают друг о друге, UDF сохранён.

## Verification

**Commands:**
- `gradlew.bat testDebugUnitTest` — expected: PointFilterTest (10) + TrackJournalTest (9) зелёные. **HITL: на машине нет JDK/SDK, не ставить; прогон за человеком.**
- `python _qa/check-1-7-tracking.py` — **выполнено 2026-10-04: 34/34 PASS** (зеркало журнала/фильтра, валидация манифеста, grep AD-10, баланс Kotlin).

**Manual checks (if no CLI):**
- grep: нет android.*/androidx.*/org.maplibre.* в TrackModels/PointFilter/TrackJournal/TrackingStateBus (AD-10) — **выполнено зеркалом, чисто**; domain и data/fog не изменены.
- **HITL на устройстве (verify тикета):** офлайн-прогулка открывает только тайлы валидных точек; точки выше лимита и телепортации тайлы не открывают; убийство процесса теряет секунды записи, не трек; постоянное уведомление висит всю запись.
