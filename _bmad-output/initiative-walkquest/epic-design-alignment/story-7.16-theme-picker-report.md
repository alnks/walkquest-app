# 7.16 · Ручной выбор темы (тёмная / светлая / системная) — отчёт

Дата: 2026-10-17. Решение владельца вне эпика design-alignment: реализовать ручной
выбор темы. До 7.16 приложение всегда следовало системной теме, а пилюля
«Тема: системная» в Настройках (7.8, группа «Внешний вид») была без действия.

## Решения

### Хранение

- Новый enum `ThemeMode { SYSTEM, LIGHT, DARK }` —
  `app/src/main/java/app/walkquest/data/settings/ThemeMode.kt`. Живёт в data-слое
  (хранилище не знает про Compose), персистится по имени enum'а — по паттерну WalkMode.
- `SettingsRepository`: поле `themeMode: String? = null` в `AppSettings`
  (formatVersion не меняли — новое поле с дефолтом, миграция не нужна), методы
  `themeMode()` (маппинг по имени; неизвестное/отсутствующее → SYSTEM) и
  `setThemeMode(mode)`. Запись атомарная (temp → rename), как остальные настройки.
- Источник состояния UI — общий `MapViewModel` (как режим/автопауза/напоминание):
  `MapUiState.themeMode` (default SYSTEM), чтение из репозитория в `init`,
  обработчик `onThemeModeSelected(mode)` — персист + `_uiState.update`.

### UI выбора (Настройки, группа «Внешний вид»)

- Строка `WqValueRow(label = "Тема", onClick = { … })` с пилюлей-значением
  `WqValuePill(text = themeModeLabel(uiState.themeMode))` — tap-area ≥48dp тот же
  контракт, что у WqValueRow.
- Тап открывает M3 `AlertDialog` (мост MaterialTheme собран из токенов WqTheme —
  диалог не скакал бы на дефолтной палитре; тот же подход, что AlertDialog в
  ProfileScreen). Содержимое — три строки-варианта `Surface(onClick)` с
  min-height 48dp: подпись (`body`, у выбранного — `accentText`) + векторная
  галочка 20dp у выбранного варианта. Подтверждение — «Готово».
- Копии — только указанные: «Тема» / «системная», «светлая», «тёмная»
  (маппинг `themeModeLabel` приватный в SettingsScreen).
- Новых hex нет: только токены WqTheme (colors/typography/spacing) + локальная
  векторная галочка (24dp-путь, как TabIcons в MainActivity).

### Применение без пересоздания активности

- `MainActivity.setContent`: `MapViewModel` создаётся на уровне активности
  (`viewModel(factory = container.mapViewModelFactory)`) и передаётся в
  `WalkQuestTabs(mapViewModel)` — тема и вкладки делят одну инстанцию.
- Корневой композабл собирает `darkTheme` при каждой рекомпозиции:
  `SYSTEM → isSystemInDarkTheme()`, `LIGHT → false`, `DARK → true`, и передаёт
  в `WalkQuestTheme(darkTheme = …)`. Палитры дублировать не пришлось — прокинут
  только флаг в существующие `WqColors.dark()/light()` (требование 4 соблюдено).
- Переключение меняет тему на лету в обе стороны; `isSystemInDarkTheme()`
  перекомпозируется при смене системной темы, поэтому режим SYSTEM следует
  системе живьём.

## Проверки

- `gradlew compileDebugKotlin` (JDK 17, Eclipse Adoptium 17.0.20) — зелёно, без предупреждений.
- `gradlew testDebugUnitTest` — зелёно; `SettingsRepositoryTest`: 34 теста
  (было 30, добавлено 4: default+roundtrip, персист через пересоздание репозитория,
  неизвестное сохранённое значение → SYSTEM, legacy-файл без поля → SYSTEM).
- `grep -rniE "#[0-9a-f]{6}|0x[0-9a-f]{6}" app/src/main/java --include="*.kt"` вне
  ui/theme: совпадения только в документационных комментариях (старые, до 7.16)
  и в `FALLBACK_WATER_COLOR` карты (MapScreen — фолбэк стиля воды MapLibre, не
  UI-хром). Новый код 7.16 hex не содержит.

## On-device (эмулятор Medium_Phone_API_37.0, WHPX, wq.py)

APK: `app-debug.apk`, lastUpdateTime 2026-10-17 16:54. Сырьё прогона:
`_qa/emulator-run/2026-10-17-theme-picker-verify/` (00–10, PNG).

Сценарий (все шаги подтверждены скриншотами):

1. `02-theme-picker-dialog.png` — Настройки → Тема → диалог с тремя вариантами,
   «системная» выбрана (галочка, accentText). **Требуемый скриншот «настройки с
   открытым выбором».**
2. `03-settings-forced-dark-system-light.png` — выбор «тёмная»: приложение стало
   тёмным **при системной светлой**, пилюля «тёмная». Переключение на лету.
3. `04-map-forced-dark-system-light.png` — главная карта в принудительно тёмной
   при системной светлой. **Требуемый скриншот.**
4. `05-settings-after-restart-still-dark.png` — force-stop + запуск: тема тёмная,
   пилюля «тёмная» — **выбор сохранён** (персист работает).
5. `06-settings-forced-light.png` — выбор «светлая» при системной светлой.
6. `07-settings-forced-light-system-dark.png` — система переведена в тёмную
   (`cmd uimode night yes`): приложение **осталось светлым** (принудительный
   режим не следует системе). Скриншот карты: панель светлая.
7. `08-settings-system-follows-dark.png` — выбор «системная» при тёмной системе:
   приложение тёмное, пилюля «системная».
8. `09-map-system-back-to-light.png` — система обратно светлая (`night no`):
   приложение светлое — **режим SYSTEM следует системной теме**.
9. `10-settings-forced-light-system-dark.png` — контрольный повтор: настройки
   светлые при системной тёмной, пилюля «светлая».

Эмулятор остановлен (`adb emu kill`).

## Переснятые склейки 11-settings

- Сырьё: `_qa/emulator-run/compare-verify/raw/11-settings-dark-raw.png`
  (настройки тёмные при системной светлой, пилюля «тёмная»),
  `11-settings-light-raw.png` (настройки светлые при системной тёмной,
  пилюля «светлая»), `11-settings-picker-dialog-raw.png` (диалог выбора).
- Склейки (id 11-settings-dark/11-settings-light уже есть в `_qa/compare-stitch.py`):
  - `_bmad-output/initiative-walkquest/ux-walkquest/compare/11-settings-dark.png`
  - `_bmad-output/initiative-walkquest/ux-walkquest/compare/11-settings-light.png`

## Отступления и замечания

1. **Несоответствие макета**: макет 11-settings (7.8) содержит строки «Отсёт цели/
   Значение цели» в группе «Цель» и «Автозавершение при разряде» — реализация 7.8
   сознательно отличается (сводка «Моя цель», автозавершение отложено владельцем
   2026-10-17). Склейки это показывают — расхождение известное, не из 7.16.
2. **Статус-бар при принудительной теме**: иконки статус-бара решает система по
   своей теме — при принудительно тёмном приложении и светлой системе иконки
   остаются тёмными (видно в `03`/`04`). Косметика на границе системы и приложения;
   лечится WindowInsetsControllerCompat по выбранному режиму — не входило в тикет.
3. **Конфиг-чендж закрывает оверлей настроек**: смена системной uiMode пересоздаёт
   активность, флаг видимости оверлея — `remember` без saveable, поэтому после
   смены системной темы из настроек оверлей закрывается (карта показывает итог).
   Не дефект 7.16 (поведение всех оверлеев), зафиксировано для сведения.
4. `wq.py install` без JAVA_HOME в окружении падает (код 9009) — прогонял с
   `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`
   (известно из walkquest-qa SKILL.md).
5. Финальный сброс состояния эмулятора (тема «системная», система светлая) тапами
   выполнен, но скриншотом не верифицирован (эмулятор остановлен сразу после) —
   на артефакты не влияет.
