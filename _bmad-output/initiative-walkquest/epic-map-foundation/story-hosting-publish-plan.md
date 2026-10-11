---
title: 'Публикация пакета на хостинге'
type: 'feature'
ticket: 4
created: '2026-10-04'
status: done
baseline_revision: 'NO_VCS'
route: 'oneshot'
route_source: 'auto'
review: 'quick'
review_source: 'pinned'
lenses_ran: ['self-review (quick; subagents unavailable in this runtime)']
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Пакет `tools/region-pack/out/region-spb-city-v1.zip` (28,9 МБ, 1.3) собран и верифицирован, но не опубликован; истории 1.5 нужны выбранный хостинг, публичная ссылка и зафиксированная URL-схема. Регистрация и настройка бакета — действия человека (тикет hitl).

**Approach:** Подтвердить выбор spine (Cloudflare R2, запасной GitHub Releases) актуальными цифрами free tier, зафиксировать URL-схему для `data/packages` (1.5), подготовить пошаговый чек-лист публикации `tools/region-pack/publish.md` на 10–15 минут работы человека и выполнить все проверки, возможные без аккаунта.

</frozen-after-approval>

## Implementation Notes

Oneshot: production-код не меняется — артефакты истории это документ публикации, Decision-строки и локальные проверки; объём правок < 100 строк, файлов ≤ 3.

- Выбор хостинга (проверено веб-поиском 2026-10-04):
  - **Cloudflare R2** — основной. Free tier: 10 ГБ хранения/мес, 1 млн Class A и 10 млн Class B операций/мес, egress бесплатен без лимита. Публичный доступ: managed-домен `https://pub-<hash>.r2.dev` (включается в настройках бакета) или свой домен. Пакет 28,9 МБ → ~340 версий пакета влезают в бесплатные 10 ГБ; отдача трафика не тарифицируется вовсе. Источники: developers.cloudflare.com/r2/pricing/, developers.cloudflare.com/r2/buckets/public-buckets/.
  - **GitHub Releases** — запасной. Файл релиза < 2 ГиБ, до 1000 ассетов на релиз, лимита на суммарный размер релиза и трафик нет. Источник: docs.github.com/en/repositories/releasing-projects-on-github/about-releases. Минусы для основного: не CDN, нет стабильного «плоского» URL-каталога (URL привязан к тегу релиза и репозиторию), публикация требует git-репозитория проекта (в рабочей папке VCS нет).
  - Supabase не рассматривается (AD-6: лимит ~10 ГБ/мес трафика исчерпается десятками скачиваний).
- **Решение: R2 основной, GitHub Releases запасной** — подтверждает spine Decision; трафик раздачи пакетов десятки МБ × N скачиваний, у R2 он бесплатен и CDN по умолчанию.
- URL-схема для 1.5 (зафиксирована Decision-строками в `.memlog.md` архитектуры и Notes эпика):
  - База: `https://pub-<hash>.r2.dev` (выдаётся при включении Public Development URL; при переезде на свой домен меняется только база — записать в meta манифеста).
  - Пакет: `<BASE>/region-<regionId>-v<packageVersion>.zip` — имя файла совпадает с именем, которое кладёт генератор (`meta.json.regionId`/`packageVersion`), напр. `region-spb-city-v1.zip`.
  - Манифест: `<BASE>/manifest.json` — стабильный индекс: `{ "baseUrl", "packages": [ { "regionId", "packageVersion", "formatVersion", "file", "sizeBytes", "sha256" (архива) } ] }`. 1.5 качает манифест → выбирает регион → качает zip → проверяет `formatVersion` и sha256 архива, затем checksums файлов из meta.json. Манифест обновляется человеком при каждой публикации (шаблон и точный JSON — в publish.md).
  - Запасной (GitHub Releases): `https://github.com/<owner>/<repo>/releases/download/region-<regionId>-v<N>/region-<regionId>-v<N>.zip`, манифест лежит ассетом того же релиза `packages` с тегом `packages`.
- Локальные проверки (без аккаунта), 2026-10-04:
  - `python tools/region-pack/verify.py out/region-spb-city-v1.zip` — PASS, exit 0 (состав, checksums roundtrip, pmtiles-заголовок, слои, сетка).
  - HTTP-отдача: пакет отдан локальным `http.server`, скачан целиком (28 929 684 байта, HTTP 200), sha256 скачанного == sha256 локального: `0e453f155d99e052f13319fbed4e8d84a8dd9fd595c79ffbb241a54cfef1d42d`.
- `gh` CLI в среде отсутствует (`which gh` — нет), git-репозитория нет → вариант «опубликовать прямо сейчас через Releases» из этой среды недоступен; команда для человека записана в publish.md как запасной путь.
- HITL-пауза: создание бакета, включение публичного доступа, загрузка zip и manifest.json, проверка скачивания по публичной ссылке — шаги человека по `tools/region-pack/publish.md`. После их выполнения verify тикета («пакет скачивается по публичной ссылке без авторизации») подтверждается человеком одной командой `curl -I <url>`.
- Файлы истории: создан `tools/region-pack/publish.md` (чек-лист публикации, R2 + запасной GitHub Releases, шаблон manifest.json с реальными sizeBytes/sha256); Decision-строки добавлены в `architecture-walkquest/.memlog.md` (хостинг + URL-схема) и Notes `epic-map-foundation.md`. Код генератора и app/ не тронуты, tickets.toml не менялся.

## Plan Change Log

## Review Triage Log

Quick self-review (subagents unavailable — вложенный запуск невозможен, зафиксировано; как в истории 1.3). Проверены: план ↔ publish.md ↔ Decision-строки ↔ результаты прогонов.
- publish.md, шаг 8: `%TEMP%` не раскрывается в PowerShell — **medium (проверено: синтаксис cmd, в PowerShell даст битый путь)** → исправлено на `$env:TEMP` + `curl.exe`. Маршрут: patch (исправлено до финализации).
- publish.md, манифест: sizeBytes/sha256 в шаблоне сверены с фактическими (28 929 684 / 0e453f…f1d42d) — совпадают, замечаний нет.
- Критерии verify тикета: Decision-строка записана ✅ (memlog + Notes эпика), URL-схема задокументирована для 1.5 ✅; «пакет скачивается по публичной ссылке без авторизации» — невыполнимо без аккаунта, явно помечено HITL-паузой, локальный аналог (HTTP-отдача + sha256) PASS.

## Verification

**Commands:**
- `python tools/region-pack/verify.py out/region-spb-city-v1.zip` — PASS, exit 0 (выполнено 2026-10-04).
- Локальная HTTP-отдача + сверка sha256 архива — MATCH (выполнено 2026-10-04, см. Implementation Notes).

**Manual checks:**
- HITL: человек проходит `tools/region-pack/publish.md`; публичная ссылка отдаёт zip без авторизации (`curl -I` → HTTP 200), sha256 скачанного совпадает с записанным в manifest.json.
