---
title: '6.2 Импорт: restore в хранилища'
type: 'feature'
ticket: '2'
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

**Problem:** Архив из 6.1 бесполезен без применения: нужно восстановить данные на новом устройстве (FR-21) без порчи текущих при частично битом архиве.

**Approach:** `ImportApplier` (data/transfer, чистый JVM): валидирует каждую секцию архива по формату её хранилища, применяет атомарно (temp + rename, как в сторах), отчёт по секциям Ok/Skipped/Failed; перед импортом удаляет активную сессию (прогулка не переносится); перепроверка достижений и сброс кэшей пакетов — снаружи, в AppContainer (wiring — 6.3).

</frozen-after-approval>

## Code Map

- `app/src/main/java/app/walkquest/data/transfer/ExportArchiveReader.kt` -- Archive(manifest, sections: Map<String, ByteArray>, warnings) из 6.1; ExportSections с именами секций.
- `app/src/main/java/app/walkquest/data/transfer/ExportSources.kt` -- корни источников; ImportTargets зеркалит их + sessionFile.
- `app/src/main/java/app/walkquest/data/fog/FogRepository.kt:155-178` -- формат `.fog`: первая строка BitmapHeader (JSON), затем '\n', затем бинарный payload; несогласованность на чтении → bitmap пересоздаётся.
- `app/src/main/java/app/walkquest/tracking/TrackJournal.kt:117-134` -- `.track`: первая строка JournalHeader, далее PointLine JSONL.
- `app/src/main/java/app/walkquest/tracking/WalkHistoryStore.kt:98-109` -- StoredRecord: 6 обязательных полей записи истории.
- `app/src/main/java/app/walkquest/di/AppContainer.kt:117` -- session/session.json (WalkSessionStore); удаляется перед импортом.

## Tasks & Acceptance

**Execution:**
- [ ] `app/src/main/java/app/walkquest/data/transfer/ImportTargets.kt` -- data-class ImportTargets: те же File-поля, что ExportSources, + sessionFile (File?); Android не импортируется.
- [ ] `app/src/main/java/app/walkquest/data/transfer/ImportApplier.kt` -- apply(archive, targets): ImportReport(sections: Map<String, SectionStatus>, sessionDeleted: Boolean); SectionStatus = Ok / Skipped(reason) / Failed(reason). Валидация до записи: settings/earned/quests/plans/poicards — parseToJsonElement → JsonObject; walks — каждая непустая строка парсится в StoredRecord-подобный объект с 6 обязательными полями; fog/<name> — имя безопасно (не начинается с '.', нет '/' '\\' '..', оканчивается на .fog) и первая строка — JSON-объект; tracks/<name> — имя начинается с 'track-' и оканчивается на '.track', первая строка — JSON-объект, остальные непустые строки — JSON. Запись: temp-файл рядом с целью → rename (поверх существующего: delete+rename, как FogRepository). Порядок секций детерминирован (sorted). sessionFile удаляется в конце при любом исходе секций.
- [ ] `app/src/test/kotlin/app/walkquest/data/transfer/ImportApplierTest.kt` -- JVM TemporaryFolder: (1) round-trip 6.1→6.2 в пустые хранилища → все файлы побайтово равны источнику, session удалён, отчёт все Ok; (2) walks.jsonl с мусорной строкой → walks Skipped, остальные Ok, существовавший walks-файл цел; (3) секция fog/../evil.fog → Skipped, файла за пределами fogRoot нет; (4) fog с не-JSON заголовком → Skipped; (5) earned восстановлен: EarnedStore читает те же id, что в источнике.

**Acceptance Criteria:**
- Given валидный архив из 6.1 и пустые цели, when apply, then содержимое всех хранилищ побайтово совпадает с источником, сессия удалена, все секции Ok.
- Given секция с невалидным содержимым, when apply, then только она Skipped, её прежнее содержимое нетронуто, остальные секции Ok — отчёт честен по каждой.
- Given опасное имя секции (path traversal), when apply, then секция Skipped, никакой файл за пределами целевых корней не создан.

## Implementation Notes

Route oneshot: субагенты недоступны (ограничение харнесса). Перепроверка достижений (runAchievementCheck) и сброс кэша пакетов сознательно вынесены в wiring 6.3 — JVM-апплайер о них не знает.

2026-10-11: реализовано ImportTargets + ImportApplier (валидатор по форматам хранилищ + атомарная запись temp→rename + отчёт Ok/Skipped/Failed + удаление сессии) и 5 тестов. Решения в ходе работы:
- Константы имени файла настроек и конвенции track-*.track взяты из SettingsRepository.FILE_NAME и TrackHistory (internal с 6.1), без дублирования литералов.
- Валидация записи истории принимает целые и дробные числа (distanceMeters — Double; первая версия требовала Long и корректно падала на валидных данных — поймано тестом round-trip).
- Невалидная секция отклоняется целиком до записи: текущие данные секции переживают ошибку её парсинга.

## Review Triage Log

2026-10-11, быстрая линза: субагенты недоступны — строгий инлайн-ревью (промпт линзы: story-restore-plan.quick-review-prompt.md). Находок, требующих правки, нет. Подтверждено: temp-импорта (`.import-*.tmp`) начинаются с точки и фильтром экспорта 6.1 не выбираются; порядок применения секций детерминирован (sorted); защита от path traversal покрыта тестом; константы не дублируются.

## Verification

**Commands:**
- `gradlew.bat :app:testDebugUnitTest --tests "app.walkquest.data.transfer.*"` -- expected: все тесты зелёные. ФАКТ 2026-10-11: 14/14 зелёных (5 новых импорта + 9 экспорта).
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` -- expected: полный набор зелёный, сборка PASS. ФАКТ 2026-10-11: PASS.

## Review Pass 2 — 2026-10-11 (сводное ревью 6.1–6.3)

Находки и вердикты:
- low → defer: writeAtomic при двойном IO-сбое (rename не удался и повторный rename тоже) может потерять целевой файл — осознанный parity с FogRepository.persistWith (тот же accepted-паттерн сторов); отдельного риска не добавляет.
- low → defer: удаление session.json при импорте может вернуть false (отчёт честен, секции применены) — stale-сессия восстановится при следующем старте; редкий IO-сбой, наблюдаемо в отчёте.
- Подтверждено: валидация до записи, защита от path traversal, константы из сторов.

Verification: 389 JVM-тестов зелёных, assembleDebug PASS (2026-10-11).
