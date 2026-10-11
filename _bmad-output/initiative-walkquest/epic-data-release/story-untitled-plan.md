---
title: '6.3 Вкладка «Профиль»: перенос прогресса, предупреждение о потере, «О приложении»'
type: 'feature'
ticket: '3'
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
  - '_bmad-output/initiative-walkquest/ux-walkquest/mockups/remaining-screens.html'
  - '_bmad-output/initiative-walkquest/prd-walkquest/prd-walkquest.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Экспорт/импорт (6.1/6.2) не достижимы пользователем: нет UI переноса прогресса, нет предупреждения о безвозвратной потере при удалении (Done when 2), нет «О приложении» с версией и политикой.

**Approach:** Шестая вкладка «Профиль» по макету remaining-screens: карточка «Перенос прогресса» (экспорт/импорт через SAF, подтверждающий диалог с датой копии, отчёт по секциям), постоянная карточка-предупреждение о потере, блок «Недостающие регионы» (докачка пакетов после импорта), «О приложении» (версия из BuildConfig, ссылка на политику из ресурсов). ViewModel принимает потоки (ввод/вывод) — SAF и ContentResolver живут в Compose-слое; JVM-тестируемость сохранена.

</frozen-after-approval>

## Code Map

- `app/src/main/java/app/walkquest/MainActivity.kt` -- пять вкладок в enum AppTab + WalkQuestTabs; иконки рисуются pathIcon-путями. Добавить шестую.
- `app/src/main/java/app/walkquest/ui/regions/RegionsScreen.kt` -- образец экрана: Column + statusBarsPadding + заголовок titleLarge ExtraBold + Surface(RoundedCornerShape(14.dp)) карточки; токены темы (HeadlineColor, AccentTextColor, DimTextColor, InkSecondaryColor).
- `app/src/main/java/app/walkquest/di/AppContainer.kt` -- фабрики viewmodel; wiring: exportSources/importTargets лямбды из тех же корней + onImportApplied = runAchievementCheck() (FR-15 задним числом).
- `app/src/main/java/app/walkquest/data/transfer/*` -- writer/reader/applier из 6.1/6.2 (потоки на входе — SAF-нейтральные).
- `app/src/main/java/app/walkquest/data/packages/RegionPackageRepository.kt:74` -- installedPackages() читает диск живьём → недостающие регионы считаются честно после импорта.
- `app/src/main/java/app/walkquest/domain/InstallRegionPackage.kt` -- suspend invoke(regionId).
- `app/src/main/java/app/walkquest/data/settings/SettingsRepository.kt:204-221` -- leaderboardNickname()/leaderboardVisible()/walkGoal().
- `app/build.gradle.kts:18-19` -- versionCode 1, versionName 0.1.0 (до 6.4); BuildConfig включён.

## Tasks & Acceptance

**Execution:**
- [ ] `app/src/main/java/app/walkquest/viewmodel/ProfileViewModel.kt` -- ProfileUiState(nickname, leaderboardVisible, goalText, appVersion, privacyUrl, missingRegions, exportBusy, importState, message); ImportState = Idle / Preview(exportedAtEpochMillis) / Done(summary). suspend export(out: OutputStream), suspend readImportPreview(input): открывает архив (ExportArchiveReader), держит секции в памяти до confirmImport(); confirmImport() применяет через ImportApplier к targets, считает missingRegions = manifest.installedRegionIds − installedPackages(), зовёт onImportApplied(), формирует summary через formatImportReport; cancelImport(). Форматирование — internal fun для тестов. Coroutines: withContext(ioDispatcher).
- [ ] `app/src/main/java/app/walkquest/ui/profile/ProfileScreen.kt` -- по макету: шапка (ник + «в рейтинге/скрыт», цель), карточка «Перенос прогресса» с кнопками Экспорт/Импорт (rememberLauncherForActivityResult: CreateDocument("application/zip") с именем walkquest-export-<дата>.zip, OpenDocument), диалог подтверждения «Текущие данные будут заменены копией от <дата>?», текст отчёта после импорта + подсказка «перезапустите приложение для обновления карты» (in-memory биткарты MapViewModel не инвалидируются — v1), постоянная карточка-предупреждение о безвозвратной потере (Done when 2), «Недостающие регионы» с кнопкой «Скачать» (installRegionPackage по очереди), «О приложении»: версия + строка «Политика конфиденциальности» → Intent ACTION_VIEW.
- [ ] `app/src/main/java/app/walkquest/MainActivity.kt` -- AppTab.Profile + иконка (силуэт человека, pathIcon) + viewModel через container.profileViewModelFactory + reload на входе.
- [ ] `app/src/main/java/app/walkquest/di/AppContainer.kt` -- profileViewModelFactory: deps settingsRepository, regionPackageRepository, installRegionPackage, exportSources/importTargets лямбды, appVersion = BuildConfig.VERSION_NAME, onImportApplied = { runAchievementCheck() }.
- [ ] `app/src/main/res/values/strings.xml` -- privacy_policy_url (заглушка https://walkquest.app/privacy — до 6.5).
- [ ] `app/src/test/kotlin/app/walkquest/viewmodel/ProfileViewModelTest.kt` -- JVM: round-trip export→preview→confirm через потоки в temp-директориях (источник с данными → пустые цели: файлы совпадают, missingRegions = spb без пакета, summary содержит Ok по секциям); formatImportReport честен по Skipped; повреждённый архив → message об ошибке, состояние Idle.

**Acceptance Criteria:**
- Given заполненный источник, when export → readImportPreview → confirmImport в пустые цели, then файлы хранилищ совпадают, missingRegions содержит regionId без пакета, onImportApplied вызван.
- Given битый архив, when readImportPreview, then message об ошибке, диалог не показывается, данные не тронуты.
- Given импорт завершён, when экран Профиля, then видны отчёт, предупреждение о потере, версия и ссылка на политику.

## Implementation Notes

Route oneshot: субагенты недоступны. Decision (v1): после импорта карта обновляется перезапуском — инвалидировать in-memory FogBitmap MapViewModel из Профиля нельзя без переработки владения состоянием; честная подсказка в отчёте. Decision: диалог подтверждения импорта строится на дате из манифеста копии.

2026-10-11: реализовано ProfileViewModel (потоки на входе — SAF в Compose), ProfileScreen (экран по макету, диалог подтверждения, launchers SAF), вкладка в MainActivity + иконка-силуэт, wiring в AppContainer (buildExportSources() собирается на каждый экспорт — свежий список регионов; onImportApplied = runAchievementCheck, FR-15 задним числом), PRIVACY_POLICY_URL-заглушка в companion. 7 JVM-тестов.

## Review Triage Log

2026-10-11, быстрая линза: субагенты недоступны — строгий инлайн-ревью (промпт: story-untitled-plan.quick-review-prompt.md). Находки:
- verdict high → patch: race потока SAF — export()/readImportPreview() были асинхронными (viewModelScope.launch), а экран закрывал поток сразу после возврата: на устройстве поток закрылся бы до записи. Исправлено: обе функции suspend, экран зовёт их в rememberCoroutineScope + use.
- verdict medium → patch: собственное форматирование цели дублировало domain — заменено на WalkGoal.describe() (AD-4: готовые строки из domain).
- verdict medium → patch: filesDir недоступен в member-функциях — конструкторный параметр AppContainer стал private val.
- Подтверждено инлайн: иконка вкладки рисуется pathIcon по конвенции проекта; PackageInstallResult обрабатывается по реальным вариантам (Success/AlreadyInstalled/Failure); профиль перечитывается при входе на вкладку.

## Verification

**Commands:**
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` -- expected: все тесты зелёные, сборка PASS. ФАКТ 2026-10-11: 388 JVM-тестов зелёных, assembleDebug PASS.

## Review Pass 2 — 2026-10-11 (сводное ревью 6.1–6.3)

Находки и вердикты:
- medium → patch: ProfileViewModel.reload() читал настройки с диска синхронно на главном потоке (init + вход на вкладку) против конвенции StatsViewModel — обёрнуто в viewModelScope.launch + withContext(ioDispatcher).
- low → patch: после импорта шапка Профиля не обновлялась до повторного входа на вкладку — reload() вызывается из confirmImport().
- low → defer: openOutputStream(uri) == null при экспорте молчит (системный выбор уже подтверждён; отказ ContentResolver редок) — при повторении вынести в явное сообщение.
- Подтверждено: suspend-API потоков закрывает race SAF; диалог с датой копии; disabled-состояния кнопок.

Verification: 389 JVM-тестов зелёных, assembleDebug PASS (2026-10-11, после патчей).
