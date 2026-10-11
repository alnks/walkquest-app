# Deferred Work — epic-leaderboard

## Итоги кодовой части — 2026-10-09 (эпик 5)

- Домен: LeaderboardSnapshot/RegionAggregate (только агрегаты, AD-3), NicknamePolicy
  (3..24, буквы/цифры/_/-). Настройки: leaderboardNickname/leaderboardVisible.
- data: LeaderboardApi — plain REST к PostgREST на HttpURLConnection (новых
  зависимостей нет, пин spine 1.5): upsertProfile, publishAggregates, cityTop,
  createInvite, acceptInvite (RPC). LeaderboardOutbox (outbox.json) — офлайн-очередь.
  regionAggregates — суммы по regionIds истории.
- D4/D5 добавлены в каталог движка (40 достижений); выдача через перепроверку
  (onJoined/onFriendAccepted → runAchievementCheck).
- UI: пятая вкладка «Рейтинг» (ник при первом открытии, невидимый режим,
  «Город»/«Друзья», invite-коды). Сервис публикует агрегаты после прогулки (FR-19).
- Миграции: supabase/migrations/20261009000000_leaderboard.sql (таблицы, view top,
  RLS, RPC accept_invite). Конфиг: supabase/config.toml.

### Проверки
- 362 JVM-теста зелёных (принудительный перезапуск), assembleRelease PASS.

### БЛОКЕР HITL (владелец) — создание проекта
supabase orgs list вернул пусто: у авторизованного аккаунта нет организации.
Шаги (после создания org/project на dashboard или через CLI):
  1) supabase projects create walkquest --org-id <ORG_ID> --db-password <PWD> --region eu-central-1
  2) supabase link --project-ref <REF>
  3) supabase db push   (применит миграцию: таблицы+RLS+RPC)
  4) Project Settings → API: anon key → в local.properties:
       supabase.url=https://<REF>.supabase.co
       supabase.anon.key=<anon-key>
  5) Проверка RLS (Done when 6): попытка PATCH чужой строки → 401/403.

### Остатки
- Keep-alive free-tier (пауза после 7 дней неактивности) — cron-задача владельца.
- Вкладка «Друзья» сейчас показывает общий топ (friend-фильтр вьюшкой/запросом —
  после первых друзей).
- Проверка трафика AD-3 (Done when 5) — на эмуляторе/устройстве при прогоне.

## Обновление 2026-10-11: проект создан и инициализирован

- Проект: jxfegrxylimrmbhcflqa (org tijvmobegaannjeibuxh, регион eu-west-1 — ближайший
  доступный при создании; миграция на eu-central-1 при желании — recreate).
- Миграция применена через Management API (POST /v1/projects/{ref}/database/query) —
  пароль Postgres НЕ потребовался и не сбрасывался.
- Верификация: таблицы aggregates/friendships/invites/profiles + view top созданы;
  RLS включена на всех таблицах; RPC accept_invite на месте; anon читает top (200),
  anon-запись в profiles заблокирована (401). Done when 6 — подтверждено снаружи.
- supabase_migrations.schema_migrations помечена — db push не повторит.
- local.properties: supabase.url + supabase.anon.key записаны (в .gitignore).
- Пароль Postgres остался неизвестен (автогенерирован при создании, не сохранён).
  Если понадобится прямой доступ: Dashboard → Database → Reset password.

### Остатки (блокеры полевые/HITL)
- Проверка трафика AD-3 (Done when 5) — на устройстве/эмуляторе.
- Keep-alive free-tier — cron владельца.
- Вкладка «Друзья» показывает общий топ; фильтр по friendships — когда появятся друзья.
