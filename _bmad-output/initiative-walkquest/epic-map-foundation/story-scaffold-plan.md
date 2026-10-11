---
title: 'Scaffold приложения и трассирующий срез'
type: 'feature'
ticket: '2'
created: '2026-10-01'
status: 'built'
baseline_revision: 'NO_VCS'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['quick']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** В рабочей папке нет Android-проекта; эпик map-foundation не с чего строить. Нужен скелет приложения по spine с проверкой, что слои ui → domain → data соединены правильно (тикет 1.2, AD-1, AD-10).

**Approach:** Один gradle-модуль `app/` в корне walkapp: Kotlin 2.3.10, Compose BOM 2026.09.00, MapLibre 13.5.2 (Vulkan, AD-1), minSdk 29, ручной DI. Пакеты ui/viewmodel/domain/data/tracking/di по Structural Seed. Трассирующий срез: кнопка на экране карты прогоняет тестовую точку через domain-use-case в data-заглушку биткарты тумана и гасит один квадрат тумана на карте.

## Boundaries & Constraints

**Always:**
- Стек строго по spine Stack: Kotlin 2.3.10 (KGP + compose compiler), Compose BOM 2026.09.00, MapLibre `org.maplibre.gl:android-sdk` 13.5.x (пин 13.5.2 — последний стабильный в линии, Maven Central), minSdk 29.
- Направление зависимостей только ui → viewmodel → domain → data (AD-10); domain — чистый Kotlin без импортов Android/MapLibre; бит тумана открывается только через domain (AD-4).
- Ручной DI в `di/` (без Hilt); одна Activity; цвета из токенов DESIGN (fog #B29ED1, map-base #E3DCF0, surface-base #F6F3FA, ink-primary #241C33, accent #7A9A1F).
- Стиль карты — офлайн assets/style.json (фоновый слой), сеть для базовой карты не нужна (AD-2).

**Never:**
- Никаких фич других историй: настоящий туман-слой (1.6), треки/сервис (1.7), пакеты/Room/Supabase — не добавлять; `tracking/` — только пакет-заглушка.
- Не менять tickets.toml и `_bmad-output/` вне этого плана; не ставить Android SDK — это шаг человека (ticket unknown).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Трассировка | Тап «тестовая точка» на экране карты | Точка (59.94, 30.31) проходит ui → viewmodel → domain → data; бит 0 биткарты-заглушки открыт; квадрат тумана на карте скрыт | Бит уже открыт → состояние не меняется, повторный тап безопасен |
| Запуск | Холодный старт | Карта MapLibre рендерится, пан/зум жестами, туман-квадрат виден | Нет SDK/сети — не относится к рантайму приложения |

</frozen-after-approval>

## Code Map

- `_bmad-output/initiative-walkquest/architecture-walkquest/architecture-walkquest.md` — AD-1, AD-4, AD-10, Stack, Structural Seed: корень проекта = walkapp, модуль `app/`, пакет `app.walkquest`.
- `_bmad-output/initiative-walkquest/ux-walkquest/DESIGN.md` — токены цветов (строки 8–38) для темы и цвета тумана-заглушки.
- Кодовой базы нет — всё создаётся с нуля; переиспользовать нечего, не трогаем `_bmad/`, `_bmad-output/`, `_qa/`.

## Tasks & Acceptance

**Execution:**
- [ ] `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` -- конфиг Gradle: репозитории google/mavenCentral, плагины AGP 8.13.2 + kotlin-android 2.3.10 + kotlin compose plugin 2.3.10, каталог версий -- AGP 8.13.2 — максимум для KGP 2.3.x (матрица совместимости Kotlin), Gradle wrapper 8.14.5.
- [ ] `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`, `.gitignore` -- wrapper 8.14.5, стандартный android-игнор -- человек сможет собрать без установленного Gradle.
- [ ] `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `res/values/strings.xml`, `res/values/themes.xml`, `res/drawable/ic_launcher.xml` -- модуль приложения: compileSdk/targetSdk 36, minSdk 29, compose, зависимости MapLibre 13.5.2 + Compose BOM + lifecycle + coroutines, junit -- минимальный набор, Room/Supabase не подключаем.
- [ ] `app/src/main/assets/scaffold-style.json` -- офлайн-стиль MapLibre: version 8, один background-слой цвета map-base #E3DCF0 -- карта рендерится без сети и без пакета региона.
- [ ] `app/src/main/java/app/walkquest/WalkQuestApplication.kt`, `MainActivity.kt` -- Application с `MapLibre.getInstance()` и AppContainer; единственная Activity с Compose -- точка входа.
- [ ] `di/AppContainer.kt` -- ручной DI: FogRepository → OpenTileAtPoint → MapViewModel factory -- AD-10, без Hilt.
- [ ] `data/fog/FogRepository.kt` -- заглушка биткарты: in-memory BitSet на 1 тайл, open(bit)/isOpen -- владелец состояния тумана в data.
- [ ] `domain/OpenTileAtPoint.kt` -- use-case: точка (lat/lon) → бит тайла через repository; чистый Kotlin -- AD-4/AD-10.
- [ ] `viewmodel/MapViewModel.kt` -- StateFlow<MapUiState>, событие тестовой точки, вызов use-case -- UDF.
- [ ] `ui/theme/Theme.kt` -- Material3-тема по токенам DESIGN -- визуальная база.
- [ ] `ui/map/MapScreen.kt` (+ `MapViewContainer` при необходимости) -- AndroidView с MapView, жизненный цикл, стиль из assets, FillLayer-квадрат тумана у центра камеры, кнопка «тестовая точка» -- трассирующий срез виден на экране.
- [ ] `tracking/Placeholder.kt` -- пакет-заглушка с комментарием (заполняется в 1.7) -- граница пакета по AD-10.
- [ ] `app/src/test/kotlin/app/walkquest/domain/OpenTileAtPointTest.kt` -- JVM-тест: точка открывает бит, повтор идемпотентен -- проверка domain-логики без устройства.

**Acceptance Criteria:**
- Given установленный Android SDK и JDK 17, when `./gradlew assembleDebug`, then APK собирается (проверка отложена на человека — на машине нет JDK/SDK).
- Given запущенное приложение, when экран карты открыт, then MapLibre рендерит фон и карта двигается/зумится жестами.
- Given экран карты, when нажата «тестовая точка», then точка проходит ui → domain → data и квадрат тумана-заглушки скрывается с экрана.
- Given код domain, when проверены импорты, then нет импортов android.*, org.maplibre.* в `domain/`.

## Implementation Notes

- Toolchain-решение (2026-10-01): AGP поставлен 8.13.2, а не 9.x — KGP 2.3.x полностью поддерживает AGP до 8.13.x (матрица Kotlin), а AGP 9 требует built-in Kotlin и не даёт держать пин Kotlin 2.3.10 из spine. Gradle 8.14.5 (последний 8.x, связка AGP 8.13.2 + Gradle 8.14.x подтверждена в полях). JDK 17 — требование AGP.
- MapLibre 13.5.2 — последний стабильный в линии 13.5.x (maven-metadata, 2026-10-01); артефакт по умолчанию Vulkan (AD-1).
- Compose BOM 2026.09.00 существует в google-maven (проверено 2026-10-01). Если при первой сборке BOM потребует compileSdk 37 — поднять AGP до 9.x отдельным решением (миграция на built-in Kotlin), это не блокер scaffold.
- На машине нет JDK и Android SDK (проверено: PATH, Program Files, %LOCALAPPDATA%\Android) — `assembleDebug` и unit-тесты отложены на человека (ticket unknown).
- Реализация (2026-10-01): все файлы созданы согласно Code Map; gradle-wrapper.jar и скрипты взяты из тега gradle/gradle v8.14.5, distributionUrl поднят до 8.14.5. Конфиги (JSON/TOML/XML) проверены парсерами; в `domain/` нет импортов android.*/androidx.*/org.maplibre.* (проверено grep) — AD-10 соблюдён.
- Matrix Test Audit: строка «Трассировка» покрыта `OpenTileAtPointTest` (2 теста: открытие бита, идемпотентность) — прогон отложен вместе со сборкой (нет JDK). Строка «Запуск» — уровень устройства, по ticket unknown проверяет человек.
- Diff не снят: проект без VCS (baseline_revision=NO_VCS); изменения — только новые файлы, перечислены в отчёте.

## Plan Change Log

## Review Triage Log

- 2026-10-01, quick (выполнен в сессии исполнителя: вложенные субагенты недоступны, оркестратор запретил остановки). Вердикты: high 0 / medium 0 / low 1 / false 0 / maybe-false 0.
  - `ui/map/MapScreen.kt`, колбэк `setStyle` захватывал устаревшее `stubFogVisible` — low → patch: тап до конца загрузки стиля оставлял туман видимым при уже открытом бите; исправлено через `rememberUpdatedState`, в колбэке читается актуальное значение.

## Design Notes

Трассирующий срез намеренно минимален: `FogRepository` — не формат 1.6, а in-memory заглушка; туман — один `FillLayer` из GeoJsonSource, а не кастомный слой AD-1 (это история 1.6). Связка для проверки AD-10: `MapScreen → MapViewModel → OpenTileAtPoint → FogRepository`.

## Verification

**Commands:**
- `gradlew.bat assembleDebug` -- expected: BUILD SUCCESSFUL (только на машине с JDK 17 + Android SDK; на текущей машине отложено, ticket unknown).
- `gradlew.bat testDebugUnitTest` -- expected: OpenTileAtPointTest проходит (там же).

**Manual checks (if no CLI):**
- Синтаксис Kotlin/Gradle-файлов выверен при написании; структура пакетов соответствует Structural Seed; в `domain/` нет android-импортов (grep).
