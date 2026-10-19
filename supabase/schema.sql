-- Brainrot friends: schema, row-level security and the functions the app calls.
-- Run once in the Supabase SQL editor. Safe to re-run: everything is create-or-replace / if-not-exists.
--
-- Model: every install signs in anonymously (Authentication → Sign In / Providers → Anonymous on).
-- Clients may only READ, and only their own rows and their friends'. Every write goes through a
-- security-definer function that touches the caller's rows only, so the anon key in the APK is harmless.

-- ============ Tables ============
create table if not exists public.profiles (
  id            uuid primary key references auth.users(id) on delete cascade,
  ig_username   text not null check (ig_username ~ '^[a-z0-9._]{1,30}$'),
  ig_full_name  text check (char_length(ig_full_name) <= 64),
  invite_code   text not null unique,
  created_at    timestamptz not null default now()
);

create table if not exists public.friendships (
  user_a     uuid not null references public.profiles(id) on delete cascade,
  user_b     uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_a, user_b),
  check (user_a < user_b)                    -- one row per pair, whoever redeemed
);
create index if not exists friendships_user_b_idx on public.friendships (user_b);

create table if not exists public.daily_stats (
  user_id    uuid not null references public.profiles(id) on delete cascade,
  day        date not null,                  -- the user's local date
  reels      int  not null default 0 check (reels between 0 and 10000),
  seconds    int  not null default 0 check (seconds between 0 and 86400),
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);

-- ============ Helpers ============
create or replace function public.gen_invite_code() returns text
language sql volatile set search_path = '' as $$
  select string_agg(substr('ABCDEFGHJKMNPQRSTUVWXYZ23456789', (get_byte(b, i) % 31) + 1, 1), '')
  from (select extensions.gen_random_bytes(8) as b) x, generate_series(0, 7) as i;
$$;

create or replace function public.is_friend(other uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.friendships
                 where user_a = least(auth.uid(), other)
                   and user_b = greatest(auth.uid(), other));
$$;

-- ============ RLS: read-only for clients ============
alter table public.profiles    enable row level security;
alter table public.friendships enable row level security;
alter table public.daily_stats enable row level security;

revoke all on public.profiles, public.friendships, public.daily_stats from anon, authenticated;
grant select on public.profiles, public.friendships, public.daily_stats to authenticated;

drop policy if exists "self or friend" on public.profiles;
create policy "self or friend" on public.profiles
  for select to authenticated using (id = auth.uid() or public.is_friend(id));

drop policy if exists "own friendships" on public.friendships;
create policy "own friendships" on public.friendships
  for select to authenticated using (auth.uid() in (user_a, user_b));

drop policy if exists "self or friend" on public.daily_stats;
create policy "self or friend" on public.daily_stats
  for select to authenticated using (user_id = auth.uid() or public.is_friend(user_id));

-- ============ Functions the app calls ============
create or replace function public.upsert_profile(p_username text, p_full_name text)
returns public.profiles
language plpgsql security definer set search_path = '' as $$
declare r public.profiles;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  insert into public.profiles (id, ig_username, ig_full_name, invite_code)
  values (auth.uid(), lower(trim(p_username)), nullif(trim(p_full_name), ''), public.gen_invite_code())
  on conflict (id) do update
    set ig_username = excluded.ig_username, ig_full_name = excluded.ig_full_name
  returning * into r;
  return r;
end $$;

-- Absolute totals, not increments: retries and out-of-order sends can never double-count.
create or replace function public.push_stats(p_day date, p_reels int, p_seconds int)
returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  if p_day not between current_date - 2 and current_date + 1 then
    raise exception 'day out of range';
  end if;
  insert into public.daily_stats as d (user_id, day, reels, seconds)
  values (auth.uid(), p_day, p_reels, p_seconds)
  on conflict (user_id, day) do update
    set reels      = greatest(d.reels,   excluded.reels),
        seconds    = greatest(d.seconds, excluded.seconds),
        updated_at = now()
    where excluded.reels > d.reels or excluded.seconds > d.seconds;  -- no-op writes skip realtime
end $$;

create or replace function public.redeem_invite(p_code text)
returns table (friend_id uuid, ig_username text, ig_full_name text)
language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); other uuid;
begin
  if me is null then raise exception 'not signed in'; end if;
  if not exists (select 1 from public.profiles p where p.id = me) then
    raise exception 'connect your Instagram first';
  end if;
  select p.id into other from public.profiles p where p.invite_code = upper(trim(p_code));
  if other is null then raise exception 'invalid code'; end if;
  if other = me   then raise exception 'that is your own code'; end if;
  insert into public.friendships (user_a, user_b)
  values (least(me, other), greatest(me, other)) on conflict do nothing;
  return query select p.id, p.ig_username, p.ig_full_name from public.profiles p where p.id = other;
end $$;

create or replace function public.remove_friend(p_friend uuid)
returns void
language sql security definer set search_path = '' as $$
  delete from public.friendships
  where user_a = least(auth.uid(), p_friend) and user_b = greatest(auth.uid(), p_friend);
$$;

-- Friends with each one's most recent day.
create or replace function public.friends_stats()
returns table (friend_id uuid, ig_username text, ig_full_name text,
               day date, reels int, seconds int, updated_at timestamptz)
language sql stable security invoker set search_path = '' as $$
  select p.id, p.ig_username, p.ig_full_name, s.day, coalesce(s.reels, 0), coalesce(s.seconds, 0), s.updated_at
  from public.friendships f
  join public.profiles p
    on p.id = case when f.user_a = auth.uid() then f.user_b else f.user_a end
  left join lateral (
    select d.day, d.reels, d.seconds, d.updated_at from public.daily_stats d
    where d.user_id = p.id order by d.day desc limit 1
  ) s on true
  where auth.uid() in (f.user_a, f.user_b)
  order by coalesce(s.reels, 0) desc;
$$;

revoke execute on all functions in schema public from public, anon;
grant  execute on function public.upsert_profile(text, text), public.push_stats(date, int, int),
                           public.redeem_invite(text), public.remove_friend(uuid),
                           public.friends_stats(),
                           public.is_friend(uuid) to authenticated;   -- the RLS policies call it

-- ============ Realtime ============
-- Subscribers only receive rows their RLS select policy allows: their own and their friends'.
do $$ begin
  alter publication supabase_realtime add table public.daily_stats;
exception when duplicate_object then null; end $$;
