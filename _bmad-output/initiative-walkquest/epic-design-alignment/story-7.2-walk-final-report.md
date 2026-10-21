# Story 7.2 — Финал прогулки по макету (pixel-perfect сверка)

Дата: 2026-10-13. Статус: сделано, on-device сверка пройдена.

## Что сделано

Экран «Финал прогулки» (`WalkSummaryOverlay` в `app/src/main/java/app/walkquest/ui/map/MapScreen.kt`)
приведён к утверждённому макету «Финал» из `mockups/map-duo.html`:

1. **Hero-процент** — `displaySmall` (Nunito 1000, 38sp) цветом `accentText`, подпись
   «территории открыто» (`body`, `inkSecondary`). Блок скрывается, если прогресс
   не прочитался (как было, по spec §3).
2. **Сетка из 3 карточек** (км / время / серия) — `WqCard` (`surfaceRaised` +
   `borderHairline`, `shapes.medium`, padding `md`), значение `headingCard`
   (`inkHeadline`), подпись `meta` (`inkDim`), зазор `sm`. Значения — из сводки
   viewmodel: `distanceMeters`, `activeMillis`, `streakDays`.
3. **Карточка нового достижения** — `WqCardActive` (`accentSoft`/`accentSoftBorder` —
   ровно заливка/рамка акцента макета `.ach`), иконка-кубок 40dp в подложке
   `surfaceRaised` с рамкой `accentSoftBorder`, tint `accentText`
   (новый вектор `WalkQuestIcons.Trophy`; эмодзи макета — плейсхолдер по DESIGN),
   заголовок «Новое достижение: «…»» (`headingCard`). Карточек может быть
   несколько (по числу новых достижений).
4. **Кнопки** — `WqPrimaryButton` «Поделиться результатом» (копия из spec §3 п.7
   и макета; раньше было «Поделиться») и текст-кнопка «На карту» (`inkSecondary`)
   вместо прежнего «Закрыть» — закрывает оверлей и оставляет на карте.
5. **Полноэкранно, без прокрутки**; нижняя навигация под оверлеем скрыта —
   условие в `MainActivity.kt`: `WqBottomNav` не рисуется, пока
   `mapUiState.walkSummary != null` (аналогично онбордингу).
6. Все цвета/стили/отступы — только токены `WqTheme`; hex вне `ui/theme` не появился.

### Минимальный диф вне UI (viewmodel/DI)

- `WalkSummaryUi` — новое поле `streakDays: Int` (серия на момент финиша).
- `MapViewModel` — опциональный `streakCalculator: StreakCalculator? = null`
  (последним параметром, тесты не ломаются), в сборке сводки
  `streakCalculator?.currentStreak(now) ?: 0`. Порядок гарантирует корректность:
  `TrackingService` пишет запись в историю (`appendToWalkHistory`) ДО публикации
  FINISHED-снимка в шину (`publishWalkStats`, TrackingService.kt ~586 → ~611), так
  что завершённая сегодня прогулка входит в серию.
- `AppContainer` — проброс `streakCalculator` в `MapViewModel`.

## Отступления от макета (согласовано спином / зафиксированы)

- **Подзаголовок «вечер · 48 минут»** из макета не добавлен: spec §3 его не
  описывает, EXPERIENCE при конфликте важнее макета, в списке задачи стори нет.
- **Подпись героя** — утверждённая копия spec §3 «территории открыто» (в макете
  «домашнего региона открыто (+0,4% сегодня)»).
- **TOMORROW_NOTE** оставлен (spec §3 п.6, утверждённая копия из domain; в теле
  макета её нет — при конфликте приоритет у спина).
- **Кегль hero** — `displaySmall` 38sp, т.к. spec §3 п.3 явно называет этот токен
  (макетная 54px в уменьшенной рамке ≈ 48sp).
- Значения карточек на склейках — честные данные прогулки (replay sim-walk-ok),
  а не макетные «3,6 км / 48 мин / 5 дней». Разделитель десятичных — запятая
  (ru-локаль, единообразно с `formatPercent`): «0,1 км», «0 мин», «1 день».

## Доработка по ревью склеек (2026-10-13, вторая итерация)

1. **Формат чисел** — расстояние форматируется `String.format(Locale ru-RU, "%.1f")`:
   «0,1 км» вместо «0.1 км» (независимо от системной локали устройства).
   Единственное централизованное место чисел экрана — `formatPercent`, он уже
   ru-локальный. Текст шеринг-интента (`shareWalkIntent`) не экранный и не менялся.
2. **Иерархия в карточках метрик** — `WalkFinalStatCard` теперь принимает число
   и единицу отдельно: строка «число + единица» (`Row`, baseline по низу):
   число `headingCard` (Nunito 800, 16), единица `meta` (12) тем же `inkHeadline`;
   подпись (`meta`, `inkDim`) под строкой. Склонение единицы серии — новый
   хелпер `pluralDaysWord` («день»/«дня»/«дней»; `pluralDaysShort` рефакторен
   через него, поведение прежнее).

После правок: `compileDebugKotlin` + `testDebugUnitTest` — SUCCESS. Склейки
пересняты (свежий APK `lastUpdateTime` 22:10, чистый сценарий целиком); на финале
заработали два достижения (A1 «Первый шаг» + «Полуночник») — по карточке на
каждое, как и задумано для списка достижений.

## Проверки

- `gradlew compileDebugKotlin` — **SUCCESS** (JDK 17 Temurin 17.0.20, ANDROID_HOME
  локально; JBR Android Studio — Java 25, Gradle отвергает).
- `gradlew testDebugUnitTest` — **SUCCESS**, 0 падений.
- Grep `Color\(0x|0xFF[0-9A-Fa-f]{6}` по `app/src` — совпадения только в
  `app/src/main/java/app/walkquest/ui/theme/WqColor.kt`.

## On-device сверка

- Устройство: `Medium_Phone_API_37.0`, debug APK, финальный прогон
  `lastUpdateTime` 2026-10-13 22:10 (первая итерация — 21:54; защита от протухшего
  APK пройдена в обоих прогонах).
- Сценарий: `clear` → `seed-package` (spb-city, v6) → онбординг →
  «Начать прогулку» → `replay _qa/fixtures/sim-walk-ok.track` (20/20 точек) →
  «Завершить» с подтверждением → финал. Заработано достижение A1 «Первый шаг» —
  карточка достижения на экране присутствует (как в макете).
- Сырые скриншоты (тёмная + светлая, `cmd uimode night yes/no`):
  `_qa/emulator-run/compare-verify/raw/03-walk-final-dark.png`,
  `_qa/emulator-run/compare-verify/raw/03-walk-final-light.png`.
- В `_qa/compare-stitch.py` добавлены два по-темных id (`03-walk-final-dark`,
  `03-walk-final-light`) — иначе выходной файл не мог получить имя
  `03-walk-final-<theme>.png`. Макет-сырьё: `compare/_mock/03-walk-final-{dark,light}.png`
  (имена сверены с README сверки).

### Склейки (макет ↔ реализация)

- `C:\Users\Lenovo\Documents\kimi\walkapp\_qa\emulator-run\compare-verify\03-walk-final-dark.png`
- `C:\Users\Lenovo\Documents\kimi\walkapp\_qa\emulator-run\compare-verify\03-walk-final-light.png`

Структура совпадает: похвала → hero-процент + подпись → сетка 3 карточек →
карточка достижения → TOMORROW_NOTE → «Поделиться результатом» → «На карту»;
нижней навигации нет в обеих темах; тёмная и светлая палитры — по токенам.

## Открытые вопросы

1. Подзаголовок «вечер · 48 минут» из макета — не реализован (нет в spec);
   если владелец хочет — отдельной микро-правкой.
2. Подпись hero в макете («домашнего региона открыто (+0,4% сегодня)») расходится
   с утверждённой копией spec («территории открыто») — оставлено по spec, макет
   кандидат на приведение в соответствие.
3. Кегль hero: макет визуально крупнее displaySmall — если владелец скажет
   «крупнее», ближайший токен `displayMedium` 48sp, однострочной правки.
