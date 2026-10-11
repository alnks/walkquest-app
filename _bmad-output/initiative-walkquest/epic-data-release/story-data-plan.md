---
title: '6.1 Формат архива экспорта (data)'
type: 'feature'
ticket: '1'
created: '2026-10-11'
status: 'built'
baseline_revision: 'NO_VCS'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: []
review_loop_iteration: 0
context:
  - '_bmad-output/initiative-walkquest/architecture-walkquest/architecture-walkquest.md'
  - '_bmad-output/initiative-walkquest/prd-walkquest/prd-walkquest.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Прогресс пользователя (туман, история, достижения, задания, планы, настройки, треки) живёт только локально в filesDir; перенос на новое устройство невозможен (FR-21), а удаление приложения = безвозвратная потеря.

**Approach:** Новый пакет `data/transfer` с чистым JVM-кодом: версионируемый zip-архив (java.util.zip) с манифестом `formatVersion=1` и секциями-файлами данных. Writer собирает архив из инжектированных корней хранилищ; Reader парсит манифест, валидирует версию и целостность (CRC zip) и отдаёт секции лениво. Пакеты регионов и leaderboard/outbox в архив не входят (Decision инсепшна 2026-10-11).

</frozen-after-approval>

## Code Map

- `app/src/main/java/app/walkquest/tracking/WalkHistoryStore.kt` -- образец конвенции сторов: kotlinx.serialization, `Json { ignoreUnknownKeys = true }`, append + fsync, чистый JVM (Android не импортируется), файл инжектируется.
- `app/src/main/java/app/walkquest/di/AppContainer.kt` -- корни хранилищ: settingsRoot, fogRoot (`fog/<regionId>.fog` бинарные), walksFile (`walks/walks.jsonl`), earnedFile (`achievements/earned.json`), questsFile (`quests/quests.json`), plansFile (`plans/plans.json`), poiCardsFile (`poicards.json`), tracksRoot. Их же получит ExportArchiveWriter.
- `app/src/main/java/app/walkquest/data/fog/FogRepository.kt` -- `.fog` = бинарные BitSet-пayload: копируются побайтово, не парсятся.
- `app/src/main/java/app/walkquest/tracking/TrackHistory.kt` -- tracksRoot содержит журналы с префиксом/расширением: экспортируется всей директорией (walk `tracks/<name>`).
- `app/build.gradle.kts` -- kotlinx-serialization-json уже есть; новых зависимостей не нужно.
- `app/src/test/kotlin/app/walkquest/...` -- JUnit4 (libs.junit), тесты рядом с пакетом класса.

## Tasks & Acceptance

**Execution:**
- [ ] `app/src/main/java/app/walkquest/data/transfer/ExportManifest.kt` -- @Serializable ExportManifest (formatVersion: Int = 1, exportedAtEpochMillis: Long, appVersion: String, installedRegionIds: List<String>) + константа EXPORT_FORMAT_VERSION.
- [ ] `app/src/main/java/app/walkquest/data/transfer/ExportSources.kt` -- data-class ExportSources с File-полями корней (settingsRoot, fogRoot, walksFile, earnedFile, questsFile, plansFile, poiCardsFile, tracksRoot) + installedRegionIds/appVersion; Android не импортируется.
- [ ] `app/src/main/java/app/walkquest/data/transfer/ExportArchiveWriter.kt` -- write(out: OutputStream, sources): ZipOutputStream (UTF-8 entry-имена): manifest.json первым; секции -- settings/settings.json, fog/<regionId>.fog (все файлы fogRoot), walks/walks.jsonl, achievements/earned.json, quests/quests.json, plans/plans.json, poicards.json, tracks/<имя> (все файлы tracksRoot); отсутствующие файлы/пустые корни пропускаются; CRC -- штатный механизм zip.
- [ ] `app/src/main/java/app/walkquest/data/transfer/ExportArchiveReader.kt` -- read(input): typed-ошибки CorruptArchive (битый/не-zip), UnsupportedFormat(version) (formatVersion больше известной или отсутствует); результат: manifest + Map<String, ByteArray> секций (малые по объёму, кроме fog/tracks -- читаем через ZipInputStream без распаковки всего в память? нет: v1 читает побайтово в память, но большие секции (>32 МБ) отдаём через temp? -- Decision: v1 читает секции в память целиком, лимит 256 МБ на секцию, превышение -> CorruptArchive("too large"); неизвестные entry-имена игнорируются с записью в warnings).
- [ ] `app/src/main/java/app/walkquest/data/transfer/ExportResult.kt` -- sealed-типы ошибок (CorruptArchive, UnsupportedFormat) + ReadReport(warnings).
- [ ] `app/src/test/kotlin/app/walkquest/data/transfer/ExportArchiveTest.kt` -- JUnit4 на временной директории (createTempDirectory): round-trip всех секций побайтово (включая бинарную .fog с произвольными байтами), пустые источники -> валидный архив с одним manifest, повреждённый zip (обрезанный) -> CorruptArchive, formatVersion=999 -> UnsupportedFormat, неизвестная секция в архиве -> warnings, не пропускается молча в манифесте.

**Acceptance Criteria:**
- Given заполненные источники, when write -> read, then все секции побайтово идентичны, манифест совпадает (formatVersion=1, exportedAt/appVersion/installedRegionIds).
- Given обрезанный/не-zip ввод, when read, then CorruptArchive, ни одна секция не применена.
- Given архив с formatVersion новее текущего, when read, then UnsupportedFormat с номером версии.
- Given отсутствующие файлы источников, when write, then архив валиден, секции отсутствуют, manifest на месте.

## Implementation Notes

Route oneshot: субагенты недоступны в этом окружении (ограничение харнесса), реализация -- напрямую. Оценка объёма ~350-400 строк с тестами, но путь full невыполним без субагентов.

2026-10-11: реализовано 5 файлов пакета `data/transfer` + 9 тестов. Решения в ходе работы:
- Секции читаются в память целиком (v1), лимит 256 МБ/секция (из плана).
- Тест порчи CRC детерминирован: STORED-запись с ручным CRC32, порча payload-байта гарантированно ловится ZipInputStream.
- Ревью-находка (см. Review Triage Log): в экспорт попадали бы temp-останки атомарных записей (`.fog-*.tmp`) и посторонние файлы -- добавлены фильтры по конвенциям читателей (`*.fog` без точки в начале; `track-*.track`), константы TrackHistory открыты internal, имя settings.json взято из SettingsRepository.FILE_NAME.
- Сборка требует env: JAVA_HOME=Eclipse Adoptium jdk-17.0.20.101-hotspot (JBR Android Studio = Java 25 не парсится Kotlin-компилятором Gradle 8.14.5), ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk.

## Review Triage Log

2026-10-11, быстрая линза: субагенты недоступны в этом харнессе — проведён строгий инлайн-ревью вместо запуска lens-субагента (промпт линзы сохранён рядом: story-data-plan.quick-review-prompt.md; владелец может прогнать его в отдельной сессии). Находки:
- verdict medium → patch: `putDirectory` экспортировал бы все файлы fogRoot/tracksRoot, включая temp-файлы атомарных записей FogRepository (`.fog-<region>-<nano>.tmp`) и посторонние файлы. Доказательство: FogRepository.kt:196. Исправлено фильтрами + тест `temp leftovers and foreign files in roots are not exported`.
- verdict low → patch: литерал "settings.json" дублировал знание SettingsRepository — заменён на SettingsRepository.FILE_NAME.
- Подтверждено инлайн-проверкой: CRC битых архивов ловится ZipInputStream на чтении entry; несовместимость форматов закрыта UnsupportedFormat; AC плана все покрыты тестами.

## Verification

**Commands:**
- `gradlew.bat :app:testDebugUnitTest --tests "app.walkquest.data.transfer.*"` -- expected: все тесты зелёные. ФАКТ 2026-10-11: 9/9 зелёных.
- `gradlew.bat :app:assembleDebug` -- expected: сборка PASS. ФАКТ 2026-10-11: PASS, полный набор JVM-тестов зелёный.

**Manual checks (if no CLI):**
- Архив, записанный writer'ом, открывается системным unzip и содержит manifest.json + ожидаемые секции.

## Review Pass 2 — 2026-10-11 (сводное ревью 6.1–6.3, выполнено владельцем-сессией)

Находки и вердикты:
- false (6.1): «reader читает весь архив в память» — осознанное решение v1 с лимитом 256 МБ/секция, задокументировано; отклонено как дефект.
- low → defer: дублирующиеся имена секций в «чужом» архиве применялись молча last-wins — читатель теперь пишет предупреждение `duplicate section` (patch, + тест на сыром zip, т.к. ZipOutputStream дубликаты не пишет).
- Подтверждено: CRC ловит порчу; UnsupportedFormat закрыт; temp-файлы экспорта не захватываются.

Verification: 389 JVM-тестов зелёных, assembleDebug PASS (2026-10-11, после патчей).
