---
title: 'Скачивание и установка пакета региона'
type: 'feature'
ticket: 5
created: '2026-10-04'
status: built
baseline_revision: 'NO_VCS'
route: 'full'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['self-review (quick; subagents unavailable in this runtime)']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Пакет региона собран (1.3) и URL-схема публикации зафиксирована (1.4), но приложение не умеет его скачивать и устанавливать: без `data/packages` офлайн-карта региона (AD-2, AD-6) и последующие истории эпика не на чем строить.

**Approach:** Чистый JVM-слой `data/packages` (без Android-зависимостей, чтобы верификацию гонять JVM-тестами): манифест → zip по схеме 1.4, проверка formatVersion + sha256 архива + sha256 файлов из meta.json, атомарная распаковка (temp → rename), подстановка абсолютного pmtiles-пути в style.json, репозиторий установленных пакетов читается только с диска (офлайн, AD-2). Тонкий use-case в domain, ручной DI в AppContainer.

## Boundaries & Constraints

**Always:**
- URL-схема 1.4: `<BASE>/manifest.json` → запись `{regionId, packageVersion, formatVersion, file, sizeBytes, sha256}` → `<BASE>/<file>`. Базовый URL — одна конфигурируемая константа (плейсхолдер до HITL-публикации R2; меняется одной строкой).
- Проверки в порядке: formatVersion (из манифеста, SUPPORTED=1) → sizeBytes+sha256 архива → meta.json из распаковки (formatVersion ещё раз) → sha256 каждого файла из `meta.json.checksums`. Любая неудача — понятная типизированная ошибка, temp-папка удаляется, установленное состояние не портится.
- AD-10: сеть/файлы/zip — только `data/packages`; domain — чистый Kotlin (use-case поверх репозитория); ошибки через границы — sealed-результаты (Consistency Conventions), не исключения.
- Офлайн после установки: чтение установленных пакетов — только с диска; сеть — только в пути скачивания (AD-2).
- Подстановка pmtiles: строка `meta.json.pmtilesUrlPlaceholder` (`pmtiles://tiles.pmtiles`) в style.json заменяется на `pmtiles://file://<абсолютный путь tiles.pmtiles>` (конвенция из отчёта 1.3).
- JSON-парсинг — kotlinx-serialization (plugin = kotlin 2.3.10, runtime 1.11.0, последний стабильный в Maven Central на 2026-10-04); HTTP — HttpURLConnection (spine не пинит OkHttp, новых сетевых зависимостей не вводим); весь код data/packages — без импортов android.* (JVM-тестируемо), rootDir инжектируется из AppContainer (context.filesDir).

**Never:**
- Не менять tickets.toml, tools/region-pack, эпики 2–6, трассирующий срез 1.2 (FogRepository/OpenTileAtPoint/MapScreen не трогаем).
- Не добавлять UI/экран регионов, Room, Supabase — не эта история; не ставить JDK/Android SDK (прогон тестов — HITL).
- Не качать пакет при старте приложения и не делать автообновлений — установка только явным вызовом use-case.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Установка | Валидные манифест+zip на HTTP-сервере | zip скачан, проверен, распакован в `packages/<regionId>/v<N>/`; style.json с абсолютным pmtiles-путём; пакет виден в репозитории без сети | — |
| Повторная установка | Та же версия уже установлена | `AlreadyInstalled`, повторного скачивания нет | — |
| formatVersion | Манифест/meta.json с formatVersion ≠ 1 | Отказ до/при распаковке | `UnsupportedFormatVersion(found=…, supported=1)` |
| Битый архив | sha256/sizeBytes скачанного ≠ манифесту | Отказ до распаковки, temp удалён | `ArchiveChecksumMismatch` |
| Битый файл | sha256 файла ≠ meta.json.checksums | Отказ после распаковки, temp удалён, целевая папка не появляется | `FileChecksumMismatch(file=…)` |
| Битый zip | Усечённый/не-zip файл | Отказ с понятной ошибкой | `ArchiveCorrupted` |
| Нет региона | regionId отсутствует в манифесте | Отказ | `RegionNotFound(regionId)` |
| Нет сети/HTTP ошибка | Сервер недоступен, 404 | Отказ, состояние не меняется | `NetworkError` |

</frozen-after-approval>

## Code Map

- `tools/region-pack/out/region-spb-city-v1.zip` — эталонный пакет: состав style.json, tiles.pmtiles, walkability.grid, boundaries.geojson, poi.json, meta.json; meta.json поля: formatVersion=1, regionId, packageVersion, tileSizeMeters, maxzoom, bbox, grid{…}, poiCount, boundaryCount, wikipedia{…}, pmtilesUrlPlaceholder=`pmtiles://tiles.pmtiles`, generatedBy/At, checksums{5 файлов sha256}. Парсер MetaJson обязан совпадать с этими полями (ignoreUnknownKeys).
- `tools/region-pack/publish.md` — шаблон manifest.json 1.4 (поля baseUrl + packages[regionId, packageVersion, formatVersion, file, sizeBytes, sha256]); парсер манифеста сверять с ним.
- `app/src/main/java/app/walkquest/di/AppContainer.kt` — ручной DI; добавить RegionPackageRepository + InstallRegionPackage; сейчас без Context — AppContainer должен принять filesDir из WalkQuestApplication.
- `app/src/main/java/app/walkquest/WalkQuestApplication.kt`, `MainActivity.kt`, `viewmodel/MapViewModel.kt`, `data/fog/FogRepository.kt` — срез 1.2, не менять поведение (в Application — только передача filesDir в контейнер).
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — добавить kotlin-serialization plugin (version.ref kotlin) и kotlinx-serialization-json 1.11.0.
- `_bmad-output/.../architecture-walkquest.md` — AD-2, AD-6, AD-10, Consistency Conventions (sealed-результаты, formatVersion).

## Tasks & Acceptance

**Execution:**
- [ ] `gradle/libs.versions.toml`, `app/build.gradle.kts` — kotlinx-serialization (plugin + json 1.11.0) — парсинг манифеста/meta.json, JVM-совместимость тестов.
- [ ] `data/packages/PackageModels.kt` — @Serializable Manifest/ManifestPackage/MetaJson(нужные поля), InstalledPackage, sealed PackageError + PackageInstallResult (Success/AlreadyInstalled/Failure) — контракты слоя, ошибки по Conventions.
- [ ] `data/packages/PackageSource.kt` — константа `DEFAULT_BASE_URL` (плейсхолдер `https://pub-<hash>.r2.dev`, комментарий «заменить после HITL 1.4»), SUPPORTED_FORMAT_VERSION=1, сборка URL — одна точка конфигурации.
- [ ] `data/packages/HttpPackageDownloader.kt` — интерфейс PackageDownloader + реализация на HttpURLConnection (стриминг в файл, контроль sizeBytes) — сеть изолирована, подменяема в тестах.
- [ ] `data/packages/PackageVerifier.kt` — чистый JVM: sha256(File), сверка formatVersion/архива/файлов — сердце verify тикета.
- [ ] `data/packages/PackageInstaller.kt` — распаковка в `.install-<uuid>` (ZipInputStream, защита от zip-slip), верификация, подстановка pmtiles-пути, атомарный rename в `packages/<regionId>/v<N>`, cleanup при любой ошибке — атомарность установки.
- [ ] `data/packages/RegionPackageRepository.kt` — фасад: installRegion(regionId) (манифест→выбор→скачивание→установка, идемпотентность), installedPackages()/getInstalled() — только диск (AD-2) — единая точка для domain.
- [ ] `domain/InstallRegionPackage.kt` — use-case-делегат поверх репозитория — связка vm→domain→data по AD-10.
- [ ] `di/AppContainer.kt`, `WalkQuestApplication.kt` — проводка: AppContainer(filesDir), regionPackageRepository, installRegionPackage; срез 1.2 сохранить — DI по конвенции 1.2.
- [ ] `app/src/test/kotlin/app/walkquest/data/packages/PackageVerifierTest.kt` — JVM-тесты матрицы: валидный пакет, битая сумма файла, formatVersion≠1, битый zip, битая сумма архива — края верификации.
- [ ] `app/src/test/kotlin/app/walkquest/data/packages/RegionPackageRepositoryTest.kt` — э2э на локальном `com.sun.net.httpserver`: установка, pmtiles-подстановка, повторная установка, отказ по formatVersion, офлайн-чтение — матрица целиком без устройства.
- [ ] Python-сверка без SDK: tomllib для libs.versions.toml; поля эталонных meta.json/manifest против @Serializable-моделей; плейсхолдер в эталонном style.json; grep: no android.* в data/packages и domain — то, что можно проверить на этой машине.

**Acceptance Criteria:**
- Given валидные манифест+zip на HTTP-URL, when installRegion("spb-city"), then пакет установлен в `packages/spb-city/v1/`, style.json содержит абсолютный pmtiles-путь, репозиторий отдаёт пакет без сети (покрыто JVM-тестом; прогон — HITL `gradlew.bat testDebugUnitTest`).
- Given пакет с formatVersion≠1 или битой контрольной суммой (архива или файла), when installRegion, then отказ с типизированной понятной ошибкой, установленное состояние не изменилось (JVM-тесты).
- Given код domain и data/packages, when проверены импорты, then domain без android.*/сети, data/packages без android.* (grep).
- Given опубликованный R2 (HITL 1.4), when BASE-URL заменён одной строкой и приложение на устройстве, then пакет скачивается по публичной ссылке и карта региона работает без сети (HITL-э2э на человеке).

## Implementation Notes

Реализация (2026-10-04), все решения исполнителя зафиксированы здесь:

- Создан слой `app/src/main/java/app/walkquest/data/packages/` (pure JVM, без android.*): `PackageModels.kt` (@Serializable PackageManifest/ManifestPackage/PackageMeta, InstalledPackage, sealed PackageError (10 типов, русские сообщения), sealed PackageInstallResult), `PackageSource.kt` (DEFAULT_BASE_URL-плейсхолдер с комментарием про HITL 1.4, SUPPORTED_FORMAT_VERSION=1, сборка URL), `HttpPackageDownloader.kt` (интерфейс PackageDownloader + HttpURLConnection-реализация, стриминг в файл), `PackageVerifier.kt` (sha256, formatVersion, архив, файлы из checksums), `PackageInstaller.kt` (ZipFile-распаковка с zip-slip-защитой во `.install-<uuid>`, верификация meta/checksums, подстановка `pmtiles://file://<абсолютный>` в style.json, rename в `packages/<regionId>/v<N>`, cleanup), `RegionPackageRepository.kt` (манифест → выбор → fail-fast formatVersion → AlreadyInstalled → скачивание → verifyArchive → install; чтение установленных только с диска; Mutex против гонок установок).
- domain: `InstallRegionPackage` (тонкий делегат, связка vm→domain→data по образцу OpenTileAtPoint из 1.2). DI: AppContainer принимает filesDir (WalkQuestApplication передаёт `filesDir`), `File(filesDir, "packages")` — корень хранилища. Срез 1.2 не изменён (FogRepository/OpenTileAtPoint/MapViewModel/MapScreen не тронуты).
- Зависимости: kotlinx-serialization (plugin version.ref=kotlin 2.3.10, json 1.11.0 — последний стабильный в Maven Central на 2026-10-04, maven-metadata). HTTP — HttpURLConnection, OkHttp не добавлялся (spine его не пинит). INTERNET-пермишен уже был в манифесте со времён 1.2.
- Распаковка через `java.util.zip.ZipFile`, а не ZipInputStream: ZipFile валидирует центральный каталог и падает на мусоре/усечении (ZipInputStream молча вернул бы null → не тот тип ошибки).
- Тесты (JVM, без Android): `TestPackages.kt` (сборка валидных/битых zip и manifest той же структуры, что у 1.3–1.4), `PackageVerifierTest` (11 тестов: sha256 эталон, formatVersion, архив, файлы, установка happy-path с проверкой pmtiles-подстановки, битый zip ×2, битая сумма файла, formatVersion из meta, чистота после отказа), `RegionPackageRepositoryTest` (7 тестов на локальном com.sun.net.httpserver: э2э-установка + офлайн-чтение, идемпотентность, RegionNotFound, formatVersion fail-fast до скачивания, битая сумма архива, битый zip по сети, NetworkError). Matrix Test Audit: все 8 строк I/O-матрицы покрыты тестами; прогон отложен (нет JDK/SDK, HITL `gradlew.bat testDebugUnitTest`) — как в истории 1.2.
- Вместо прогона тестов — Python-зеркало алгоритма `_qa/check-1-5-package-install.py` против фактических артефактов: локальный http.server отдал реальные manifest.json (шаблон publish.md) и `out/region-spb-city-v1.zip`; проверены поля моделей ↔ фактические JSON, sha256 архива и всех 5 файлов, плейсхолдер и подстановка pmtiles, негативы — 24/24 PASS (2026-10-04).
- Проверки без SDK: `libs.versions.toml` валиден (tomllib); grep: нет android.*/androidx.*/org.maplibre.* в `domain/` и `data/packages/` (AD-10); DEFAULT_BASE_URL — одна константа для смены после HITL-публикации R2.
- Diff не снят: проект без VCS (baseline_revision=NO_VCS); изменения — новые файлы data/packages (6), domain/InstallRegionPackage.kt, 3 тестовых файла, правки AppContainer/WalkQuestApplication/libs.versions.toml/app/build.gradle.kts, плюс `_qa/check-1-5-package-install.py`.

## Plan Change Log

## Review Triage Log

Quick self-review (subagents unavailable — вложенный запуск невозможен, зафиксировано; как в историях 1.3–1.4). Вердикты: high 0 / medium 0 / low 0 / false 1 / maybe-false 0. Проверены: план ↔ код ↔ тесты ↔ результаты Python-зеркала; матрица краёв ↔ тесты (8/8 строк покрыты); импорты (AD-10).
- `PackageInstaller.moveIntoPlace`, delete→rename существующей целевой папки не атомарен — **false** (недостижимо в реальном потоке: репозиторий отсекает ту же версию веткой AlreadyInstalled до вызова installer, разные версии — разные папки `v<N>`; прямых вызовов installer мимо репозитория нет).

## Design Notes

- Весь `data/packages` — pure JVM (rootDir: File инжектируется): верификация и установка гоняются локальными unit-тестами без SDK, что критично на машине без JDK (прогон откладывается, но тесты настоящие, не Robolectric).
- Идемпотентность и атомарность: установка пишет только в `.install-*`, видимое состояние меняет один rename; провал на любом шаге = удаление temp, прежняя установка (если была) не тронута. Повтор той же версии — `AlreadyInstalled` без скачивания (сверка по манифесту до загрузки zip).
- URL zip: `<configuredBase>/<file>` из записи манифеста; `baseUrl` внутри манифеста приложением не используется (он для человека/миграции домена, publish.md) — меньше доверия к данным сети, одна точка смены адреса.

## Verification

**Commands:**
- `gradlew.bat testDebugUnitTest` — expected: PackageVerifierTest (11) + RegionPackageRepositoryTest (7) зелёные. **HITL: на машине нет JDK/SDK, не ставить; прогон за человеком.**
- `python _qa/check-1-5-package-install.py` — **выполнено 2026-10-04: 24/24 PASS** (зеркало алгоритма против фактических manifest/zip из 1.3–1.4, включая локальную HTTP-отдачу и негативы).

**Manual checks (if no CLI):**
- grep: нет android.*/androidx.*/org.maplibre.* в `domain/` и `data/packages/` — **выполнено, чисто**; трассирующий срез 1.2 не изменён; DEFAULT_BASE_URL — одна строка-константа с комментарием про HITL 1.4.
- **HITL-э2э после публикации R2 (пауза 1.4):** заменить DEFAULT_BASE_URL на `https://pub-<hash>.r2.dev` → на устройстве вызвать installRegion("spb-city") → карта региона открывается в авиарежиме (подключение style.json+пакета к MapLibre — история 1.6).
