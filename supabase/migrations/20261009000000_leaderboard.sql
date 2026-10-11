-- WalkQuest: миграции Supabase (эпик 5, FR-19, AD-7).
-- Принцип AD-3: на сервере только ник и агрегаты. Треков/координат/истории здесь НЕТ.

create table if not exists public.profiles (
  id uuid primary key default auth.uid(),
  nickname text not null unique check (char_length(nickname) between 3 and 24),
  created_at timestamptz not null default now()
);

create table if not exists public.aggregates (
  user_id uuid not null default auth.uid() references public.profiles(id) on delete cascade,
  region_id text not null,
  distance_meters double precision not null default 0 check (distance_meters >= 0),
  fraction double precision not null default 0 check (fraction between 0 and 1),
  updated_at timestamptz not null default now(),
  primary key (user_id, region_id)
);

create table if not exists public.invites (
  code text primary key,
  inviter uuid not null default auth.uid() references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now()
);

create table if not exists public.friendships (
  user_id uuid not null references public.profiles(id) on delete cascade,
  friend_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, friend_id),
  check (user_id <> friend_id)
);

-- Представление топа: только ник + агрегаты (AD-3).
create or replace view public.top as
  select p.nickname, a.region_id, a.distance_meters, a.fraction
  from public.aggregates a
  join public.profiles p on p.id = a.user_id;

-- RLS (Done when 6): писать свои строки, читать топ всем, дружбу — свою.
alter table public.profiles enable row level security;
alter table public.aggregates enable row level security;
alter table public.invites enable row level security;
alter table public.friendships enable row level security;

create policy "own profile" on public.profiles
  for all using (auth.uid() = id) with check (auth.uid() = id);
create policy "own aggregates" on public.aggregates
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own invites" on public.invites
  for all using (auth.uid() = inviter) with check (auth.uid() = inviter);
create policy "own friendships" on public.friendships
  for select using (auth.uid() = user_id or auth.uid() = friend_id);
-- Вставка дружбы — только через RPC (security definer) ниже.

-- Принятие приглашения: двусторонняя дружба (D5 отмечается на клиенте).
create or replace function public.accept_invite(code text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  inviter_id uuid;
  me uuid := auth.uid();
begin
  if me is null then return false; end if;
  select inviter into inviter_id from public.invites where invites.code = accept_invite.code;
  if inviter_id is null or inviter_id = me then return false; end if;
  insert into public.friendships (user_id, friend_id) values (me, inviter_id)
    on conflict do nothing;
  insert into public.friendships (user_id, friend_id) values (inviter_id, me)
    on conflict do nothing;
  delete from public.invites where invites.code = accept_invite.code;
  return true;
end;
$$;

grant execute on function public.accept_invite(text) to anon, authenticated;
