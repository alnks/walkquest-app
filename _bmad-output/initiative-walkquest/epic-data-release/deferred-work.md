# Deferred Work — epic-data-release

## Итоги — 2026-10-11 (кодовая часть завершена, 6.1–6.4, 6.6)

- 6.1 Формат архива экспорта: пакет `data/transfer` — ExportManifest
  (formatVersion=1, exportedAt, appVersion, installedRegionIds), zip-writer/reader
  (CRC zip, typed-ошибки CorruptArchive/UnsupportedFormat, лимит секции 256 МБ,
  неизвестные секции — предупреждения). Пакеты регионов и leaderboard/outbox в
  архив не входят (Decision инсепшна).
- 6.2 Импорт: ImportApplier — валидация секций по форматам хранилищ до записи,
  атомарная запись temp→rename, отчёт Ok/Skipped/Failed, удаление активной
  сессии; защита от path traversal; перепроверка достижений — wiring (6.3).
- 6.3 Вкладка «Профиль»: ProfileViewModel (потоки на входе — SAF в Compose,
  suspend-API), ProfileScreen по макету remaining-screens (экспорт/импорт,
  диалог с датой копии, предупреждение о потере — Done when 2, недостающие
  регионы с докачкой, «О приложении»), шестая вкладка + pathIcon-силуэт,
  onImportApplied = runAchievementCheck (FR-15 задним числом).
- 6.4 Релиз: allowBackup=false (AD-3), versionName 1.0, signingConfigs.release
  из local.properties с fallback на debug, docs/release-signing.md.
  R8/shrink/abi (AD-8) уже были включены — регресс подтверждён.

### Проверки
- 389 JVM-тестов зелёных (после сводного ревью; было 388), assembleDebug PASS,
  assembleRelease PASS (~2,5 мин). aapt badging: versionName='1.0'.
  apksigner verify: подпись Android Debug (fallback — keystore владельца не создан).
- Аудит логов: координат в Log.* нет (TrackingService — provider/accuracy/age,
  MapScreen — метрики геометрии), AD-3 соблюдён.

### Остатки
- 6.5 (hitl, владелец): публикация — чек-лист см. в Notes эпика и
  docs/release-signing.md; генерация keystore, политика конфиденциальности,
  декларация background location, возрастной опросник (OQ-6), листинг, AAB.
- Quick-review линзы 6.1/6.2/6.3 не прогнаны — субагенты недоступны в сессии
  сборки; промпты рядом с планами (story-*-plan.quick-review-prompt.md) —
  прогнать в отдельных сессиях, находки — сюда.
  UPDATE 2026-10-11: сводное инлайн-ревью 6.1–6.3 выполнено в этой сессии;
  патчи: IO-поток в ProfileViewModel.reload, reload после импорта,
  предупреждение о дублях секций в reader (+ тест на сыром zip). Defer:
  молчаливая ошибка openOutputStream при экспорте; отказ delete session.json
  при импорте (честен в отчёте); writeAtomic parity с FogRepository.
- Инвалидация in-memory FogBitmap MapViewModel после импорта: сейчас честная
  подсказка «перезапустите приложение» (v1); полноценная инвалидация —
  переработка владения состоянием карты, отдельный тикет при необходимости.
- Экспортный архив читается в память целиком (лимит 256 МБ/секция): потоковая
  обработка — если копии вырастут за пределы десятков МБ.

### Полевой смоук — 2026-10-11 (SM-S942B, release arm64, подпись debug-fallback)

Установка `adb install -r` на живой телефон, полный сценарий переноса:
запуск → онбординг (пропуск → цель «3 прогулки в неделю») → карта с туманом
(пакет spb-city на месте, WalkQuestFog-лог живой, без крашей) → Профиль
(цель из онбординга в шапке, предупреждение о потере, версия 1.0) → экспорт
в Скачанные (SAF CreateDocument, имя walkquest-export-2026-10-11.zip) → копия
на диске: manifest formatVersion=1/appVersion 1.0, секции settings+fog+4 трека,
CRC OK → импорт той же копии (SAF OpenDocument → диалог «заменены копией от
2026-10-11, 15:59» → отчёт «все секции восстановлены», подсказка о перезапуске).
Скриншоты и копии: `_qa/emulator-run/2026-10-11-release-smoke-verify/`.
Трассер эпика (export → wipe → import) подтверждён на устройстве; wipe-часть
симулирована импортом поверх себя же, чистое устройство — до первой реальной
миграции владельца (Done when 1, hitl).

Дефект, найденный прогоном: installedPackages() возвращает запись на каждую
версионную директорию v* — после обновления пакета regionId дублировался в
манифесте (["spb-city","spb-city"]). Патч: distinct() в buildExportSources
(AppContainer), подтверждено повторным экспортом на устройстве (["spb-city"]).
Репозиторное поведение (запись на версию, а не на регион) — наблюдение для
будущих эпиков: потребителей ~15, дедупликация у каждого своя; при следующем
касании RegionPackageRepository рассмотреть distinctBy(regionId) на источнике.

Косметика (записать в UX-проход): шесть вкладок на 1080px переносят подписи
в две строки («Статистик/ика», «Профил/ь») — читаемо, но стоит рассмотреть
короткие подписи или шире spacing.
- Slug-коллизия tickets.py: планы 6.3 и 6.4 оба «untitled» (кириллица) — план
  6.4 лежит в story-release-plan.md, статус читать там.
