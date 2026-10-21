# Story 7.10 — POI-карточка по утверждённому макету (AL-10)

Дата: 2026-10-14. Статус: выполнено, on-device сверка пройдена.

## Что сделано

### Код

1. **Фото-полоса до 3 фото** (`data/poi/Poi.kt`, `viewmodel/MapViewModel.kt`,
   `ui/map/MapScreen.kt`):
   - `Poi`/`PoiJson`: новое опциональное поле `photos: List<String>` (пути от корня
     пакета). Вычисляемое `Poi.photoPaths` — `photos.take(MAX_PHOTOS = 3)` или
     одиночное `photo` как список из одного; оба отсутствуют — пусто (пакет v1).
     Обратная совместимость: пакеты v1–v6 парсятся без изменений.
   - `MapViewModel`: `selectedPoiPhoto: File?` → `selectedPoiPhotos: List<File>`;
     `resolvePoiPhotos` резолвит пути с той же отсечкой `../`, битые/отсутствующие
     файлы пропускает.
   - `PoiCard`: фото-полоса — `Row` ячеек `weight(1f)`, `spacedBy(4dp)`, высота
     96dp, `ContentScale.Crop`, Surface обрезает по радиусу карточки
     (макет `remaining-screens.html` `.photos`: ряд из 3 фото, зазор 4px).
     Плейсхолдер загрузки: `surfaceBase` → `surfaceSunken` (спека §11).
2. **Прибивка карточки** (`MapScreen.kt`): idle — `bottom = 12dp` над навигацией,
   `HomeSheet` скрыта, пока карточка открыта; запись — `bottom = 228dp` над
   WalkPanel (прежнее состояние сохранено, константа `POI_CARD_BOTTOM_RECORDING`).
   Пока карточка открыта в idle, скрыты `MeLocationFab` и `DebugToggleFab`
   (иначе нависали над фото-полосой; в записи видны как прежде).
3. **Закрытие**: явная кнопка «Закрыть» **удалена**. Решение: в утверждённом макете
   (`remaining-screens.html`, секция «Карточка POI», обе темы) кнопки нет;
   EXPERIENCE (Interaction Primitives) и DESIGN (`poi-card`) описывают закрытие
   только свайпом вниз и тапом вне карточки. Спека §11 упоминает текст-кнопку
   «Закрыть» — при конфликте спина (EXPERIENCE/DESIGN) важнее макета, макет
   подтверждает отсутствие кнопки. Свайп вниз (`detectVerticalDragGestures`,
   порог 120px) и tap-outside (`onMapTappedOutside`) сохранены.
4. **Контент**: заголовок `headingScreen` (22) → `headingSection` (18, Nunito 800,
   ink-headline) — спека §11 буквально; описание `body`/`inkSecondary`, 4 строки
   с многоточием; «Подробнее» акцентом (`accentText`) с подчёркиванием при
   обрезке + `url` (без изменений, сверено со спекой); fallback категорией meta.
5. **Тесты**: `PoiRepositoryTest` + «photos array caps at three and falls back to
   single photo» (обрезка до 3, fallback на `photo`, пусто без фото).

### Инфраструктура QA

- `local.properties`: восстановлен `sdk.dir` (пропал из файла; gradle не собирал).
  Файл в `.gitignore`.
- `_qa/compare-stitch.py`: добавлены по-темные id `12-poi-card-dark` /
  `12-poi-card-light` (по образцу 7.2–7.9).
- `_qa/emulator-run/compare-verify/make-fog-v6.py`: генератор `spb-city.fog` для
  сетки v6 (603×307) с открытым пятном радиусом 500 м вокруг POI (52 проходимых
  тайла) — подкладка через `run-as`, как в прогоне fix3.

## Проверки

- `gradlew compileDebugKotlin` — BUILD SUCCESSFUL (JDK 17).
- `gradlew testDebugUnitTest` — 386 тестов, 0 падений.
- `grep -rn 'Color(0x|0xFF[0-9A-Fa-f]{6}' app/src` — совпадения только в
  `ui/theme/WqColor.kt`.
- Дизайн-интеллект (обязательный первый шаг): навыки `ui-ux-pro-max` /
  `design-system` вызваны; по гайдам: свайп-карусели избегаем (конфликт жестов),
  ряд ячеек 1:1 по ширине, токены вместо raw hex, фото crop по контейнеру.
  Прямых совпадений по «bottom sheet carousel» в базе нет (0 results, retry 2×) —
  применены общие гайды + утверждённые макеты/спека проекта.

## On-device сверка (Medium_Phone_API_37.0)

Сценарий: seed spb-city **v6** (`wq.py seed-package`) → подкладка fog (пятно
вокруг POI) → geo fix в 90 м южнее → FAB «К моей точке» → тап по маяку →
карточка. POI: «Петру Великому» (osm 249082841) — фото + url + длинное описание
(показывает обрезку 4 строк и «Подробнее»).

- Светлая тема: `raw/c1-poi-card-light.png` — карточка прибита к нижней зоне,
  HomeSheet скрыта, полоса фото, заголовок/описание/«Подробнее» по спеке.
- Тёмная тема (`cmd uimode night yes`, тема приложения системная): свежий сценарий
  после перезапуска — `raw/c2-poi-card-dark.png`.
- Закрытие tap-outside: тап по карте → карточка закрылась, HomeSheet вернулась
  (`raw/d1-after-tap-outside.png`).
- Состояние записи: «Начать прогулку» → карточка над WalkPanel, панель записи
  видна (`raw/d4-recording.png`, `raw/d5-poi-card-recording.png`); прогулка
  завершена штатно (удержание «Завершить» → диалог → финал).
- Сырые скриншоты: `_qa/emulator-run/compare-verify/raw/` (a1–d6).

### Склейки (макет ↔ реализация)

- `C:\Users\Lenovo\Documents\kimi\walkapp\_bmad-output\initiative-walkquest\ux-walkquest\compare\12-poi-card-dark.png`
- `C:\Users\Lenovo\Documents\kimi\walkapp\_bmad-output\initiative-walkquest\ux-walkquest\compare\12-poi-card-light.png`

Макет-сырьё `_mock/12-poi-card-*.png` в этом прогоне уже с полосой из 3 фото
(перерендер не потребовался). Сравнение фото-полосы по макету рендеру: на
устройстве полоса из **одного** фото — пакет spb-city v6 содержит только
одиночное поле `photo` (422 фото на 4583 POI, по одному на POI); ряд из 3
покрыт юнит-тестом (`photos` обрезается до 3) и макетной сверкой компоновки.

## Отступления и открытые вопросы

1. **Поле `photos` пишет не генератор**: `tools/region-pack` по-прежнему кладёт
   одно `photo` на POI (Wikimedia-лид-изображение). UI и парсер готовы к массиву;
   доработка генератора — отдельная задача, если понадобятся пакеты с 3 фото.
2. **Кнопка «Закрыть»** удалена вопреки букве спеки §11 — приоритет
   EXPERIENCE/DESIGN + макет (зафиксировано выше).
3. Замечание (pre-existing, вне AL-10): во время записи `QuestFab` («+ Задание»)
   перекрывает правый нижний угол карточки POI.
4. Ручной выбор темы в приложении отсутствует (системная, спека §10) — тёмная
   тема на эмуляторе включалась системно (`cmd uimode night yes`), после прогона
   возвращено `night auto`.
5. Верх карточки при 1 фото визуально крупнее макетной ячейки (фото во всю
   ширину при 96dp против ~88dp ячейки в макете при 3) — соответствует спеке
   «полоса во всю ширину (~96dp)».
