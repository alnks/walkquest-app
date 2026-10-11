---
name: walkquest-qa
description: 'QA/debug assistant for the WalkQuest project (walkapp). Use when verifying map, fog, tracking, POI, or package-install changes on the emulator or a USB phone; when a map render regression is suspected; when running the dev→test cycle; or when a BMad ticket in epic-map-foundation (or later epics) needs on-device verification evidence before it can be closed.'
---

# WalkQuest QA

## Purpose

This skill is the QA-specialized entry point for **WalkQuest** (workspace: `walkapp`).
It owns the debug and on-device verification workflow so that map/fog/tracking work
is checked by commands with thresholds — not by hand and not by eye alone.

## Ground rules

- **JVM unit tests do not catch render defects.** The worst regression of the epic
  (D2: fog holes not rendering — ring orientation) was invisible to all 205 tests
  and was caught only by pixel diff. Every map-render change needs the device loop.
- **Everything goes through `python _qa/wq.py`** — never hand-roll adb/gradle
  sequences. The harness encodes the pitfalls found in fix1–fix10
  (see `_qa/README.md`, section «Грабли»).
- Environment recipe and full command reference: `_qa/README.md`.
  Work journal of all verification runs: `_bmad-output/initiative-walkquest/epic-map-foundation/deferred-work.md`.

## The verification loop (run for every map/fog/tracking change)

1. `python _qa/wq.py doctor` — environment sane (adb, emulator, Pillow).
2. Unit tests: `gradlew.bat testDebugUnitTest` with
   `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`
   (system JAVA_HOME is unset; Android Studio's JBR is Java 25 — Gradle rejects it).
3. `python _qa/wq.py emu-start` then `python _qa/wq.py install`
   (install has the stale-APK guard: APK mtime must advance, otherwise it fails —
   do not bypass it; fix9 lost a run to a silently stale install).
4. Seed state as needed: `python _qa/wq.py clear` (clean install scenario),
   `python _qa/wq.py seed-package` (region package via run-as, style.json patched),
   `python _qa/wq.py push-base-url http://10.0.2.2:8777` + `wq.py serve`
   (full install path against the local package server, no GitHub publish).
5. Drive the scenario: `wq.py geo <lat> <lon>`, `wq.py replay <file.track>`
   (pacing is filter-safe by construction — do not inject raw `adb emu geo fix`
   in loops), UI taps via `input tap` after `wq.py uidump`.
6. Capture evidence: `wq.py shot <name>.png`, `wq.py log`,
   `wq.py diff a.png b.png`.
7. **Assert the render with thresholds**:
   `python _qa/check-fog-render.py shot.png --expect-gap-px <px> --expect-veil-max 0.95`.
   Reference ladder on Medium_Phone_API_37.0 at zoom ~16 (fix9 fixture):
   radius 10/15/20 m → gap 43/65/87 px; veil color ≈ (178,158,209) light theme.
8. Record the run: new folder `_qa/emulator-run/<name>-verify/` with screenshots
   and log, then a dated section in `deferred-work.md` — what changed, numbers
   (tests, gap px, coverage), what was NOT verified on device and why.

## Phone over USB (fast dev→test cycle)

- `python _qa/wq.py phone-install` — build, install, launch, and
  `adb reverse tcp:8777` in one command. The phone then reaches the PC's local
  package server at `http://localhost:8777` (add `--local-server` to also push
  the debug base-url override; `wq.py serve` must be running).
- Field-test-only behaviours (GPS accuracy rejection > 50 m, NETWORK-provider
  marker indoors, battery drain) are **not** covered by the emulator — they stay
  on the owner's device checklist (tickets 1.11/1.12 are HITL).

## BMad integration

- Before closing or moving a map/fog/tracking ticket past `built`, check its
  `verify` line against the loop above: unit tests green + device run recorded
  in `deferred-work.md` + screenshots in `_qa/emulator-run/`.
  A ticket whose verify demands on-screen behaviour is not done on JVM tests alone.
- Render-regression evidence belongs in the ticket's verification note:
  gap-px measurements and veil coverage from `check-fog-render.py`, not «looks fine».
- New QA tooling gaps become tickets in `epic-map-foundation/tickets.toml`
  (entries 14+: debug overlay, in-app track simulation) — do not grow ad-hoc scripts.

## App-side debug hooks (debug builds only)

- **Debug-оверлей карты (1.14, реализован 2026-10-07):** 5 быстрых тапов по карте
  (окно 1,5 с) — плашка с версией пакета в памяти, baseUrl, тайлами/лентой/вершинами
  завесы, build/upload мс, последним фиксом (провайдер/accuracy/возраст), счётчиками
  и последним вердиктом фильтра, POI видимо/всего. Только debuggable-сборки
  (FLAG_DEBUGGABLE). Заменяет uidump/logcat для чтения состояния. Провайдер фикса
  виден только во время записи (известный пробел).
- `files/qa/packages-base-url.txt` — package source override
  (`PackageSource.effectiveBaseUrl`; ignored in release and when invalid).
  Managed via `wq.py push-base-url <url>` / without argument to clear.
- Logcat tags: `WalkQuestFog` (fog build/upload timings), `TrackingService`
  (point verdicts). `wq.py log` filters to both.
