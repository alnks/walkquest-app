# Quick review — story-restore-plan (6.2 Импорт: restore в хранилища)

Запустить в отдельной сессии Kimi Work (проект C:\Users\Lenovo\Documents\kimi\walkapp), вставив промпт ниже целиком.

---

Read `C:\Users\Lenovo\Documents\kimi\walkapp\_bmad-output\initiative-walkquest\epic-data-release\story-restore-plan.md` — the plan holding the acceptance criteria and intent — and the files its frontmatter `context:` lists. Read the agent instruction files (`AGENTS.md`, `CLAUDE.md`, or equivalents) at the repository root and in the directories the change touches; those are the rules. The change under review is `app/src/main/java/app/walkquest/data/transfer/ImportApplier.kt` and `ImportTargets.kt` (new), plus the test `app/src/test/kotlin/app/walkquest/data/transfer/ImportApplierTest.kt` (new). The sibling files ExportArchiveWriter/ExportArchiveReader/ExportManifest/ExportSources/ExportResult are the previous story (6.1) — read them only as context. This is a VCS-less workspace: read the files themselves as the diff.

Report unmet acceptance criteria, broken rules, and bugs. Each finding: the location, what goes wrong, and evidence checked against the surrounding code. Pay special attention to: path-traversal safety of section names, atomicity claims (temp + rename), and whether a skipped section can partially overwrite existing data. Output a Markdown list of findings only — no severity, priority, or ranking.

Do not invoke any skill, and do not spawn subagents of your own — you are the reviewer. Return your findings as text in your final message.
