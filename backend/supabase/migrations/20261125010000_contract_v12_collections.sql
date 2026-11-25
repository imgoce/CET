-- 계약서 v1.2 기준 DB 재구성 (2/5): 수집 저장·음영지역·수집 보상
--
-- 기준: docs/api-contract.md §04, §05, §06, §07, §13, §14
-- 요구사항: SM-3, DC-3-4, DC-5-3, DC-6, DC-6-1, DC-7, RW-1, RW-2, RW-3, RW-5, RW-6, EX-6
--
-- commit_collection(p_input, p_test) 한 번에 처리하는 것 (§07 저장 순서)
--   1. 같은 collectionId 면 처음 결과를 돌려준다 (내용이 다르면 ID_CONFLICT)
--   2. 저장 직전 음영 여부: 34곳을 고유 측정 수 오름차순·센서 번호순으로 정렬한 하위 5곳
--   3. 환경 측정은 (sensorNo, measuredAt) 별 1건. 새 측정이면 그 센서 건수 +1
--   4. 기여 인정: 후보 센서이고, 같은 사용자의 겹치는 10초 구간·같은 측정에 인정 기록이 없을 때
--   5. 보상: 오늘(KST, collectedAt 기준) 이 센서 첫 기여 2개 / 일반 5회마다 1개 / 음영 5회마다 2개
--   6. 기록·카운터·잔액·지급 이력을 한 트랜잭션으로 저장
-- 운영 수집은 전역 잠금으로 한 건씩 처리한다 (두 폰이 동시에 보내도 한 건씩 확정한 것과 같은 결과).
-- 테스트 모드(p_test = true)는 test_collections 에만 저장하고 운영 데이터는 바꾸지 않는다.

-- ============================================================
-- 1. 센서별 고유 측정 수 (음영지역 순위용)
-- ============================================================
alter table public.sensors
    add column unique_measurements bigint not null default 0 check (unique_measurements >= 0);

-- ============================================================
-- 2. 환경 측정: (sensor_no, measured_at) 별 1건 (§14)
-- ============================================================
create table public.measurements (
    sensor_no            integer     not null references public.sensors(sensor_no),
    measured_at          timestamptz not null,
    temperature_c        numeric,            -- 31·32 는 항상 null
    humidity_rh          numeric,            -- 연구실 센서·31·32 는 null
    co2_ppm              integer,            -- 연구실 센서
    eco2_ppm             integer,            -- 수업용 센서
    aqi                  integer,
    tvoc_ppb             integer,
    first_collection_id  uuid        not null,  -- 이 측정을 처음 저장한 수집
    stored_at            timestamptz not null default now(),
    primary key (sensor_no, measured_at)
);

create index measurements_stored_at_idx on public.measurements (stored_at desc);

-- ============================================================
-- 3. 개인 수집 기록 (§14). 비후보·중복 기록도 모두 남긴다. 수정·삭제 불가.
-- ============================================================
create table public.collections (
    collection_id        uuid        primary key,
    user_id              uuid        not null references public.users(user_id) on delete cascade,
    window_id            uuid        not null,
    window_start_at      timestamptz not null,
    candidate_sensor_no  integer     not null,
    sensor_no            integer     not null references public.sensors(sensor_no),
    sensor_mac           text        not null,
    measured_at          timestamptz not null,
    collected_at         timestamptz not null,
    rssi_dbm             integer     not null,
    temperature_c        numeric,
    humidity_rh          numeric,
    co2_ppm              integer,
    eco2_ppm             integer,
    aqi                  integer,
    tvoc_ppb             integer,
    public_route         text        not null check (public_route in ('UPLOADED', 'TEMP_UNAVAILABLE')),
    public_id            bigint,
    credited             boolean     not null,   -- 기여 인정 여부
    is_shadow_before     boolean     not null,   -- 저장 직전 음영 여부
    measurement_added    boolean     not null,   -- 새 환경 측정이었는지
    input_fingerprint    text        not null,   -- 같은 ID 재전송 확인용
    result               jsonb       not null,   -- 처음 돌려준 응답 (재전송 시 그대로)
    stored_at            timestamptz not null default now()
);

create index collections_user_idx on public.collections (user_id, window_start_at);
create index collections_collected_idx on public.collections (collected_at);

-- 기여는 같은 사용자의 같은 구간·같은 측정에 최대 1회 (전역 잠금과 함께 이중 안전장치)
create unique index collections_credit_window_uidx
    on public.collections (user_id, window_id) where credited;
create unique index collections_credit_measurement_uidx
    on public.collections (user_id, sensor_no, measured_at) where credited;

create trigger collections_append_only
    before update or delete on public.collections
    for each row execute function private.forbid_change();

-- ============================================================
-- 4. 사용자·센서·KST 수집일별 첫 기여 (§14, RW-1)
-- ============================================================
create table public.first_sensor_days (
    user_id        uuid    not null references public.users(user_id) on delete cascade,
    sensor_no      integer not null references public.sensors(sensor_no),
    kst_date       date    not null,
    collection_id  uuid    not null references public.collections(collection_id) on delete cascade,
    primary key (user_id, sensor_no, kst_date)
);

-- ============================================================
-- 5. 지급 이력 (§14, RW-5). (참여 UUID, 사유) 별 1번. 수정·삭제 불가.
--    참여 UUID = collectionId 또는 feedbackId
-- ============================================================
create table public.grants (
    grant_id          bigint      generated always as identity primary key,
    user_id           uuid        not null references public.users(user_id) on delete cascade,
    participation_id  uuid        not null,
    reason            text        not null
                      check (reason in ('FIRST_SENSOR_DAY', 'NORMAL_FIVE', 'SHADOW_FIVE', 'FEEDBACK')),
    food              integer     not null check (food > 0),
    granted_at        timestamptz not null default now(),
    unique (participation_id, reason)
);

create index grants_user_idx on public.grants (user_id, granted_at);

create trigger grants_append_only
    before update or delete on public.grants
    for each row execute function private.forbid_change();

-- ============================================================
-- 6. 테스트 모드 저장 공간 (DC-7). 운영 건수·음영·보상·랭킹에 반영하지 않는다.
-- ============================================================
create table public.test_collections (
    collection_id      uuid        primary key,
    user_id            uuid        not null references public.users(user_id) on delete cascade,
    input              jsonb       not null,
    input_fingerprint  text        not null,
    stored_at          timestamptz not null default now()
);

-- ============================================================
-- 7. 접근 제어
-- ============================================================
alter table public.measurements      enable row level security;
alter table public.collections       enable row level security;
alter table public.first_sensor_days enable row level security;
alter table public.grants            enable row level security;
alter table public.test_collections  enable row level security;

revoke all on public.measurements, public.collections, public.first_sensor_days,
              public.grants, public.test_collections
    from anon, authenticated;

-- ============================================================
-- 8. 도우미
-- ============================================================

-- 현재 음영지역 5곳 (§07): 고유 측정 수 오름차순, 동률은 센서 번호순
create function private.shadow_sensor_nos()
returns integer[]
language sql
stable
set search_path = public
as $$
    select array_agg(sensor_no order by unique_measurements, sensor_no)
    from (
        select sensor_no, unique_measurements
        from public.sensors
        order by unique_measurements, sensor_no
        limit 5
    ) s;
$$;

-- 먹이 지급 1건: 이력 저장 + 잔액 증가. 이미 지급했으면 null
create function private.grant_food(p_user_id uuid, p_participation_id uuid, p_reason text, p_food integer)
returns jsonb
language plpgsql
set search_path = public
as $$
begin
    insert into public.grants (user_id, participation_id, reason, food)
    values (p_user_id, p_participation_id, p_reason, p_food)
    on conflict (participation_id, reason) do nothing;

    if not found then
        return null;
    end if;

    update public.pets
    set food_balance = food_balance + p_food, updated_at = now()
    where user_id = p_user_id;

    return jsonb_build_object('reason', p_reason, 'food', p_food);
end;
$$;

-- ============================================================
-- 9. commitCollection(input) → CollectionResult (§06)
--    p_input: 계약서 §06 의 요청 JSON 그대로
--    실패: INVALID_INPUT, ID_CONFLICT, USER_NOT_FOUND
-- ============================================================
create function public.commit_collection(p_input jsonb, p_test boolean default false)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    m                    jsonb := p_input -> 'measurement';
    v_fingerprint        text  := md5(p_input::text);
    v_collection_id      uuid;
    v_user_id            uuid;
    v_window_id          uuid;
    v_window_start       timestamptz;
    v_candidate          integer;
    v_sensor_no          integer;
    v_mac                text;
    v_measured_at        timestamptz;
    v_collected_at       timestamptz;
    v_rssi               integer;
    v_temp               numeric;
    v_humidity           numeric;
    v_co2                integer;
    v_eco2               integer;
    v_aqi                integer;
    v_tvoc               integer;
    v_route              text;
    v_public_id          bigint;
    v_sensor             public.sensors;
    v_prev               record;
    v_replay             boolean;
    v_is_shadow          boolean;
    v_added              boolean;
    v_credited           boolean := false;
    v_first_day          boolean := false;
    v_total              bigint;
    v_grants             jsonb := '[]'::jsonb;
    v_grant              jsonb;
    v_result             jsonb;
begin
    -- ── 입력 해석 ──────────────────────────────────────────────
    begin
        v_collection_id := (p_input ->> 'collectionId')::uuid;
        v_user_id       := (p_input ->> 'userId')::uuid;
        v_window_id     := (p_input ->> 'windowId')::uuid;
        v_window_start  := (p_input ->> 'windowStartAt')::timestamptz;
        v_candidate     := (p_input ->> 'candidateSensorNo')::integer;
        v_route         := p_input ->> 'publicRoute';
        v_public_id     := (p_input ->> 'publicId')::bigint;
        v_sensor_no     := (m ->> 'sensorNo')::integer;
        v_mac           := m ->> 'sensorMac';
        v_measured_at   := (m ->> 'measuredAt')::timestamptz;
        v_collected_at  := (m ->> 'collectedAt')::timestamptz;
        v_rssi          := (m ->> 'rssiDbm')::integer;
        v_temp          := (m ->> 'temperatureC')::numeric;
        v_humidity      := (m ->> 'humidityRh')::numeric;
        v_co2           := (m ->> 'co2Ppm')::integer;
        v_eco2          := (m ->> 'eco2Ppm')::integer;
        v_aqi           := (m ->> 'aqi')::integer;
        v_tvoc          := (m ->> 'tvocPpb')::integer;
    exception when others then
        perform private.app_error('INVALID_INPUT', 400, '입력 형식이 올바르지 않습니다: ' || sqlerrm);
    end;

    -- ── 입력 검사 (§04, §05, §06) ─────────────────────────────
    if v_collection_id is null or v_user_id is null or v_window_id is null or v_window_start is null
       or v_candidate is null or v_sensor_no is null or v_mac is null or v_measured_at is null
       or v_collected_at is null or v_rssi is null or v_route is null then
        perform private.app_error('INVALID_INPUT', 400, '필수 값이 없습니다');
    end if;

    select * into v_sensor from public.sensors where sensor_no = v_sensor_no;
    if not found or v_candidate not between 1 and 34 then
        perform private.app_error('INVALID_INPUT', 400, '등록되지 않은 센서 번호입니다');
    end if;
    if v_rssi < -85 then
        perform private.app_error('INVALID_INPUT', 400, 'RSSI가 -85 dBm보다 약합니다');
    end if;
    if v_collected_at < v_window_start or v_collected_at >= v_window_start + interval '10 seconds' then
        perform private.app_error('INVALID_INPUT', 400, 'collectedAt이 해당 10초 구간 밖입니다');
    end if;

    if v_route = 'UPLOADED' then
        if v_public_id is null or v_public_id <= 0 then
            perform private.app_error('INVALID_INPUT', 400, 'UPLOADED 기록에는 양의 정수 publicId가 필요합니다');
        end if;
    elsif v_route = 'TEMP_UNAVAILABLE' then
        if v_public_id is not null or not v_sensor.temp_humidity_unavailable then
            perform private.app_error('INVALID_INPUT', 400, 'TEMP_UNAVAILABLE은 31·32번에만, publicId 없이 사용합니다');
        end if;
    else
        perform private.app_error('INVALID_INPUT', 400, 'publicRoute는 UPLOADED 또는 TEMP_UNAVAILABLE입니다');
    end if;

    -- 31·32 는 온습도를 저장하지 않는다. 연구실 센서는 습도·AQI·TVOC 가 없다.
    if v_sensor.temp_humidity_unavailable then
        v_temp := null;
        v_humidity := null;
    elsif v_temp is null or v_temp not between -30 and 50 then
        perform private.app_error('INVALID_INPUT', 400, '온도는 -30~50℃여야 합니다');
    end if;
    if not v_sensor.is_course_sensor then
        v_humidity := null;
        v_aqi := null;
        v_tvoc := null;
    elsif not v_sensor.temp_humidity_unavailable
          and (v_humidity is null or v_humidity not between 0 and 100) then
        perform private.app_error('INVALID_INPUT', 400, '습도는 0~100%여야 합니다');
    end if;
    if v_sensor.is_course_sensor then
        if v_eco2 is null or v_eco2 not between 400 and 5000 or v_co2 is not null then
            perform private.app_error('INVALID_INPUT', 400, '수업용 센서는 eco2Ppm(400~5000)만 보냅니다');
        end if;
    else
        if v_co2 is null or v_co2 not between 400 and 5000 or v_eco2 is not null then
            perform private.app_error('INVALID_INPUT', 400, '연구실 센서는 co2Ppm(400~5000)만 보냅니다');
        end if;
    end if;

    if not exists (select 1 from public.users where user_id = v_user_id) then
        perform private.app_error('USER_NOT_FOUND', 404, '알 수 없는 사용자입니다');
    end if;

    -- ── 테스트 모드: 테스트 공간에만 저장 (DC-7) ───────────────
    if coalesce(p_test, false) then
        select input_fingerprint into v_prev from public.test_collections where collection_id = v_collection_id;
        v_replay := found;
        if v_replay and v_prev.input_fingerprint <> v_fingerprint then
            perform private.app_error('ID_CONFLICT', 409, '이미 다른 내용에 사용한 collectionId입니다');
        end if;

        insert into public.test_collections (collection_id, user_id, input, input_fingerprint)
        values (v_collection_id, v_user_id, p_input, v_fingerprint)
        on conflict (collection_id) do nothing;

        return (
            select jsonb_build_object(
                'collectionId',     v_collection_id,
                'credited',         false,
                'isShadowBefore',   false,
                'measurementAdded', false,
                'grants',           '[]'::jsonb,
                'foodBalance',      p.food_balance,
                'normalRemainder',  u.normal_total % 5,
                'shadowRemainder',  u.shadow_total % 5,
                'replayed',         v_replay
            )
            from public.users u join public.pets p using (user_id)
            where u.user_id = v_user_id
        );
    end if;

    -- ── 운영: 한 건씩 처리 ────────────────────────────────────
    perform pg_advisory_xact_lock(hashtext('cet.commit_collection'));

    -- 1) 재전송
    select input_fingerprint, result into v_prev from public.collections where collection_id = v_collection_id;
    if found then
        if v_prev.input_fingerprint <> v_fingerprint then
            perform private.app_error('ID_CONFLICT', 409, '이미 다른 내용에 사용한 collectionId입니다');
        end if;
        return v_prev.result || jsonb_build_object('replayed', true);
    end if;

    -- 2) 저장 직전 음영 여부
    v_is_shadow := v_sensor_no = any (private.shadow_sensor_nos());

    -- 3) 환경 측정
    insert into public.measurements (
        sensor_no, measured_at, temperature_c, humidity_rh, co2_ppm, eco2_ppm, aqi, tvoc_ppb, first_collection_id
    ) values (
        v_sensor_no, v_measured_at, v_temp, v_humidity, v_co2, v_eco2, v_aqi, v_tvoc, v_collection_id
    )
    on conflict (sensor_no, measured_at) do nothing;
    v_added := found;

    if v_added then
        update public.sensors set unique_measurements = unique_measurements + 1 where sensor_no = v_sensor_no;
    end if;

    -- 4) 기여 인정
    v_credited := v_sensor_no = v_candidate
        and not exists (
            select 1 from public.collections c
            where c.user_id = v_user_id and c.credited
              and (c.window_id = v_window_id
                   -- 다른 폰의 구간과 겹침: [시작, 시작+10초) 끼리 비교
                   or (c.window_start_at < v_window_start + interval '10 seconds'
                       and v_window_start < c.window_start_at + interval '10 seconds')
                   or (c.sensor_no = v_sensor_no and c.measured_at = v_measured_at))
        );

    -- 5) 보상 계산 (지급은 기록 저장 후)
    if v_credited then
        v_first_day := not exists (
            select 1 from public.first_sensor_days
            where user_id = v_user_id and sensor_no = v_sensor_no
              and kst_date = private.kst_date(v_collected_at)
        );

        if v_is_shadow then
            update public.users set shadow_total = shadow_total + 1
            where user_id = v_user_id returning shadow_total into v_total;
        else
            update public.users set normal_total = normal_total + 1
            where user_id = v_user_id returning normal_total into v_total;
        end if;

        if v_first_day then
            v_grants := v_grants || jsonb_build_array(jsonb_build_object('reason', 'FIRST_SENSOR_DAY', 'food', 2));
        end if;
        if v_total % 5 = 0 then
            v_grants := v_grants || jsonb_build_array(
                case when v_is_shadow
                     then jsonb_build_object('reason', 'SHADOW_FIVE', 'food', 2)
                     else jsonb_build_object('reason', 'NORMAL_FIVE', 'food', 1)
                end);
        end if;
    end if;

    -- 6) 저장: 기록 → 첫 기여 → 지급
    v_result := (
        select jsonb_build_object(
            'collectionId',     v_collection_id,
            'credited',         v_credited,
            'isShadowBefore',   v_is_shadow,
            'measurementAdded', v_added,
            'grants',           v_grants,
            'foodBalance',      p.food_balance
                                + coalesce((select sum((g ->> 'food')::integer)
                                            from jsonb_array_elements(v_grants) g), 0),
            'normalRemainder',  u.normal_total % 5,
            'shadowRemainder',  u.shadow_total % 5,
            'replayed',         false
        )
        from public.users u join public.pets p using (user_id)
        where u.user_id = v_user_id
    );

    insert into public.collections (
        collection_id, user_id, window_id, window_start_at, candidate_sensor_no,
        sensor_no, sensor_mac, measured_at, collected_at, rssi_dbm,
        temperature_c, humidity_rh, co2_ppm, eco2_ppm, aqi, tvoc_ppb,
        public_route, public_id, credited, is_shadow_before, measurement_added,
        input_fingerprint, result
    ) values (
        v_collection_id, v_user_id, v_window_id, v_window_start, v_candidate,
        v_sensor_no, v_mac, v_measured_at, v_collected_at, v_rssi,
        v_temp, v_humidity, v_co2, v_eco2, v_aqi, v_tvoc,
        v_route, v_public_id, v_credited, v_is_shadow, v_added,
        v_fingerprint, v_result
    );

    if v_first_day then
        insert into public.first_sensor_days (user_id, sensor_no, kst_date, collection_id)
        values (v_user_id, v_sensor_no, private.kst_date(v_collected_at), v_collection_id);
    end if;

    for v_grant in select * from jsonb_array_elements(v_grants) loop
        perform private.grant_food(v_user_id, v_collection_id, v_grant ->> 'reason', (v_grant ->> 'food')::integer);
    end loop;

    return v_result;
end;
$$;

-- ============================================================
-- 10. getShadowSites() → { sites: ShadowSite[5], updatedAt } (§07)
-- ============================================================
create function public.get_shadow_sites()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
    select jsonb_build_object(
        'sites', (
            select jsonb_agg(jsonb_build_object('sensorNo', sensor_no, 'uniqueMeasurements', unique_measurements)
                             order by unique_measurements, sensor_no)
            from (
                select sensor_no, unique_measurements from public.sensors
                order by unique_measurements, sensor_no limit 5
            ) s
        ),
        'updatedAt', (select max(stored_at) from public.measurements)
    );
$$;

-- ============================================================
-- 11. 권한과 설명
-- ============================================================
revoke all on function public.commit_collection(jsonb, boolean) from public;
revoke all on function public.get_shadow_sites()               from public;

grant execute on function public.commit_collection(jsonb, boolean) to anon, authenticated;
grant execute on function public.get_shadow_sites()               to anon, authenticated;

comment on function public.commit_collection(jsonb, boolean) is
    'commitCollection (§06·07). p_input = 계약서 §06 요청 JSON, p_test = 테스트 모드. 반환: CollectionResult. 실패: INVALID_INPUT, ID_CONFLICT, USER_NOT_FOUND';
comment on function public.get_shadow_sites() is
    'getShadowSites (§07). 고유 측정 수 하위 5곳과 마지막 갱신 시각';
