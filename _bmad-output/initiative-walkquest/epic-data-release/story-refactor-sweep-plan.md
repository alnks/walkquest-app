---
title: '6.6 Refactor sweep'
type: 'refactor'
ticket: '6'
created: '2026-10-11'
status: 'built'
baseline_revision: 'NO_VCS'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: []
review_loop_iteration: 0
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** За историями 6.1–6.4 остались хвосты: устаревший doc-комментарий MainActivity (эпоха трёх вкладок), невыполненные quick-review линзы (субагенты недоступны в сессии сборки), подсказка о перезапуске вместо инвалидации in-memory карт.

**Approach:** Только cleanup: правка комментария, выписка отложенных находок в deferred-work.md эпика, финальный полный прогон. Новый объём — в отдельные тикеты, здесь не появляется.

</frozen-after-approval>

## Tasks & Acceptance

**Execution:**
- [ ] `app/src/main/java/app/walkquest/MainActivity.kt` -- doc-комментарий класса: шесть вкладок (актуальный список), без переписывания кода.
- [ ] `_bmad-output/initiative-walkquest/epic-data-release/deferred-work.md` -- выписка: три quick-review промпта (6.1/6.2/6.3) для прогона владельцем; инвалидация FogBitmap MapViewModel после импорта (сейчас — перезапуск, v1).

**Acceptance Criteria:**
- Given код после чистки, when полный прогон, then все JVM-тесты зелёные, assembleDebug и assembleRelease PASS.
- Отложенные находки зафиксированы в deferred-work.md эпика.

## Implementation Notes

Route oneshot. Скан TODO/FIXME по новым файлам (data/transfer, ui/profile, ProfileViewModel) — чисто.

2026-10-11: doc-комментарий MainActivity приведён к шести вкладкам; выписка отложенных находок — epic-data-release/deferred-work.md. Кода без тикета не добавлено.

## Review Triage Log

2026-10-11, быстрая линза: инлайн-ревью (субагенты недоступны). Находок не осталось: комментарий — единственное расхождение с кодом, исправлено; новый объём не вводился.

## Verification

**Commands:**
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` -- expected: зелёно, обе сборки PASS. ФАКТ 2026-10-11: 388 JVM-тестов зелёных, assembleDebug PASS, assembleRelease PASS.
