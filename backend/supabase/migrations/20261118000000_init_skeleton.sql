-- CET 백엔드 뼈대 스키마 (Supabase / PostgreSQL)
-- 기준: 2팀 요구사항 명세서 (US, GR-4, DC-6, RK 일부). 명세 확정 전이므로
-- 바뀌지 않을 핵심 테이블만 둔다. 보상·피드백·코스튬·집계는 이후 마이그레이션에서 추가한다.
--
-- 사용자 구분: Supabase 익명 로그인 (signInAnonymously)
--   - 사용자에게 로그인 화면은 없다. 앱이 처음 실행될 때 자동으로 익명 세션을 만든다.
--   - 세션 토큰은 기기에 저장되고, 모든 요청은 서버가 확인한 auth.uid()로 사용자를 판단한다.
--   - 사용자 UUID(auth.uid())는 공개돼도 된다 (공용 API 2의 sender로 사용).
--   - 대시보드 Authentication → Sign In / Providers → "Allow anonymous sign-ins"를 켜야 한다.

-- ============================================================
-- 1. 사용자 (US-1, US-1-1, US-1-2)
-- ============================================================
create table public.users (
    id          uuid        primary key references auth.users(id) on delete cascade,
    nickname    text        not null unique
                            check (char_length(nickname) between 2 and 10),
    created_at  timestamptz not null default now()
);

comment on table public.users is '익명 로그인 사용자 + 표시용 닉네임. id = auth.uid()';

-- ============================================================
-- 2. 펫 (US-1-3, GR-4) - 사용자당 1마리
-- ============================================================
create table public.pets (
    user_id        uuid        primary key references public.users(id) on delete cascade,
    level          integer     not null default 1 check (level >= 1),
    feed_in_level  integer     not null default 0 check (feed_in_level >= 0),  -- 현재 레벨에서 먹인 먹이 수
    stage          text        not null default 'egg'
                               check (stage in ('egg', 'baby', 'hatchling', 'adult')),
    food           integer     not null default 0 check (food >= 0),          -- 보유 먹이
    updated_at     timestamptz not null default now()
);

comment on column public.pets.stage is '알 / 새끼 / 해츨링 / 성체. 진화 레벨(3, 6, 10)은 임시값이라 아직 DB에서 계산하지 않음';

-- ============================================================
-- 3. 수집 기록 (DC-6, DC-6-1, DC-7)
-- ============================================================
create table public.collection_records (
    id                bigint       generated always as identity primary key,
    user_id           uuid         not null references public.users(id),
    sensor_name       text         not null,                -- 연구실 이름으로 변환된 값 (예: 'sensor 07')
    temperature       numeric(5,2) check (temperature between -30 and 50),
    humidity          numeric(5,2) check (humidity between 0 and 100),  -- 연구실 센서, opensrc3·4는 null
    aqi               integer,
    tvoc              integer,
    co2               integer      check (co2 between 400 and 5000),    -- 연구실: CO2, 오픈소스: eCO2
    rssi              integer      not null,
    measured_at       timestamptz  not null,
    public_server_id  text         unique,                 -- 공용 API 2가 돌려준 id. 테스트 모드면 null
    is_shadow_area    boolean      not null default false, -- 음영지역 여부 (SM-3)
    is_test           boolean      not null default false, -- 테스트 모드 (DC-7)
    created_at        timestamptz  not null default now(),

    -- 실제 수집은 반드시 공용 서버 id가 있어야 한다
    check (is_test or public_server_id is not null)
);

create index collection_records_user_idx   on public.collection_records (user_id, created_at desc);
create index collection_records_sensor_idx on public.collection_records (sensor_name, measured_at desc);

comment on column public.collection_records.public_server_id is
    '재전송돼도 같은 id면 한 번만 저장된다 (unique). 보상 중복 방지의 기준 키로도 쓴다';

-- 수집 기록은 추가만 허용 (DC-6-1)
create function public.forbid_collection_record_change()
returns trigger
language plpgsql
as $$
begin
    raise exception '수집 기록은 수정하거나 삭제할 수 없습니다';
end;
$$;

create trigger collection_records_append_only
    before update or delete on public.collection_records
    for each row execute function public.forbid_collection_record_change();

-- ============================================================
-- 4. 접근 제어 (RLS)
-- 본인 데이터만 읽을 수 있다. 쓰기는 테이블에 직접 하지 못하고 아래 RPC 함수로만 한다.
-- 다른 사람의 정보는 랭킹 함수로 닉네임·레벨·진화 단계만 볼 수 있다 (UUID 비공개).
-- ============================================================
alter table public.users              enable row level security;
alter table public.pets               enable row level security;
alter table public.collection_records enable row level security;

create policy users_read_own on public.users
    for select to authenticated using (id = auth.uid());

create policy pets_read_own on public.pets
    for select to authenticated using (user_id = auth.uid());

create policy collection_records_read_own on public.collection_records
    for select to authenticated using (user_id = auth.uid());

-- ============================================================
-- 5. RPC 함수
-- 모든 함수는 앱이 보낸 사용자 ID를 받지 않고, 서버가 확인한 auth.uid()를 쓴다.
-- ============================================================

-- 공통: 세션이 없으면 거절
create function public.require_uid()
returns uuid
language plpgsql
stable
as $$
begin
    if auth.uid() is null then
        raise exception '인증 세션이 없습니다' using errcode = '28000';
    end if;
    return auth.uid();
end;
$$;

-- 닉네임 사용 가능 여부 (US-1-1)
create function public.is_nickname_available(p_nickname text)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select not exists (select 1 from public.users where nickname = p_nickname);
$$;

-- 닉네임 등록 + 펫 생성을 한 트랜잭션으로 처리 (US-1, US-1-3)
-- 닉네임 중복이면 unique_violation(23505) → 앱에서 '이미 사용 중인 닉네임' 표시
-- 이미 등록한 사용자가 다시 호출해도 23505 (users.id 중복)
create function public.register_user(p_nickname text)
returns public.pets
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := public.require_uid();
    v_pet public.pets;
begin
    insert into public.users (id, nickname) values (v_uid, p_nickname);
    insert into public.pets (user_id) values (v_uid) returning * into v_pet;
    return v_pet;
end;
$$;

-- 내 정보 불러오기 (US-2-1, US-2-2)
-- 앱 시작 시 호출. 결과가 없으면 아직 닉네임을 등록하지 않은 것 → 닉네임 화면으로 이동
create function public.get_my_profile()
returns table (
    user_id uuid, nickname text, level integer, feed_in_level integer, stage text, food integer
)
language sql
stable
security definer
set search_path = public
as $$
    select u.id, u.nickname, p.level, p.feed_in_level, p.stage, p.food
    from public.users u
    join public.pets p on p.user_id = u.id
    where u.id = auth.uid();
$$;

-- 수집 기록 저장 (DC-6). 같은 public_server_id 재전송은 기존 기록을 그대로 돌려준다.
create function public.add_collection_record(
    p_sensor_name      text,
    p_temperature      numeric,
    p_humidity         numeric,
    p_aqi              integer,
    p_tvoc             integer,
    p_co2              integer,
    p_rssi             integer,
    p_measured_at      timestamptz,
    p_public_server_id text,
    p_is_shadow_area   boolean,
    p_is_test          boolean default false
)
returns public.collection_records
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid    uuid := public.require_uid();
    v_record public.collection_records;
begin
    insert into public.collection_records (
        user_id, sensor_name, temperature, humidity, aqi, tvoc, co2, rssi,
        measured_at, public_server_id, is_shadow_area, is_test
    ) values (
        v_uid, p_sensor_name, p_temperature, p_humidity, p_aqi, p_tvoc, p_co2, p_rssi,
        p_measured_at, p_public_server_id, p_is_shadow_area, p_is_test
    )
    on conflict (public_server_id) do nothing
    returning * into v_record;

    if v_record.id is null then
        -- 재전송: 본인 기록이면 기존 기록 반환, 남의 기록이면 거절
        select * into v_record
        from public.collection_records
        where public_server_id = p_public_server_id
          and user_id = v_uid;

        if v_record.id is null then
            raise exception '다른 사용자의 수집 기록입니다' using errcode = '42501';
        end if;
    end if;

    return v_record;
end;
$$;

-- 랭킹 (RK-1). UUID 없이 닉네임·레벨·진화 단계만 공개한다.
-- 동점 순서 기준은 미정 → 임시로 현재 레벨에서 먹인 수, 가입 순
create function public.get_ranking(p_limit integer default 50)
returns table (
    rank bigint, nickname text, level integer, stage text, is_me boolean
)
language sql
stable
security definer
set search_path = public
as $$
    select
        row_number() over (order by p.level desc, p.feed_in_level desc, u.created_at asc),
        u.nickname, p.level, p.stage, u.id = auth.uid()
    from public.users u
    join public.pets p on p.user_id = u.id
    order by 1
    limit least(greatest(p_limit, 1), 100);
$$;

-- ============================================================
-- 6. 권한: 익명 로그인 사용자도 authenticated 역할이다. anon(세션 없음)은 아무것도 못 한다.
-- ============================================================
revoke all on function public.require_uid()                     from public, anon;
revoke all on function public.is_nickname_available(text)       from public, anon;
revoke all on function public.register_user(text)               from public, anon;
revoke all on function public.get_my_profile()                  from public, anon;
revoke all on function public.add_collection_record(text, numeric, numeric, integer, integer, integer, integer, timestamptz, text, boolean, boolean) from public, anon;
revoke all on function public.get_ranking(integer)              from public, anon;

grant execute on function public.require_uid()                  to authenticated;
grant execute on function public.is_nickname_available(text)    to authenticated;
grant execute on function public.register_user(text)            to authenticated;
grant execute on function public.get_my_profile()               to authenticated;
grant execute on function public.add_collection_record(text, numeric, numeric, integer, integer, integer, integer, timestamptz, text, boolean, boolean) to authenticated;
grant execute on function public.get_ranking(integer)           to authenticated;
