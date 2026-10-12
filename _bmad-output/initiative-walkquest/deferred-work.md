# Отложенные работы

- source_plan: `_bmad-output/initiative-walkquest/epic-map-foundation/story-track-recording-prototype-plan.md`
  summary: Синхронизировать чтение BitSet в FogRepository.openedIndices с мутацией в persistWith (снимок вне монитора @Synchronized).
  evidence: История 1.7 впервые даёт реальную конкурентность (HandlerThread сервиса vs IO-поток viewmodel); худший исход — моментальный снимок UI без части битов, самолечится следующей точкой/fogEpoch (low, defer из ревью 1.7). Код из 1.6, фикс data/fog был вне интента тикета; пересмотреть в epic-walk-tracking при миграции журнала в Room и переработке потоков записи.

## Дизайн-система (2026-10-12): кодовая система токенов и компонентов

Что сделано:
- Трёхслойные токены в ui/theme (WqPrimitive → WqColors/WqTypography/WqShapes/WqSpacing → WqTheme),
  MaterialTheme собран из тех же токенов (мост для M3-инфраструктуры); ни одного hex и MaterialTheme
  в экранах (debug-панель карты читает цвета из WqPrimitive).
- Шрифты ресурсами res/font с фолбэком на системный гротеск: Nunito 800/1000 (кириллица проверена
  по cmap), Rubik Bubbles, Manrope 400/500. Все @Preview компонентов (светлая+тёмная) собираются.
- Компоненты ui/components строго по DESIGN.md: WqPrimaryButton/GhostButton, WqCard/WqCardActive,
  WqStatColumn, WqSheet, WqChip, WqSegmentPill, WqBottomNav, WqQuestCard, WqIconFab, WqModeToggle.
- Все экраны (Map/Progress/Quests/Stats/Achievements/Rating/Profile/Regions/Settings/Onboarding,
  MainActivity) переведены на WqTheme + компоненты; deprecated-шимы эпохи прототипа удалены.
- Качество: gradlew testDebugUnitTest зелёные на JDK 17, compileDebugKotlin без ошибок.
- Спецификация экранов: _bmad-output/initiative-walkquest/ux-walkquest/screens-spec.md.

Что НЕ проверено на устройстве (нужен прогон walkquest-qa / wq.py):
- Визуал шрифтов живьём: плотность Nunito 1000 в герой-цифрах, начертания Manrope при
  динамическом размере шрифта системы, Rubik Bubbles (в прод-экранах не используется до юбилеев).
- Светлая тема целиком: призрак CTA, сегмент-пилюли (заливка #587110 + белый текст), тогл режима.
- Микро-анимация «всадника» WqModeToggle (пружина кружка) и её влияние на перцепцию скорости.
- Контрасты dim-текстов (ink-dim) на реальных экранах Samsung (эталон S26).

Открытые вопросы для дизайна/владельца:
- DM Sans в DESIGN.md «отлично держит кириллицу» — фактически кириллицы у DM Sans нет
  (google/fonts ofl/dmsans, подтв. issues google/fonts#6193). Заменён на Manrope (OFL,
  тот же характер роли body). Если дизайн настаивает на DM Sans — нужна платная кириллическая
  редакция или другой выбор; текущая замена зафиксирована в WqTypography.kt.
- Подсветка текущего пользователя в рейтинге card-active (DESIGN leaderboard-row): ждёт флага
  isCurrentUser в RatingEntryUi (эпик 5) — сейчас строки лидерборда равнозначны.
- «Всадник» режима 2.4с с подписью (EXPERIENCE) не реализован — короткая пружина кружка;
  длинная обучающая анимация отдельной задачей. Reduce Motion не читается.
