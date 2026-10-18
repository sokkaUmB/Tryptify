-- Theme Store
-- ===========
--
-- The tables behind the Theme Store: packs people publish, the likes on them,
-- the downloads counted once per person, and reports. Read and written by
-- ThemeStoreRepository; a pack's `pack` column is ThemePackCodec's JSON and
-- only the app parses it.
--
-- Every part of the store needs an account: nothing here is granted to anon.
-- The counters (likes, downloads) are kept by the database, never by the
-- client: the client cannot insert or update those columns at all.
--
-- Idempotent, so it is safe to re-run. Run it in the Supabase SQL editor for
-- the project that owns the app.

-- ── Packs ───────────────────────────────────────────────────────────────────

create table if not exists public.theme_packs (
    id           uuid        primary key default gen_random_uuid(),
    user_id      uuid        not null default auth.uid()
                             references auth.users (id) on delete cascade,
    author_name  text        not null default ''
                             check (char_length(author_name) <= 40),
    name         text        not null
                             check (char_length(btrim(name)) between 1 and 40),
    description  text        not null default ''
                             check (char_length(description) <= 280),
    -- ThemePart names, for the store's "what's in it" row without parsing the pack.
    parts        text[]      not null default '{}'
                             check (cardinality(parts) <= 8),
    -- The pack's accent and page colours as ARGB, so a card can be painted
    -- without downloading the pack.
    accent       integer     not null default 0,
    background   integer     not null default 0,
    pack         text        not null
                             check (octet_length(pack) <= 65536),
    -- false: only its author and people with its share code can open it.
    listed       boolean     not null default true,
    share_code   text        not null unique
                             default upper(substr(md5(gen_random_uuid()::text), 1, 8)),
    likes        integer     not null default 0,
    downloads    integer     not null default 0,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create index if not exists theme_packs_popular on public.theme_packs (likes desc, created_at desc) where listed;
create index if not exists theme_packs_newest  on public.theme_packs (created_at desc) where listed;
create index if not exists theme_packs_author  on public.theme_packs (user_id);

create or replace function public.theme_packs_touch()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_theme_packs_touch on public.theme_packs;
create trigger trg_theme_packs_touch
    before update on public.theme_packs
    for each row execute function public.theme_packs_touch();

-- At most 50 packs per person: a store, not free storage.
create or replace function public.theme_packs_limit()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if (select count(*) from public.theme_packs where user_id = new.user_id) >= 50 then
        raise exception 'theme pack limit reached' using errcode = 'P0001';
    end if;
    return new;
end;
$$;

drop trigger if exists trg_theme_packs_limit on public.theme_packs;
create trigger trg_theme_packs_limit
    before insert on public.theme_packs
    for each row execute function public.theme_packs_limit();

alter table public.theme_packs enable row level security;

drop policy if exists "theme_packs_select" on public.theme_packs;
create policy "theme_packs_select"
    on public.theme_packs for select to authenticated
    using (listed or user_id = (select auth.uid()));

drop policy if exists "theme_packs_insert_own" on public.theme_packs;
create policy "theme_packs_insert_own"
    on public.theme_packs for insert to authenticated
    with check (user_id = (select auth.uid()));

drop policy if exists "theme_packs_update_own" on public.theme_packs;
create policy "theme_packs_update_own"
    on public.theme_packs for update to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

drop policy if exists "theme_packs_delete_own" on public.theme_packs;
create policy "theme_packs_delete_own"
    on public.theme_packs for delete to authenticated
    using (user_id = (select auth.uid()));

-- Column grants: the author writes what they wrote, the database the rest.
revoke all on public.theme_packs from anon, authenticated;
grant select, delete on public.theme_packs to authenticated;
grant insert (author_name, name, description, parts, accent, background, pack, listed)
    on public.theme_packs to authenticated;
grant update (author_name, name, description, parts, accent, background, pack, listed)
    on public.theme_packs to authenticated;

-- An unlisted pack, opened by the code its author shared.
create or replace function public.theme_pack_by_code(p_code text)
returns setof public.theme_packs
language sql
stable
security definer
set search_path = ''
as $$
    select *
      from public.theme_packs
     where share_code = upper(btrim(p_code))
       and (select auth.uid()) is not null
     limit 1;
$$;

revoke execute on function public.theme_pack_by_code(text) from public, anon;
grant  execute on function public.theme_pack_by_code(text) to authenticated;

-- ── Likes ───────────────────────────────────────────────────────────────────

create table if not exists public.theme_likes (
    pack_id     uuid        not null references public.theme_packs (id) on delete cascade,
    user_id     uuid        not null default auth.uid()
                            references auth.users (id) on delete cascade,
    created_at  timestamptz not null default now(),
    primary key (pack_id, user_id)
);

create index if not exists theme_likes_user on public.theme_likes (user_id);

alter table public.theme_likes enable row level security;

drop policy if exists "theme_likes_select_own" on public.theme_likes;
create policy "theme_likes_select_own"
    on public.theme_likes for select to authenticated
    using (user_id = (select auth.uid()));

drop policy if exists "theme_likes_insert_own" on public.theme_likes;
create policy "theme_likes_insert_own"
    on public.theme_likes for insert to authenticated
    with check (user_id = (select auth.uid()));

drop policy if exists "theme_likes_delete_own" on public.theme_likes;
create policy "theme_likes_delete_own"
    on public.theme_likes for delete to authenticated
    using (user_id = (select auth.uid()));

revoke all on public.theme_likes from anon, authenticated;
grant select, delete on public.theme_likes to authenticated;
grant insert (pack_id) on public.theme_likes to authenticated;

create or replace function public.theme_likes_count()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if tg_op = 'INSERT' then
        update public.theme_packs set likes = likes + 1 where id = new.pack_id;
        return new;
    else
        update public.theme_packs set likes = greatest(likes - 1, 0) where id = old.pack_id;
        return old;
    end if;
end;
$$;

drop trigger if exists trg_theme_likes_count on public.theme_likes;
create trigger trg_theme_likes_count
    after insert or delete on public.theme_likes
    for each row execute function public.theme_likes_count();

-- ── Downloads ───────────────────────────────────────────────────────────────
--
-- Counted once per person and pack, so reapplying a theme does not inflate
-- it. Nobody reads the table directly; the function below is the only door.

create table if not exists public.theme_downloads (
    pack_id     uuid        not null references public.theme_packs (id) on delete cascade,
    user_id     uuid        not null references auth.users (id) on delete cascade,
    created_at  timestamptz not null default now(),
    primary key (pack_id, user_id)
);

alter table public.theme_downloads enable row level security;
revoke all on public.theme_downloads from anon, authenticated;

create or replace function public.theme_pack_downloaded(p_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    uid uuid := (select auth.uid());
begin
    if uid is null then
        return;
    end if;
    insert into public.theme_downloads (pack_id, user_id)
    values (p_id, uid)
    on conflict do nothing;
    if found then
        update public.theme_packs set downloads = downloads + 1 where id = p_id;
    end if;
end;
$$;

revoke execute on function public.theme_pack_downloaded(uuid) from public, anon;
grant  execute on function public.theme_pack_downloaded(uuid) to authenticated;

-- ── Reports ─────────────────────────────────────────────────────────────────

create table if not exists public.theme_reports (
    id          bigint      generated always as identity primary key,
    pack_id     uuid        not null references public.theme_packs (id) on delete cascade,
    user_id     uuid        not null default auth.uid()
                            references auth.users (id) on delete cascade,
    reason      text        not null default ''
                            check (char_length(reason) <= 280),
    created_at  timestamptz not null default now(),
    unique (pack_id, user_id)
);

alter table public.theme_reports enable row level security;

drop policy if exists "theme_reports_insert_own" on public.theme_reports;
create policy "theme_reports_insert_own"
    on public.theme_reports for insert to authenticated
    with check (user_id = (select auth.uid()));

revoke all on public.theme_reports from anon, authenticated;
grant insert (pack_id, reason) on public.theme_reports to authenticated;

-- Trigger functions are called by their triggers, never by anyone directly.
revoke execute on function public.theme_packs_touch()  from public, anon, authenticated;
revoke execute on function public.theme_packs_limit()  from public, anon, authenticated;
revoke execute on function public.theme_likes_count()  from public, anon, authenticated;
