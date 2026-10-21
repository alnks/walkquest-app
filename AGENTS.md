<!-- bmad:context -->
<!-- Verified 2026-10-21 against 3b0ba23. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## walkquest (walkapp)

WalkQuest — Android-приложение-трекер прогулок (Kotlin, Jetpack Compose, MapLibre, minSdk 29). Планирование и тикеты — в `_bmad-output/initiative-walkquest/`, глубокий QA-контур — в `_qa/`.

## Policy

- **Запрещены юнит-тесты**: не создавать `app/src/test/`, не добавлять `testImplementation`-зависимости. Проверка логики — только прогоном на устройстве.
- **Запрещены UI/instrumented-тесты**: не создавать `app/src/androidTest/`, не добавлять `androidTestImplementation` (Compose UI Test, Espresso и т.п.).
- Любое изменение поведения проверяется на эмуляторе или телефоне по сценарию из `_qa/README.md`: реальные тапы по элементам (`wq.py uidump` → координаты → `ui-tap.py`), инжекция GPS (`wq.py geo`/`replay`/`simulate`), скриншоты и пороговые проверки (`check-fog-render.py`, `check-1-*.py`). Прогон без артефактов (скриншоты, логи, diff) считается невыполненным.

## Where things are

- Единая точка входа QA: `_qa/wq.py` (runbook — `_qa/README.md`, там же список грабель).
- Правила сценарной проверки тикетов: скилл `walkquest-qa`.
- Дизайн-моки и прототипы экранов: `_qa/proto-*.png`, `_qa/*-duo.png`.

## Running and verifying

- Gradle требует JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`); JAVA_HOME в системе не выставлен — задавать при вызове. Из Git Bash вызывать `cmd //c gradlew.bat ...` (одиночный `/c` ломается преобразованием путей MSYS).
- Эмулятор запускать только `python _qa/wq.py emu-start` (`-gpu auto`; `swiftshader_indirect` вешает гостя в adb-offline).
- Типовой цикл проверки: `wq.py doctor` → `emu-start` → `install` → `clear` → `seed-package` → `launch` → `geo`/`replay` → `shot` → `check-fog-render.py`.

<!-- /bmad:context -->
