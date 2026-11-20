-- 보상 시스템 (FR-05-05, FR-05-06, NFR-05-01) + 수집 보상 자동 지급 (FR-04-07, FR-04-08)
--
-- 수집 기록이 새로 저장되면 DB가 바로 조건을 확인해 먹이를 지급한다.
--   - 센서별 첫 수집          : reward_first_collection_food 개 (센서마다 1번)
--   - 일반 수집 N회마다        : reward_collection_food 개
--   - 음영지역 수집 N회마다    : reward_shadow_food 개 (일반 횟수에는 넣지 않음)
--   - 테스트 모드 기록         : 보상 없음
-- 같은 기록으로는 두 번 지급되지 않는다 (reward_history 유니크 제약).

-- ============================================================
-- 0. 사용자 삭제 시 수집 기록도 함께 삭제되도록 수정
--    (기존: 수집 기록이 있는 사용자는 삭제되지 않았음)
-- ============================================================
alter table public.collection_records
    drop constraint collection_records_user_id_fkey,
    add  constraint collection_records_user_id_fkey
         foreign key (user_id) references public.users(id) on delete cascade;

-- 수정은 항상 금지. 삭제는 사용자 탈퇴(계정 삭제)로 함께 지워질 때만 허용
create or replace function public.forbid_collection_record_change()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE'
       and not exists (select 1 from public.users where id = old.user_id) then
        return old;
    end if;
    perform private.app_error('RECORD_IMMUTABLE', 403, '수집 기록은 수정하거나 삭제할 수 없습니다');
    return null;
end;
$$;

-- ============================================================
-- 1. 설정표: 팀 결정에 따라 바뀔 수 있는 숫자
--    값을 바꿀 때는 Table Editor 에서 value 만 고치면 된다.
-- ============================================================
create table public.settings (
    key          text    primary key,
    value        integer not null check (value >= 0),
    description  text    not null
);

insert into public.settings (key, value, description) values
    ('reward_first_collection_food', 2, '센서별 첫 유효 수집 보상 먹이 수 (FR-04-07, RW-1)'),
    ('reward_collection_every',      5, '일반 수집 보상 주기, N회마다 (FR-04-08, RW-2)'),
    ('reward_collection_food',       1, '일반 수집 보상 먹이 수 (FR-04-08, RW-2)'),
    ('reward_shadow_every',          5, '음영지역 수집 보상 주기, N회마다 (FR-04-08, RW-3)'),
    ('reward_shadow_food',           2, '음영지역 수집 보상 먹이 수 (FR-04-08, RW-3)');

alter table public.settings enable row level security;
create policy settings_read_all on public.settings
    for select to authenticated using (true);

create function private.setting(p_key text)
returns integer
language plpgsql
stable
set search_path = public
as $$
declare
    v_value integer;
begin
    select value into v_value from public.settings where key = p_key;
    if v_value is null then
        raise exception '설정값이 없습니다: %', p_key;
    end if;
    return v_value;
end;
$$;

-- ============================================================
-- 2. 사용자별 수집 현황 (FR-05-06)
-- ============================================================
create table public.collection_stats (
    user_id                  uuid        primary key references public.users(id) on delete cascade,
    collection_count         integer     not null default 0 check (collection_count >= 0),
    shadow_collection_count  integer     not null default 0 check (shadow_collection_count >= 0),
    updated_at               timestamptz not null default now()
);

create table public.first_collections (
    user_id               uuid        not null references public.users(id) on delete cascade,
    sensor_name           text        not null,
    collection_record_id  bigint      not null references public.collection_records(id) on delete cascade,
    created_at            timestamptz not null default now(),
    primary key (user_id, sensor_name)
);

comment on table public.collection_stats  is '일반·음영지역 수집 횟수. 실제 모드 기록만 센다';
comment on table public.first_collections is '사용자가 처음 수집한 센서 목록 (센서별 첫 수집 보상 판정)';

-- ============================================================
-- 3. 보상 지급 이력 (FR-05-05, NFR-05-01)
-- ============================================================
create table public.reward_history (
    id                    bigint      generated always as identity primary key,
    user_id               uuid        not null references public.users(id) on delete cascade,
    reason                text        not null
                          check (reason in ('first_collection', 'collection', 'shadow_collection', 'feedback')),
    food                  integer     not null check (food > 0),
    collection_record_id  bigint      references public.collection_records(id) on delete cascade,
    feedback_id           bigint,     -- 피드백 테이블을 만들 때 외래 키 추가
    created_at            timestamptz not null default now(),

    -- 수집 보상은 수집 기록에, 피드백 보상은 피드백에 연결된다
    check (
        (reason =  'feedback' and feedback_id is not null and collection_record_id is null) or
        (reason <> 'feedback' and feedback_id is null     and collection_record_id is not null)
    ),
    -- 같은 기록·같은 사유로는 한 번만 지급
    unique (reason, collection_record_id),
    unique (reason, feedback_id)
);

create index reward_history_user_idx on public.reward_history (user_id, created_at desc);

alter table public.collection_stats  enable row level security;
alter table public.first_collections enable row level security;
alter table public.reward_history    enable row level security;

create policy collection_stats_read_own  on public.collection_stats
    for select to authenticated using (user_id = auth.uid());
create policy first_collections_read_own on public.first_collections
    for select to authenticated using (user_id = auth.uid());
create policy reward_history_read_own    on public.reward_history
    for select to authenticated using (user_id = auth.uid());

-- 수집 보상 1건 지급. 이미 지급된 경우 null 반환
create function private.grant_collection_reward(
    p_uid uuid, p_reason text, p_food integer, p_record_id bigint
)
returns jsonb
language plpgsql
set search_path = public
as $$
begin
    if p_food <= 0 then
        return null;
    end if;

    insert into public.reward_history (user_id, reason, food, collection_record_id)
    values (p_uid, p_reason, p_food, p_record_id)
    on conflict do nothing;

    if not found then
        return null;
    end if;

    update public.pets
    set food = food + p_food, updated_at = now()
    where user_id = p_uid;

    return jsonb_build_object('food', p_food, 'reason', p_reason);
end;
$$;

-- ============================================================
-- 4. 수집 기록 저장 + 수집 보상 지급
--    반환 형식이 바뀌므로 함수를 지우고 다시 만든다.
--    반환: { "record": {...}, "rewards": [{ "food": 2, "reason": "first_collection" }], "food": 2 }
-- ============================================================
drop function public.add_collection_record(text, numeric, numeric, integer, integer, integer, integer, timestamptz, text, boolean, boolean);

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
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid      uuid := public.require_uid();
    v_record   public.collection_records;
    v_is_new   boolean;
    v_rewards  jsonb := '[]'::jsonb;
    v_reward   jsonb;
    v_count    integer;
    v_every    integer;
    v_food     integer;
begin
    if not exists (select 1 from public.users where id = v_uid) then
        perform private.app_error('NOT_REGISTERED', 403, '닉네임 등록 후 이용할 수 있습니다');
    end if;

    if not coalesce(p_is_test, false) and p_public_server_id is null then
        perform private.app_error('MISSING_SERVER_ID', 400, '실제 모드 수집 기록에는 공용 서버 id가 필요합니다');
    end if;

    -- 1) 기록 저장
    begin
        insert into public.collection_records (
            user_id, sensor_name, temperature, humidity, aqi, tvoc, co2, rssi,
            measured_at, public_server_id, is_shadow_area, is_test
        ) values (
            v_uid, p_sensor_name, p_temperature, p_humidity, p_aqi, p_tvoc, p_co2, p_rssi,
            p_measured_at, p_public_server_id, p_is_shadow_area, coalesce(p_is_test, false)
        )
        on conflict (public_server_id) do nothing
        returning * into v_record;
    exception when check_violation or not_null_violation then
        perform private.app_error('INVALID_VALUE', 400, '측정값이 허용 범위를 벗어났거나 필수값이 없습니다');
    end;

    v_is_new := v_record.id is not null;

    if not v_is_new then
        -- 재전송: 본인 기록이면 기존 기록 반환(보상 없음), 남의 기록이면 거절
        select * into v_record
        from public.collection_records
        where public_server_id = p_public_server_id
          and user_id = v_uid;

        if v_record.id is null then
            perform private.app_error('RECORD_OF_OTHER_USER', 403, '다른 사용자의 수집 기록입니다');
        end if;
    end if;

    -- 2) 보상 (새 기록 + 실제 모드만)
    if v_is_new and not v_record.is_test then

        -- 센서별 첫 수집
        insert into public.first_collections (user_id, sensor_name, collection_record_id)
        values (v_uid, v_record.sensor_name, v_record.id)
        on conflict do nothing;

        if found then
            v_reward := private.grant_collection_reward(
                v_uid, 'first_collection', private.setting('reward_first_collection_food'), v_record.id);
            if v_reward is not null then
                v_rewards := v_rewards || jsonb_build_array(v_reward);
            end if;
        end if;

        -- 횟수 증가 (같은 사용자의 동시 요청은 이 행 잠금으로 차례대로 처리됨)
        insert into public.collection_stats (user_id) values (v_uid)
        on conflict (user_id) do nothing;

        if v_record.is_shadow_area then
            update public.collection_stats
            set shadow_collection_count = shadow_collection_count + 1, updated_at = now()
            where user_id = v_uid
            returning shadow_collection_count into v_count;

            v_every := private.setting('reward_shadow_every');
            if v_every > 0 and v_count % v_every = 0 then
                v_reward := private.grant_collection_reward(
                    v_uid, 'shadow_collection', private.setting('reward_shadow_food'), v_record.id);
                if v_reward is not null then
                    v_rewards := v_rewards || jsonb_build_array(v_reward);
                end if;
            end if;
        else
            update public.collection_stats
            set collection_count = collection_count + 1, updated_at = now()
            where user_id = v_uid
            returning collection_count into v_count;

            v_every := private.setting('reward_collection_every');
            if v_every > 0 and v_count % v_every = 0 then
                v_reward := private.grant_collection_reward(
                    v_uid, 'collection', private.setting('reward_collection_food'), v_record.id);
                if v_reward is not null then
                    v_rewards := v_rewards || jsonb_build_array(v_reward);
                end if;
            end if;
        end if;
    end if;

    select food into v_food from public.pets where user_id = v_uid;

    return jsonb_build_object(
        'record',  to_jsonb(v_record),
        'rewards', v_rewards,
        'food',    v_food
    );
end;
$$;

-- ============================================================
-- 5. 내 수집 현황 (FR-05-06) - "다음 보상까지 N회" 표시용
-- ============================================================
create function public.get_my_collection_stats()
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_uid          uuid := public.require_uid();
    v_count        integer := 0;
    v_shadow       integer := 0;
    v_every        integer := private.setting('reward_collection_every');
    v_shadow_every integer := private.setting('reward_shadow_every');
begin
    select collection_count, shadow_collection_count
    into v_count, v_shadow
    from public.collection_stats
    where user_id = v_uid;

    v_count  := coalesce(v_count, 0);
    v_shadow := coalesce(v_shadow, 0);

    return jsonb_build_object(
        'collection_count',          v_count,
        'shadow_collection_count',   v_shadow,
        'next_collection_reward_in', case when v_every > 0 then v_every - v_count % v_every end,
        'next_shadow_reward_in',     case when v_shadow_every > 0 then v_shadow_every - v_shadow % v_shadow_every end,
        'first_sensors', coalesce(
            (select jsonb_agg(sensor_name order by created_at)
             from public.first_collections where user_id = v_uid),
            '[]'::jsonb)
    );
end;
$$;

-- ============================================================
-- 6. 권한과 설명
-- ============================================================
revoke all on function public.add_collection_record(text, numeric, numeric, integer, integer, integer, integer, timestamptz, text, boolean, boolean) from public, anon;
revoke all on function public.get_my_collection_stats() from public, anon;

grant execute on function public.add_collection_record(text, numeric, numeric, integer, integer, integer, integer, timestamptz, text, boolean, boolean) to authenticated;
grant execute on function public.get_my_collection_stats() to authenticated;

comment on function public.add_collection_record(text, numeric, numeric, integer, integer, integer, integer, timestamptz, text, boolean, boolean) is
    '수집 기록 저장 + 수집 보상 지급 (FR-05-04, 05). 공용 API 2 저장 성공 후 호출. 반환: {record, rewards, food}. 같은 p_public_server_id 재전송은 기존 기록과 rewards [] 반환. 실패: NOT_REGISTERED, MISSING_SERVER_ID, INVALID_VALUE, RECORD_OF_OTHER_USER';

comment on function public.get_my_collection_stats() is
    '내 수집 현황 (FR-05-06). 반환: collection_count, shadow_collection_count, next_collection_reward_in, next_shadow_reward_in, first_sensors';
