-- 실패 사유를 오류 키로 구분 (FR-05-12) + 함수 설명 추가 (동적 명세서용)
--
-- 실패 응답 형식 (PostgREST가 errcode 'PTxxx'를 HTTP xxx로 돌려준다)
--   HTTP 409
--   { "code": "PT409", "message": "NICKNAME_TAKEN", "details": "이미 사용 중인 닉네임입니다", "hint": null }
-- 앱은 message(오류 키)로 구분한다. 오류 키 목록은 backend/API.md 4장.

-- ============================================================
-- 1. 내부 도우미 (API로 노출되지 않는 private 스키마)
-- ============================================================
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create function private.app_error(p_key text, p_status integer, p_detail text)
returns void
language plpgsql
as $$
begin
    raise exception using
        errcode = 'PT' || p_status::text,
        message = p_key,
        detail  = p_detail;
end;
$$;

-- ============================================================
-- 2. 세션 확인: AUTH_REQUIRED
-- ============================================================
create or replace function public.require_uid()
returns uuid
language plpgsql
stable
as $$
begin
    if auth.uid() is null then
        perform private.app_error('AUTH_REQUIRED', 401, '인증 세션이 없습니다');
    end if;
    return auth.uid();
end;
$$;

-- 다른 함수 안에서만 쓰므로 앱에서 직접 호출하지 못하게 한다
revoke execute on function public.require_uid() from authenticated;

-- ============================================================
-- 3. 닉네임 등록: NICKNAME_INVALID / ALREADY_REGISTERED / NICKNAME_TAKEN
-- ============================================================
create or replace function public.register_user(p_nickname text)
returns public.pets
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid        uuid := public.require_uid();
    v_pet        public.pets;
    v_constraint text;
begin
    if p_nickname is null or char_length(p_nickname) not between 2 and 10 then
        perform private.app_error('NICKNAME_INVALID', 400, '닉네임은 2~10자여야 합니다');
    end if;

    if exists (select 1 from public.users where id = v_uid) then
        perform private.app_error('ALREADY_REGISTERED', 409, '이미 닉네임을 등록한 사용자입니다');
    end if;

    begin
        insert into public.users (id, nickname) values (v_uid, p_nickname);
    exception when unique_violation then
        get stacked diagnostics v_constraint = constraint_name;
        if v_constraint = 'users_nickname_key' then
            perform private.app_error('NICKNAME_TAKEN', 409, '이미 사용 중인 닉네임입니다');
        else
            -- 같은 사용자가 동시에 두 번 등록한 경우
            perform private.app_error('ALREADY_REGISTERED', 409, '이미 닉네임을 등록한 사용자입니다');
        end if;
    end;

    insert into public.pets (user_id) values (v_uid) returning * into v_pet;
    return v_pet;
end;
$$;

-- ============================================================
-- 4. 수집 기록 저장: NOT_REGISTERED / MISSING_SERVER_ID / INVALID_VALUE / RECORD_OF_OTHER_USER
-- ============================================================
create or replace function public.add_collection_record(
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
    if not exists (select 1 from public.users where id = v_uid) then
        perform private.app_error('NOT_REGISTERED', 403, '닉네임 등록 후 이용할 수 있습니다');
    end if;

    if not coalesce(p_is_test, false) and p_public_server_id is null then
        perform private.app_error('MISSING_SERVER_ID', 400, '실제 모드 수집 기록에는 공용 서버 id가 필요합니다');
    end if;

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

    if v_record.id is null then
        -- 재전송: 본인 기록이면 기존 기록 반환, 남의 기록이면 거절
        select * into v_record
        from public.collection_records
        where public_server_id = p_public_server_id
          and user_id = v_uid;

        if v_record.id is null then
            perform private.app_error('RECORD_OF_OTHER_USER', 403, '다른 사용자의 수집 기록입니다');
        end if;
    end if;

    return v_record;
end;
$$;

-- ============================================================
-- 5. 수집 기록 수정·삭제 금지: RECORD_IMMUTABLE
-- ============================================================
create or replace function public.forbid_collection_record_change()
returns trigger
language plpgsql
as $$
begin
    perform private.app_error('RECORD_IMMUTABLE', 403, '수집 기록은 수정하거나 삭제할 수 없습니다');
    return null;
end;
$$;

-- ============================================================
-- 6. 함수 설명 (Supabase API Docs에 표시된다)
-- ============================================================
comment on function public.is_nickname_available(text) is
    '닉네임 사용 가능 여부 (FR-05-02). 반환: true/false';

comment on function public.register_user(text) is
    '닉네임 등록 + 펫 생성 (FR-05-02, 03). 반환: 펫. 실패: NICKNAME_INVALID, NICKNAME_TAKEN, ALREADY_REGISTERED';

comment on function public.get_my_profile() is
    '내 사용자·펫 정보 (FR-05-03). 닉네임 등록 전이면 []';

comment on function public.add_collection_record(text, numeric, numeric, integer, integer, integer, integer, timestamptz, text, boolean, boolean) is
    '수집 기록 저장 (FR-05-04). 공용 API 2 저장 성공 후 호출. 같은 p_public_server_id 재전송은 기존 기록 반환. 실패: NOT_REGISTERED, MISSING_SERVER_ID, INVALID_VALUE, RECORD_OF_OTHER_USER';

comment on function public.get_ranking(integer) is
    '상위 랭킹 (FR-05-11). p_limit 기본 50, 최대 100. UUID는 반환하지 않음';
