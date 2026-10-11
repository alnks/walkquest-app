# Deferred Work — epic-habits

## Итоги историй — 2026-10-09

### 3.1 История завершённых прогулок — done
- tracking/WalkHistoryStore.kt (append-only walks/walks.jsonl, AD-5) + domain/WalkHistory
  (агрегаты, неделя пн–вс локального календаря). Сервис дописывает запись при
  финале сессии. Тесты: WalkHistoryStoreTest 6, WalkHistoryTest 4.

### 3.2 Цель (FR-11) — done
- domain/WalkGoal (типы WALKS_PER_WEEK/MINUTES_PER_DAY/KM_PER_DAY, валидация,
  describe со склонениями; км хранятся удвоенным Int). Settings: goalType/goalValue,
  битая пара читается как null (не краш). Тесты: WalkGoalTest 8, SettingsRepository +4.

### 3.3 Онбординг (FR-12, FR-11) — done
- ui/onboarding/OnboardingOverlay.kt: 3 экрана обучения + экран цели; «Пропустить»
  ведёт на экран цели (цель обязательна). Флаг onboardingDone в настройках;
  показ, пока нет флага ИЛИ нет цели. Тексты — черновой голос, полировка в UX-проходе.

### 3.4 Статистика и серия (FR-13) — done
- domain/StreakCalculator (серия дней, сегодня не обрывает), StatsViewModel +
  ui/stats/StatsScreen + третья вкладка MainActivity. Вкладка перечитывается при
  входе (LaunchedEffect). Тесты: StreakCalculatorTest 6.

### 3.5 Умные напоминания (FR-14) — done
- domain/GoalReminderPolicy (только при отставании; тексты по типам цели),
  reminder/GoalReminderReceiver (вечерний AlarmManager 19:00 default, тихий канал),
  настройки goalRemindersEnabled/goalReminderHour. Тесты: GoalReminderPolicyTest 5,
  SettingsRepository +3.

### 3.6 Планировщик (FR-17) — done
- data/plans/PlannedWalksStore (атомарный plans.json), domain/PlanPolicy
  (выполнен = прогулка в день плана; пропуск без наказания), reminder/PlanReminderReceiver
  (lead default 15 мин), UI: «Планы» на вкладке статистики + DatePicker/TimePicker
  диалог. Тесты: PlannedWalksStoreTest 6, PlanPolicyTest 3, SettingsRepository +3.

### 3.7 Refactor sweep — done
- Чтение настроек из композиции StatsScreen убрано (goalText в UiState, IO в reload).
- Комментарий MainActivity про две вкладки → три.

### Проверки
- Принудительный перезапуск: 336 JVM-тестов, 0 падений. assembleRelease BUILD SUCCESSFUL.

### Остатки (не блокеры закрытия кода)
- Переживание перезагрузки устройства будильниками 3.5/3.6 — к boot-ресиверу
  (приложение стартует ежедневно и переставляет; exact-аляrms не используем).
- Тексты онбординга и напоминаний — полировка в UX-проходе (EXPERIENCE).
- Числовые редакторы настроек (час, lead, лимиты 2.2, пороги 2.6, интервал 2.7) —
  к экрану настроек.
- Полевая калибровка смысла «дня» для MINUTES/KM_PER_DAY (сейчас сегодняшний день).
- Доставка уведомлений и сценарии напоминаний — эмуляторный/полевой прогон
  (отложено владельцем вместе с остальной полевой работой).
