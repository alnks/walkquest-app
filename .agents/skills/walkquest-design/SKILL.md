---
name: walkquest-design
description: 'Design-focused BMad assistant that analyzes current workflow state and routes to the right design, layout, and UI/UX skills. Use when the user needs help with design decisions, screen/layout creation, mockup work, or UI validation in the WalkQuest project (walkapp).'
---

# WalkQuest Design

## Purpose

This skill is the design-specialized entry point for the **WalkQuest** project (workspace: `walkapp`). It extends `bmad` help logic with explicit awareness that **all design, layout, and UI/UX work must be handled through the ui-ux-pro-max plugin** (locally registered, with skills: `ui-ux-pro-max`, `design`, `design-system`, `ui-styling`, `brand`, `banner-design`, `slides`).

## Project Context (WalkQuest)

- Mobile app that turns walking into a quest: the map is hidden under a Fog that lifts only where the user has actually walked.
- **First platform: Android. First language: Russian.** All UI copy, typography choices, and layouts must work for Russian text (long words, Cyrillic).
- Core UI surfaces: map screen with fog overlay, walk tracking, achievements, leaderboard, regions list, points-of-interest cards.
- Design artifacts live in `_bmad-output/initiative-walkquest/ux-walkquest/` (`DESIGN.md`, `EXPERIENCE.md`) and must stay consistent with the PRD at `_bmad-output/initiative-walkquest/prd-walkquest/prd-walkquest.md`.
- Key UX constraints from the PRD: usable one-handed while walking, must not demand constant screen attention (user may be listening to music), honest progress display.

## When to Use

Invoke this skill whenever the user asks for:
- Help with the design workflow or next steps related to UI/UX
- Screen or layout creation / refactoring (map screen, achievements, onboarding, etc.)
- Mockup interpretation or handoff (Figma, screenshots, sketches)
- UI validation, review, accessibility, or quality checks
- Design system decisions (colors, typography, spacing, components, dark theme for the map)
- Mobile / adaptive layout guidance for Android

## Behavior

1. **Run bmad help logic first**
   Analyze the current BMad workflow state using this project's actual data sources:
   - `{project-root}/_bmad/config.toml` (core config: `project_name`, `output_folder`)
   - `{project-root}/_bmad/custom/config.user.toml` (user overrides)
   - Artifact files under `{project-root}/_bmad-output/` — currently the `initiative-walkquest/` tree (`prd-walkquest/`, `ux-walkquest/`)
   - Project knowledge and memory available to the current session

2. **Augment recommendations with design context**
   For every recommendation that touches UI, layout, or visual design, append:
   - A reminder to use `skill:ui-ux-pro-max`
   - The specific ui-ux-pro-max capability that applies (see mapping below)

3. **If the user's question is purely about design / layout / UI**
   Skip general BMad routing and surface ui-ux-pro-max as the primary tool.

## Design Task → Skill Mapping

| User Need | Primary Skill | Typical Invocation |
|-----------|---------------|-------------------|
| Analyze current workflow / find next step | bmad | "help" or "what's next" |
| Create or review a screen / layout | ui-ux-pro-max | "сделай экран карты", "сверстай экран ачивок" |
| Work with mockups (Figma / screenshots) | ui-ux-pro-max | "реализуй этот макет", "извлеки layout со скриншота" |
| Validate UI implementation | ui-ux-pro-max | "проверь экран на UX-проблемы", "проверь доступность" |
| Choose colors / fonts / style | ui-ux-pro-max | "подбери палитру для карты", "какой стиль подойдёт WalkQuest" |
| Design system / tokens | design-system (из ui-ux-pro-max) | "собери дизайн-токены", "заведи типографику под кириллицу" |
| Fix a UI bug or interaction | ui-ux-pro-max | "кнопка дёргается при нажатии", "layout скачет при загрузке" |
| Component design / refactor | ui-ux-pro-max | "сделай карточку точки интереса", "добавь bottom sheet" |

## Response Rules

1. **Always mention ui-ux-pro-max** when the topic involves:
   - Visual design decisions (style, palette, typography)
   - Android UI implementation (Jetpack Compose / XML layouts)
   - Component structure
   - Accessibility or UX quality (contrast on the map, touch targets while walking)
   - Mockup → code translation

2. **Respect WalkQuest constraints**: Android-first, Russian-first copy, map-centric dark-friendly UI, glanceable one-handed use while walking.

3. **Use the user's configured communication language** for the response (Russian by default in this project).

4. **Recommend a fresh context window** for each new skill invocation, so ui-ux-pro-max runs with full context.

5. **Offer a quick start** when a single skill is the obvious next step.

## Example Responses

**User:** "Что делать дальше?"
**System:**
- [Анализ] Инициатива WalkQuest: PRD в статусе final, UX-фаза начата — `DESIGN.md` и `EXPERIENCE.md` пока черновики.
- Следующий обязательный шаг: завершить UX-спецификацию (`bmad-ux` → Finalize для `DESIGN.md` / `EXPERIENCE.md`).
- **Для всех экранов и визуальных решений используй skill:ui-ux-pro-max** — он поможет с выбором стиля, палитры под карту, шрифтовой пары для кириллицы и компонентов.
- Быстрый старт: «Подбери палитру и типографику для экрана карты WalkQuest через ui-ux-pro-max».

**User:** "Проверь вёрстку экрана ачивок"
**System:**
- Этот запрос напрямую относится к UI/UX.
- Используй skill:ui-ux-pro-max для анализа и валидации вёрстки (контраст, размеры touch-зон, иерархия, доступность).
- Предоставь код экрана или укажи путь к файлу — я запущу ui-ux-pro-max с нужным контекстом.
