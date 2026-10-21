# 7.15 Шаг 0: аудит дизайн-системы — отчёт

Дата: 2026-10-12 (по контексту сессии; время выполнения — текущая сессия эпика design-alignment).
Источник истины: `_bmad-output/initiative-walkquest/ux-walkquest/DESIGN.md` (прочитан целиком).
Референс-интеллект: навыки `ui-ux-pro-max:ui-ux-pro-max` и `ui-ux-pro-max:design-system`
(трёхслойная архитектура primitive → semantic → component; правило «никакого сырого hex
в компонентах» — соответствует архитектуре проекта).

## Что проверено

1. **Палитра** — каждый токен `WqPrimitive` светлой и тёмной темы сверен посимвольно с YAML-шапкой
   DESIGN.md (строки 8–38) и разделом Colors: все 18 светлых и 15 тёмных токенов из спеки
   присутствуют в обеих фабриках (`WqColors.light()` / `WqColors.dark()`), значения совпадают
   полностью. Расхождений нет.
2. **Типографика** — `WqTypography` против блока typography DESIGN.md: Nunito 800 (heading,
   16–24px: 22/18/16), Nunito 1000 (display, 38–58px: 58/48/38), meta 500 12px (минимальный
   кегль соблюдён везде, включая M3-мост labelSmall 12sp вместо дефолтных 11sp),
   metaLarge 13/18 (сегмент-контролы ≥13px), celebration Rubik Bubbles. Расхождений нет.
   Шрифтовые файлы `res/font/*.ttf` (5 файлов) на месте и совпадают с объявлениями `WqFonts`.
3. **Иконки** — все точки использования `WalkQuestIcons.*` и inline-векторов проверены:
   tint во всех случаях идёт из токенов `WqTheme.colors` (WqBottomNav — через
   `NavigationBarItemDefaults.colors`, WqModeToggle/WqChip/WqIconFab/MapScreen — явным tint).
   Захардкоженных tint нет.
4. **Shapes/Spacing** — `WqShapes` (8/12/16/20/50%) и `WqSpacing` (4/8/12/16/24/32) совпадают
   с блоками rounded/spacing DESIGN.md.
5. **Grep на захардкоженные цвета** — полный прогон `Color\(0x|0xFF[0-9A-Fa-f]{6}` по `app/src`:
   до правок 2 вхождения вне `ui/theme` (оба в `WqSheet.kt`, @Preview backgroundColor),
   плюс 2 не входящих в паттерн, но семантически захардкоженных `Color.White.toArgb()`
   в `MapScreen.kt` (ядра маркеров POI и «я»).

## Сделанные правки

| Файл | Было | Стало | Причина |
|---|---|---|---|
| `ui/components/WqSheet.kt` (47–48) | `@Preview(..., backgroundColor = 0xFFB29ED1)` и `0xFF514568` | Превью без `showBackground/backgroundColor`, подложка — фон темы; комментарий почему | `@Preview.backgroundColor` требует compile-time константу и физически не может взять токен; hex вне `ui/theme` запрещён границей шага |
| `ui/map/MapScreen.kt` (1327) | — | `val whiteArgb = WqTheme.colors.white.toArgb()` + `currentWhiteArgb` через `rememberUpdatedState` | Токенизация белого ядра маркеров |
| `ui/map/MapScreen.kt` (1945) | `PropertyFactory.circleStrokeColor(Color.White.toArgb())` (обводка POI-маяка) | `circleStrokeColor(whiteArgb)`, параметр `whiteArgb: Int` у `updatePoiLayer` | Белое ядро/обводка — утверждённый элемент DESIGN («белое ядро», маркер «я»); цвет должен идти из токена `WqColors.white` |
| `ui/map/MapScreen.kt` (2023) | `PropertyFactory.circleColor(Color.White.toArgb())` (ядро маркера «я») | `circleColor(whiteArgb)`, параметр `whiteArgb: Int` у `updateMeLayer` | То же; оба вызова (setStyle-колбэк и LaunchedEffect) передают токен |

Поведение экранов, тексты, навигация, viewmodel/data/tracking не тронуты: сигнатуры изменены
только у двух private-функций MapScreen и их четырёх внутренних вызовов, значения цветов
идентичны (white обеих тем = `Color.White`).

## Принято без правок (зафиксировано)

- `fill = SolidColor(Color.Black)` в `WalkQuestIcons.kt`, `MainActivity.kt` (инлайн-объект
  вкладок), `MapScreen.kt` (`MyLocationIcon`) — канонический дефолтный залив векторного пути
  в стиле material-icons; на всех точках использования перекрывается tint из токенов.
  В паттерн обязательного grep не входит, в отчёт занесено для полноты.
- `res/values/themes.xml` (`#F6F3FA` = surface-base, фон окна до загрузки Compose) и
  `res/drawable/ic_launcher.xml` (бренд-маяк: fog/surface-base-dark/accent-dark) — статичные
  Android-ресурсы, токенов Compose не существует на момент их рендера; hex соответствуют спеке.
- M3-дефолты для `error` и debug-панели (1.14) — в DESIGN.md не заданы, задокументированы
  в док-блоке WqColor.kt.

## Открытые вопросы (к дизайну, не блокируют шаг)

1. **`cursor` светлой темы** — в DESIGN.md есть только `cursor-dark: '#9379E0'`; светлое
   значение `#7A5FC0` взято из прототипа (зафиксировано в WqColor.kt). Нужно либо добавить
   `cursor-light` в DESIGN.md, либо подтвердить прототипное значение.
2. **Карта: `fogCovered`-dark `#514568`, `fogGlow` `#FBF7EF`/`#6B5A88`** — не названы в YAML-шапке,
   утверждены разбором владельца 2026-10-05/06 («свеча v3»). Стоит довнести в DESIGN.md как
   формальные токены.
3. **`bodySmall` 14/20** — DESIGN.md задаёт body только 16px/1.5; роль 14px широко используется
   и не противоречит правилам (минимум 12px), но формально в спеке отсутствует. Кандидат
   на явную фиксацию в DESIGN.md.
4. **`accentText` в тёмной теме = accent-dark `#C6E26B`** — YAML определяет accent-text только
   для светлой (#587110); поведение кода согласовано с DESIGN Colors, но тёмное значение
   не зафиксировано явно.

## Результаты verify

- `grep Color\(0x|0xFF[0-9A-Fa-f]{6}` по `app/src` → **только `app/src/main/java/app/walkquest/ui/theme/WqColor.kt`** ✅
- `grep Color\.White|Color\.Black` по `app/src/main/java` → только дефолтные заливы векторных
  путей (см. «принято без правок») и токен `white` внутри темы ✅
- `./gradlew compileDebugKotlin` (JDK 17: Eclipse Adoptium 17.0.20, ANDROID_HOME задан) → **BUILD SUCCESSFUL** ✅
- `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL** ✅
- @Preview в теме/типографике/компонентах компилируются (следует из зелёного compileDebugKotlin) ✅
