# Deferred Work — epic-achievements-quests

## Итоги — 2026-10-09 (кодовая часть завершена, 4.1–4.7)

- 4.1 WalkRecord v2 (regionIds/modes/newTilesOpened, newTilesOpened=-1 «неизвестно» у
  записей до 4.1 — C8 не выдаётся задним числом по ним), OpenResult несёт regionId,
  PoiCardStore (прочтения карточек, парк = leisure=park по факту пакета v6).
- 4.2 Каталог 38 достижений (D4/D5 — в epic-leaderboard, нужен Supabase), движок
  (idемпотентный, полная перепроверка по истории = выдача задним числом), EarnedStore.
- 4.3 DistrictProgressCalculator (bbox-префильтр + PointInPolygon по центрам клеток);
  парсинг kind=district в BoundaryRepository. Генератор region-pack сейчас кладёт
  форты Кронштадта вместо 18 районов СПб — фикс tools отложен, движок generic.
- 4.4 Вкладка «Награды» (38 строк, условия, прогресс-бары, rare-метки, перепроверка
  при входе).
- 4.5 Пул 22 заданий, раздача случайно/из 6, одно активное, диалог замены без
  штрафа, выполнение на честном слове, отказ без штрафа, настройка скрытия кнопки.
- 4.6 Проверка после каждой прогулки (сервис), новые ачивки на финальном экране
  через порт WalkAchievements (мост AchievementEvents), D1–D3 от счётчиков заданий,
  AchievementStateFactory (сборка состояния в domain).
- 4.7 Sweep: acceptedCount как явный счётчик, companion MapUiState восстановлен.

### Проверки
- 362 JVM-теста (принудительный перезапуск), 0 падений. assembleRelease PASS.
- Дефекты по ходу: формула acceptedCount, обратимость newTilesOpened, companion,
  потерянный persistSession, ожидания regionId в старых тестах.

### Остатки
- Фикс генератора region-pack (районы СПб в boundaries.geojson).
- D4/D5 — epic-leaderboard. Веса пула по статистике отказов — после запуска.
- Rare-праздник: сейчас метка «✦» и акцент; Rubik Bubbles-типографика — UX-проход.
