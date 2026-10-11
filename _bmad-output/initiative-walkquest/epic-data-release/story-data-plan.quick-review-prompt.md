# Quick review — story-data-plan (6.1 Формат архива экспорта)

Запустить в отдельной сессии Kimi Work (проект C:\Users\Lenovo\Documents\kimi\walkapp), вставив промпт ниже целиком.

---

Read `C:\Users\Lenovo\Documents\kimi\walkapp\_bmad-output\initiative-walkquest\epic-data-release\story-data-plan.md` — the plan holding the acceptance criteria and intent — and the files its frontmatter `context:` lists. Read the agent instruction files (`AGENTS.md`, `CLAUDE.md`, or equivalents) at the repository root and in the directories the diff touches; those are the rules. The change under review is the new package `app/src/main/java/app/walkquest/data/transfer/` (5 files), the test `app/src/test/kotlin/app/walkquest/data/transfer/ExportArchiveTest.kt`, and the one-line visibility change in `app/src/main/java/app/walkquest/tracking/TrackHistory.kt` (private → internal for FILE_PREFIX/FILE_EXTENSION). This is a VCS-less workspace: read the files themselves as the diff.

Report unmet acceptance criteria, broken rules, and bugs. Each finding: the location, what goes wrong, and evidence checked against the surrounding code. Output a Markdown list of findings only — no severity, priority, or ranking.

Do not invoke any skill, and do not spawn subagents of your own — you are the reviewer. Return your findings as text in your final message.
