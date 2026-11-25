-- 계약서 v1.2 기준으로 DB 재구성 (1/5): 기존 구조 정리 + 사용자·펫 + 닉네임 접속
--
-- 기준: docs/api-contract.md §02, §08, §13, §14 / 요구사항 US-1-1 ~ US-2-1, GR-4, CS-4
--
-- 바뀐 점
--   - 익명 로그인을 쓰지 않는다. 닉네임으로 접속하고, 함수가 userId 를 직접 받는다 (§08, 본인 확인 없음).
--   - 앱은 Publishable key(anon 역할)로 함수만 호출한다. 표는 직접 읽거나 쓸 수 없다.
--   - 실패는 HTTP 4xx + { "message": "<계약서 오류 코드>", "details": "<설명>" }.
--     Java 어댑터가 message 를 TeamDbErrorCode 로 바꿔 { ok:false, error } 로 감싼다.

-- ============================================================
-- 0. 이전 구조(20261118 ~ 20261122) 정리. 테스트 데이터만 있었다.
-- ============================================================
drop function if exists public.is_nickname_available(text);
drop function if exists public.register_user(text);
drop function if exists public.register_user(uuid, text);
drop function if exists public.login_by_nickname(text);
drop function if exists public.get_my_profile();
drop function if exists public.add_collection_record(text, numeric, numeric, integer, integer, integer, integer, timestamptz, text, boolean, boolean);
drop function if exists public.get_ranking(integer);
drop function if exists public.get_my_rank();
drop function if exists public.get_my_collection_stats();
drop function if exists public.submit_feedback(bigint, text, text, text[], text);
drop function if exists public.feed_pet(uuid);
drop function if exists public.require_uid();

drop table if exists public.feed_requests cascade;
drop table if exists public.feedbacks cascade;
drop table if exists public.feedback_discomfort_options cascade;
drop table if exists public.reward_history cascade;
drop table if exists public.first_collections cascade;
drop table if exists public.collection_stats cascade;
drop table if exists public.collection_records cascade;
drop table if exists public.pets cascade;
drop table if exists public.users cascade;
drop table if exists public.settings cascade;

drop function if exists public.forbid_collection_record_change();
drop schema if exists private cascade;

-- ============================================================
-- 1. 내부 도우미 (API로 노출되지 않는 private 스키마)
-- ============================================================
create schema private;
revoke all on schema private from public, anon, authenticated;

-- 실패 응답: HTTP p_status, message = 계약서 오류 코드
create function private.app_error(p_code text, p_status integer, p_detail text)
returns void
language plpgsql
as $$
begin
    raise exception using
        errcode = 'PT' || p_status::text,
        message = p_code,
        detail  = p_detail;
end;
$$;

-- 하루 경계: Asia/Seoul 00:00 (§02)
create function private.kst_date(p_ts timestamptz)
returns date
language sql
immutable
as $$
    select (p_ts at time zone 'Asia/Seoul')::date;
$$;

-- 성장 단계: 알 1~5, 새끼 6~10, 해츨링 11~15, 성체 16 이상 (§08, §11)
create function private.stage_for_level(p_level bigint)
returns text
language sql
immutable
as $$
    select case
        when p_level >= 16 then 'ADULT'
        when p_level >= 11 then 'HATCHLING'
        when p_level >= 6  then 'BABY'
        else 'EGG'
    end;
$$;

-- 닉네임 정규화: 양끝 공백 제거 + NFC (§08). 길이는 코드포인트 수, 대소문자 구분.
create function private.normalize_nickname(p_nickname text)
returns text
language sql
immutable
as $$
    select normalize(regexp_replace(coalesce(p_nickname, ''), '^\s+|\s+$', '', 'g'), NFC);
$$;

-- 수정·삭제 금지 트리거 (§14). 사용자 계정 삭제로 함께 지워지는 경우만 허용.
create function private.forbid_change()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE'
       and not exists (select 1 from public.users where user_id = old.user_id) then
        return old;
    end if;
    raise exception '저장된 기록은 수정하거나 삭제할 수 없습니다 (%)', tg_table_name;
end;
$$;

-- ============================================================
-- 2. 센서 지점 34곳 (§02 sensorNo 1~34)
-- ============================================================
create table public.sensors (
    sensor_no                  integer primary key check (sensor_no between 1 and 34),
    site_name                  text    not null,
    is_course_sensor           boolean not null default false,  -- 수업용 opensrc1~5 (07·15·31·32·33)
    temp_humidity_unavailable  boolean not null default false   -- 31·32 수리 전: 온습도 저장·조회 안 함
);

insert into public.sensors (sensor_no, site_name, is_course_sensor, temp_humidity_unavailable)
select n,
       'sensor ' || lpad(n::text, 2, '0'),
       n in (7, 15, 31, 32, 33),
       n in (31, 32)
from generate_series(1, 34) as n;

comment on table public.sensors is '등록 센서 1~34. 지점명이 정해지면 site_name 을 수정한다 (기본값 sensor NN)';

-- ============================================================
-- 3. 사용자·펫·장식 (§08, §14)
-- ============================================================
create table public.users (
    user_id       uuid        primary key default gen_random_uuid(),
    nickname      text        not null unique,             -- 정규화된 닉네임
    normal_total  bigint      not null default 0 check (normal_total >= 0),  -- 승인된 일반 기여 누계
    shadow_total  bigint      not null default 0 check (shadow_total >= 0),  -- 승인된 음영 기여 누계
    created_at    timestamptz not null default now(),
    check (char_length(nickname) between 2 and 10)
);

create table public.owned_costumes (
    user_id      uuid        not null references public.users(user_id) on delete cascade,
    costume      text        not null check (costume in ('SPROUT', 'RIBBON', 'STAR', 'CROWN')),
    acquired_at  timestamptz not null default now(),
    primary key (user_id, costume)
);

create table public.pets (
    user_id           uuid        primary key references public.users(user_id) on delete cascade,
    level             bigint      not null default 1 check (level >= 1),
    progress          bigint      not null default 0 check (progress >= 0 and progress <= level),
    food_balance      bigint      not null default 0 check (food_balance >= 0),
    draw_count        bigint      not null default 0 check (draw_count >= 0),
    equipped_costume  text,
    updated_at        timestamptz not null default now(),
    -- 착용 장식은 반드시 보유 중이어야 한다
    foreign key (user_id, equipped_costume) references public.owned_costumes(user_id, costume)
);

comment on column public.pets.progress is '현재 레벨에서 먹인 개수. level+1 에 도달하면 레벨업하고 0으로';

-- 등록 요청 기록: 같은 requestId 재전송 시 중복 생성 방지 (§13)
create table public.register_requests (
    request_id  uuid        primary key,
    nickname    text        not null,
    user_id     uuid        not null references public.users(user_id) on delete cascade,
    created_at  timestamptz not null default now()
);

-- ============================================================
-- 4. 접근 제어: 앱은 표에 직접 접근하지 못하고 아래 함수만 호출한다
-- ============================================================
alter table public.sensors           enable row level security;
alter table public.users             enable row level security;
alter table public.owned_costumes    enable row level security;
alter table public.pets              enable row level security;
alter table public.register_requests enable row level security;

revoke all on public.sensors, public.users, public.owned_costumes, public.pets, public.register_requests
    from anon, authenticated;

-- ============================================================
-- 5. 응답 JSON 만들기 (계약서 필드명 그대로)
-- ============================================================
create function private.pet_state(p_user_id uuid)
returns jsonb
language sql
stable
set search_path = public
as $$
    select jsonb_build_object(
        'level',           p.level,
        'progress',        p.progress,
        'foodBalance',     p.food_balance,
        'stage',           private.stage_for_level(p.level),
        'drawCount',       p.draw_count,
        'nextDrawCost',    24 + p.draw_count,
        'ownedCostumes',   coalesce((
            select jsonb_agg(c.costume order by c.acquired_at, c.costume)
            from public.owned_costumes c where c.user_id = p.user_id
        ), '[]'::jsonb),
        'equippedCostume', p.equipped_costume
    )
    from public.pets p
    where p.user_id = p_user_id;
$$;

create function private.user_state(p_user_id uuid)
returns jsonb
language sql
stable
set search_path = public
as $$
    select jsonb_build_object(
        'userId',          u.user_id,
        'nickname',        u.nickname,
        'pet',             private.pet_state(u.user_id),
        'normalTotal',     u.normal_total,
        'shadowTotal',     u.shadow_total,
        'normalRemainder', u.normal_total % 5,
        'shadowRemainder', u.shadow_total % 5
    )
    from public.users u
    where u.user_id = p_user_id;
$$;

-- ============================================================
-- 6. registerUser({requestId, nickname}) → UserState
--    실패: INVALID_INPUT, NICKNAME_TAKEN, ID_CONFLICT
-- ============================================================
create function public.register_user(p_request_id uuid, p_nickname text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_nickname text := private.normalize_nickname(p_nickname);
    v_prev     public.register_requests;
    v_user_id  uuid;
begin
    if p_request_id is null then
        perform private.app_error('INVALID_INPUT', 400, 'requestId가 필요합니다');
    end if;
    if char_length(v_nickname) not between 2 and 10 then
        perform private.app_error('INVALID_INPUT', 400, '닉네임은 2~10자여야 합니다');
    end if;

    -- 같은 요청의 재전송: 처음 만든 사용자를 그대로 돌려준다
    select * into v_prev from public.register_requests where request_id = p_request_id;
    if found then
        if v_prev.nickname <> v_nickname then
            perform private.app_error('ID_CONFLICT', 409, '이미 다른 닉네임 등록에 사용한 requestId입니다');
        end if;
        return private.user_state(v_prev.user_id);
    end if;

    begin
        insert into public.users (nickname) values (v_nickname) returning user_id into v_user_id;
    exception when unique_violation then
        perform private.app_error('NICKNAME_TAKEN', 409, '이미 사용 중인 닉네임입니다');
    end;

    insert into public.pets (user_id) values (v_user_id);
    insert into public.register_requests (request_id, nickname, user_id)
    values (p_request_id, v_nickname, v_user_id);

    return private.user_state(v_user_id);
end;
$$;

-- ============================================================
-- 7. loginByNickname({nickname}) → UserState
--    실패: NICKNAME_NOT_FOUND. 새 사용자를 만들지 않는다.
-- ============================================================
create function public.login_by_nickname(p_nickname text)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_user_id uuid;
begin
    select user_id into v_user_id
    from public.users
    where nickname = private.normalize_nickname(p_nickname);

    if v_user_id is null then
        perform private.app_error('NICKNAME_NOT_FOUND', 404, '등록되지 않은 닉네임입니다');
    end if;

    return private.user_state(v_user_id);
end;
$$;

-- ============================================================
-- 8. getUserState({userId}) → UserState
--    실패: USER_NOT_FOUND. 기기에 저장된 userId 로 자동 접속할 때 쓴다.
-- ============================================================
create function public.get_user_state(p_user_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
begin
    if p_user_id is null or not exists (select 1 from public.users where user_id = p_user_id) then
        perform private.app_error('USER_NOT_FOUND', 404, '알 수 없는 사용자입니다');
    end if;

    return private.user_state(p_user_id);
end;
$$;

-- ============================================================
-- 9. 권한과 설명
-- ============================================================
revoke all on function public.register_user(uuid, text)  from public;
revoke all on function public.login_by_nickname(text)    from public;
revoke all on function public.get_user_state(uuid)       from public;

grant execute on function public.register_user(uuid, text) to anon, authenticated;
grant execute on function public.login_by_nickname(text)   to anon, authenticated;
grant execute on function public.get_user_state(uuid)      to anon, authenticated;

comment on function public.register_user(uuid, text) is
    'registerUser (§08). 사용자·기본 펫 생성. 같은 requestId 재전송은 처음 결과. 실패: INVALID_INPUT, NICKNAME_TAKEN, ID_CONFLICT';
comment on function public.login_by_nickname(text) is
    'loginByNickname (§08). 기존 사용자 UserState. 실패: NICKNAME_NOT_FOUND';
comment on function public.get_user_state(uuid) is
    'getUserState (§08). 실패: USER_NOT_FOUND';
