-- 계약서 v1.2 기준 DB 재구성 (3/5): 현장 피드백 저장·보상
--
-- 기준: docs/api-contract.md §09, §13, §14
-- 요구사항: FB-2, FB-2-1, FB-3, RW-4, RW-5
--
-- submit_feedback(p_input) 판정 순서 (§09)
--   1. 같은 feedbackId 면 처음 결과를 돌려준다 (다음 날 재전송이어도). 내용이 다르면 ID_CONFLICT
--   2. 입력: feltTemp·feltHumidity 필수, issues 1개 이상·중복 없음·NONE 은 단독, memo 100자(코드포인트) 이하
--   3. 연결 수집: 본인 소유·기여 인정·운영 기록 (아니면 NOT_ELIGIBLE)
--   4. 수집 1건당 피드백 1건 (FEEDBACK_EXISTS)
--   5. 현장 수집 시각(collectedAt) → DB 접수 시각(submittedAt) 600초 이하 (FEEDBACK_EXPIRED)
--   6. KST 제출일 기준 하루 3건 (DAILY_LIMIT)
--   7. 피드백·먹이 5개·지급 이력을 한 트랜잭션으로 저장
-- 같은 사용자의 피드백은 사용자 행 잠금으로 한 건씩 처리한다 (두 폰에서 동시에 내도 하루 최대 3건).

-- ============================================================
-- 1. 피드백 (§14). 수정·삭제 불가.
-- ============================================================
create table public.feedbacks (
    feedback_id         uuid        primary key,
    user_id             uuid        not null references public.users(user_id) on delete cascade,
    collection_id       uuid        not null unique references public.collections(collection_id) on delete cascade,
    sensor_no           integer     not null references public.sensors(sensor_no),
    collected_at        timestamptz not null,   -- 연결 수집의 현장 수신 시각 (집계의 날짜·시간대 기준)
    felt_temp           text        not null check (felt_temp in ('COLD', 'OK', 'HOT')),
    felt_humidity       text        not null check (felt_humidity in ('DRY', 'OK', 'HUMID')),
    issues              text[]      not null,
    memo                text,
    submitted_at        timestamptz not null,
    submitted_kst_date  date        not null,   -- 하루 한도 기준
    input_fingerprint   text        not null,
    result              jsonb       not null,   -- 처음 돌려준 응답
    check (cardinality(issues) >= 1),
    check (issues <@ array['NONE', 'NO_SHADE', 'STRONG_WIND', 'ODOR', 'NO_SEAT', 'OTHER']),
    check (not ('NONE' = any (issues)) or cardinality(issues) = 1),
    check (memo is null or char_length(memo) <= 100)
);

create index feedbacks_user_day_idx on public.feedbacks (user_id, submitted_kst_date);
create index feedbacks_collected_idx on public.feedbacks (collected_at);

create trigger feedbacks_append_only
    before update or delete on public.feedbacks
    for each row execute function private.forbid_change();

alter table public.feedbacks enable row level security;
revoke all on public.feedbacks from anon, authenticated;

-- ============================================================
-- 2. submitFeedback(input) → FeedbackResult (§09)
--    p_input: 계약서 §09 의 요청 JSON 그대로
--    실패: INVALID_INPUT, ID_CONFLICT, USER_NOT_FOUND, NOT_ELIGIBLE, FEEDBACK_EXISTS,
--          FEEDBACK_EXPIRED, DAILY_LIMIT
-- ============================================================
create function public.submit_feedback(p_input jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_fingerprint    text := md5(p_input::text);
    v_feedback_id    uuid;
    v_collection_id  uuid;
    v_user_id        uuid;
    v_felt_temp      text := p_input ->> 'feltTemp';
    v_felt_humidity  text := p_input ->> 'feltHumidity';
    v_issues         text[];
    v_memo           text := p_input ->> 'memo';
    v_prev           record;
    v_collection     public.collections;
    v_submitted_at   timestamptz := now();
    v_today          date := private.kst_date(now());
    v_today_count    integer;
    v_food           bigint;
    v_result         jsonb;
begin
    -- ── 입력 해석 ──────────────────────────────────────────────
    begin
        v_feedback_id   := (p_input ->> 'feedbackId')::uuid;
        v_collection_id := (p_input ->> 'collectionId')::uuid;
        v_user_id       := (p_input ->> 'userId')::uuid;
        select coalesce(array_agg(x), '{}') into v_issues
        from jsonb_array_elements_text(coalesce(p_input -> 'issues', '[]'::jsonb)) x;
    exception when others then
        perform private.app_error('INVALID_INPUT', 400, '입력 형식이 올바르지 않습니다: ' || sqlerrm);
    end;

    if v_feedback_id is null or v_collection_id is null or v_user_id is null then
        perform private.app_error('INVALID_INPUT', 400, 'feedbackId, collectionId, userId가 필요합니다');
    end if;

    -- 같은 사용자의 요청을 한 건씩 처리
    perform 1 from public.users where user_id = v_user_id for update;
    if not found then
        perform private.app_error('USER_NOT_FOUND', 404, '알 수 없는 사용자입니다');
    end if;

    -- 1) 재전송: 기간·한도를 다시 검사하기 전에 처음 결과를 돌려준다 (§13)
    select input_fingerprint, result into v_prev from public.feedbacks where feedback_id = v_feedback_id;
    if found then
        if v_prev.input_fingerprint <> v_fingerprint then
            perform private.app_error('ID_CONFLICT', 409, '이미 다른 내용에 사용한 feedbackId입니다');
        end if;
        return v_prev.result || jsonb_build_object('replayed', true);
    end if;

    -- 2) 입력 검사
    if v_felt_temp is null or v_felt_temp not in ('COLD', 'OK', 'HOT') then
        perform private.app_error('INVALID_INPUT', 400, 'feltTemp는 COLD, OK, HOT 중 하나입니다');
    end if;
    if v_felt_humidity is null or v_felt_humidity not in ('DRY', 'OK', 'HUMID') then
        perform private.app_error('INVALID_INPUT', 400, 'feltHumidity는 DRY, OK, HUMID 중 하나입니다');
    end if;
    if cardinality(v_issues) = 0 then
        perform private.app_error('INVALID_INPUT', 400, 'issues를 1개 이상 선택해야 합니다');
    end if;
    if not (v_issues <@ array['NONE', 'NO_SHADE', 'STRONG_WIND', 'ODOR', 'NO_SEAT', 'OTHER']) then
        perform private.app_error('INVALID_INPUT', 400, '알 수 없는 issues 값이 있습니다');
    end if;
    if cardinality(v_issues) <> (select count(distinct x) from unnest(v_issues) x) then
        perform private.app_error('INVALID_INPUT', 400, 'issues에 중복이 있습니다');
    end if;
    if 'NONE' = any (v_issues) and cardinality(v_issues) > 1 then
        perform private.app_error('INVALID_INPUT', 400, 'NONE(개선 필요 없음)은 다른 불편 사항과 함께 고를 수 없습니다');
    end if;
    if v_memo is not null and btrim(v_memo) = '' then
        v_memo := null;   -- 공백만 있으면 null
    end if;
    if char_length(v_memo) > 100 then
        perform private.app_error('INVALID_INPUT', 400, '메모는 100자 이하입니다');
    end if;

    -- 3) 연결 수집: 본인 소유·기여 인정·운영 기록 (테스트 기록은 collections 에 없다)
    select * into v_collection from public.collections where collection_id = v_collection_id;
    if not found or v_collection.user_id <> v_user_id or not v_collection.credited then
        perform private.app_error('NOT_ELIGIBLE', 403, '피드백할 수 있는 본인의 인정된 수집 기록이 아닙니다');
    end if;

    -- 4) 수집 1건당 피드백 1건
    if exists (select 1 from public.feedbacks where collection_id = v_collection_id) then
        perform private.app_error('FEEDBACK_EXISTS', 409, '이미 피드백한 수집입니다');
    end if;

    -- 5) 현장 수집 후 600초 이하 (경계 포함)
    if v_submitted_at - v_collection.collected_at > interval '600 seconds' then
        perform private.app_error('FEEDBACK_EXPIRED', 400, '수집 후 10분이 지났습니다');
    end if;

    -- 6) KST 제출일 하루 3건
    select count(*) into v_today_count
    from public.feedbacks
    where user_id = v_user_id and submitted_kst_date = v_today;

    if v_today_count >= 3 then
        perform private.app_error('DAILY_LIMIT', 429, '오늘은 피드백을 3건까지 낼 수 있습니다');
    end if;

    -- 7) 저장 + 보상
    select food_balance into v_food from public.pets where user_id = v_user_id;

    v_result := jsonb_build_object(
        'feedbackId',  v_feedback_id,
        'submittedAt', v_submitted_at,
        'todayCount',  v_today_count + 1,
        'grants',      jsonb_build_array(jsonb_build_object('reason', 'FEEDBACK', 'food', 5)),
        'foodBalance', v_food + 5,
        'replayed',    false
    );

    insert into public.feedbacks (
        feedback_id, user_id, collection_id, sensor_no, collected_at,
        felt_temp, felt_humidity, issues, memo,
        submitted_at, submitted_kst_date, input_fingerprint, result
    ) values (
        v_feedback_id, v_user_id, v_collection_id, v_collection.sensor_no, v_collection.collected_at,
        v_felt_temp, v_felt_humidity, v_issues, v_memo,
        v_submitted_at, v_today, v_fingerprint, v_result
    );

    perform private.grant_food(v_user_id, v_feedback_id, 'FEEDBACK', 5);

    return v_result;
end;
$$;

-- ============================================================
-- 3. 권한과 설명
-- ============================================================
revoke all on function public.submit_feedback(jsonb) from public;
grant execute on function public.submit_feedback(jsonb) to anon, authenticated;

comment on function public.submit_feedback(jsonb) is
    'submitFeedback (§09). p_input = 계약서 §09 요청 JSON. 반환: FeedbackResult. 실패: INVALID_INPUT, ID_CONFLICT, USER_NOT_FOUND, NOT_ELIGIBLE, FEEDBACK_EXISTS, FEEDBACK_EXPIRED, DAILY_LIMIT';
