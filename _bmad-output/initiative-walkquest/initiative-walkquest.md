---
type: initiative
title: "WalkQuest: город под туманом, который расступается от прогулок"
parent: none
covers: [FR-1, FR-2, FR-3, FR-4, FR-5, FR-6, FR-7, FR-8, FR-9, FR-10, FR-11, FR-12, FR-13, FR-14, FR-15, FR-16, FR-17, FR-18, FR-19, FR-20, FR-21]
after: []
assignee: ""
risk: high
---

# WalkQuest

## Description

WalkQuest — Android-приложение, превращающее прогулку в квест: карта города скрыта Туманом, который расступается только от реального перемещения пешком или на велосипеде/самокате. PRD — единственный источник требований (FR-1…FR-21); архитектурный контракт — spine (AD-1…AD-10); UX и контент финализированы. Инициатива доставляет v1: карта с туманом, трекинг, цели и напоминания, 40 достижений и 22 задания из каталога, лидерборд на Supabase, перенос данных и публикация в Google Play.

## Outcome

У Оли (28 лет, без привычки гулять) появляется повод выходить каждую неделю: сигнал успеха — метрики PRD SM-1 (retention D7 ≥ 25%) и SM-3 (автор сама пользуется приложением еженедельно через 3 месяца после запуска).

## Requirements

Источник требований — нумерованный PRD: FR-1…FR-21 (`prd-walkquest/prd-walkquest.md`, раздел 4). Каждый эпик цитирует свои FR в `covers` и раскрывает их в своих Requirements на инсепшне.

## Done when

1. Полный цикл v1 работает на реальном устройстве среднего сегмента: онбординг → город в тумане → прогулка в фоне → туман расступился → финальный экран.
2. Базовый цикл (карта, трекинг, растуманивание, статистика) работает полностью офлайн; сеть нужна только лидерборду и скачиванию пакетов (AD-2).
3. На сервер уходят только ник и агрегированные метрики; треки не покидают устройство (AD-3) — проверено на трафике.
4. Все FR-1…FR-21 реализованы; Non-Goals PRD (§5) не нарушены.
5. Числовые пороги AD-8 (≥30 fps, пакет ≤ 50 МБ, батарея ≤ 5%/час, APK ≤ 30 МБ) подтверждены измерениями на устройстве.
6. Приложение опубликовано в Google Play (декларация background location, политика конфиденциальности, возрастной рейтинг).

## Boundaries

Граница инициативы — Android-приложение, скрипт генерации пакетов регионов (`tools/region-pack`) и бэкенд лидерборда на Supabase. Не входит: iOS, монетизация, соцсеть, облачная синхронизация — Non-Goals PRD. Сквозной demo-путь: онбординг → карта в тумане → первая прогулка → туман расступился → финальный экран с первым достижением.

- Touch point: Google Play Console — публикация, декларации, возрастной рейтинг; owner: epic-data-release
- Touch point: Supabase (проект, RLS, таблицы) — настройка бэкенда лидерборда; owner: epic-leaderboard
- Touch point: OpenStreetMap и Википедия — источники данных пакетов и POI, потребляются скриптом; owner: epic-map-foundation

## References

- prd — _bmad-output/initiative-walkquest/prd-walkquest/prd-walkquest.md, разделы 4 (FR), 7 (метрики), Constraints
- architecture — _bmad-output/initiative-walkquest/architecture-walkquest/architecture-walkquest.md, AD-1…AD-10
- ux — _bmad-output/initiative-walkquest/ux-walkquest/DESIGN.md и EXPERIENCE.md
- content — _bmad-output/initiative-walkquest/ux-walkquest/content-catalog.md (40 достижений, 22 задания, модель территории)

## Notes

- Decision: нарезка на 6 эпиков утверждена пользователем (2026-10-01); первым идёт платформенный эпик map-foundation — он же гейт AD-8.
- Decision: оценка (estimation) выключена — соло-проект, прогноз по датам не нужен (настройка workflow.estimation).
- Open question: финальное название приложения (PRD OQ-8) — не блокирует разработку, нужно к публикации (epic-data-release).
