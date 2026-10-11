# Отложенные работы

- source_plan: `_bmad-output/initiative-walkquest/epic-map-foundation/story-track-recording-prototype-plan.md`
  summary: Синхронизировать чтение BitSet в FogRepository.openedIndices с мутацией в persistWith (снимок вне монитора @Synchronized).
  evidence: История 1.7 впервые даёт реальную конкурентность (HandlerThread сервиса vs IO-поток viewmodel); худший исход — моментальный снимок UI без части битов, самолечится следующей точкой/fogEpoch (low, defer из ревью 1.7). Код из 1.6, фикс data/fog был вне интента тикета; пересмотреть в epic-walk-tracking при миграции журнала в Room и переработке потоков записи.
