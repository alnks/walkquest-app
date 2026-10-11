---
title: '6.4 Релизная приватность и подписанная сборка'
type: 'feature'
ticket: '4'
created: '2026-10-11'
status: 'built'
baseline_revision: 'NO_VCS'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: []
review_loop_iteration: 0
context:
  - '_bmad-output/initiative-walkquest/architecture-walkquest/architecture-walkquest.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Релиз сейчас нарушает приватность молча (android:allowBackup="true" — треки и история утекали бы в Android-автобэкап Google Drive против AD-3), версия 0.1.0 не 1.0, а release-подпись — debug-ключ (нельзя публиковать).

**Approach:** allowBackup=false с комментарием AD-3; versionName 1.0; signingConfigs.release из local.properties (wqRelease-ключи) с честным fallback на debug-подпись, пока keystore владельца не создан (hitl-часть — генерация ключа по инструкции docs/release-signing.md); R8/shrink уже включены — регресс прогоном assembleRelease и полного набора тестов.

</frozen-after-approval>

## Tasks & Acceptance

**Execution:**
- [ ] `app/src/main/AndroidManifest.xml` -- android:allowBackup="false" (комментарий: AD-3, треки не покидают устройство даже в автобэкап).
- [ ] `app/build.gradle.kts` -- versionName "1.0"; localProperty вынесен на уровень android {}; signingConfigs.create("release") читает release.keystore/пароли/alias из local.properties, storeFile ставится только если файл существует; release buildType: signingConfig = release при живом keystore, иначе debug (поведение до 6.4 сохранено).
- [ ] `docs/release-signing.md` -- инструкция: keytool -genkeypair, куда положить keystore, какие ключи в local.properties, как проверить apksigner.

**Acceptance Criteria:**
- Given сборка без keystore владельца, when assembleRelease, then PASS, APK подписан (debug fallback).
- Given keystore прописан в local.properties, when assembleRelease, then APK подписан release-конфигом (apksigner verify проходит).
- Given манифест, then android:allowBackup="false"; versionName == "1.0".
- Given весь набор JVM-тестов, when прогон после изменений, then зелёный.

## Implementation Notes

Route oneshot. План лежит в story-release-plan.md, а не в пути от tickets.py (story-untitled-plan.md занят планом 6.3 — коллизия слага «untitled» у кириллических названий); slug-коллизия задокументирована в Notes эпика.

2026-10-11: сделано — allowBackup=false (комментарий AD-3 вынесен ПЕРЕД тегом application: комментарий внутри тега невалиден, поймано инлайн-ревью до сборки); versionName 1.0 (подтверждено aapt badging: versionName='1.0'); signingConfigs.release из local.properties с fallback на debug (apksigner verify: подписан Android Debug — keystore владельца не создан, hitl); docs/release-signing.md. R8/shrink/abi уже были включены (AD-8) — регресс подтверждён прогоном. Аудит логов: единственные Log-вызовы — TrackingService (provider/accuracy/age/вердикт, координат нет, комментарии AD-3) и MapScreen (метрики геометрии тумана) — чисто.

## Review Triage Log

2026-10-11, быстрая линза: субагенты недоступны — строгий инлайн-ревью. Находки:
- verdict high → patch (до сборки): XML-комментарий внутри <application> невалиден — перенесён.
- verdict low → patch: двойное присваивание signingConfig в release (старая строка AD-8-замера + новая) — старая удалена, полурасчленённый комментарий вычищен.
- Подтверждено: localProperty вынесен на уровень android{} — виден и signingConfigs, и defaultConfig; fallback не ломает сборку без keystore (факт: assembleRelease PASS, apksigner OK).

## Verification

**Commands:**
- `gradlew.bat :app:testDebugUnitTest :app:assembleRelease` -- expected: зелёно, PASS. ФАКТ 2026-10-11: полный набор JVM-тестов зелёный, assembleRelease PASS (~2,5 мин).
- apksigner verify --print-certs -- expected: verified. ФАКТ: подпись Android Debug (fallback, keystore — hitl владельца).
- aapt dump badging -- expected: versionName='1.0'. ФАКТ: подтверждено.
