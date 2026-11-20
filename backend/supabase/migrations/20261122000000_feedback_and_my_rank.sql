-- 피드백 저장·보상 (FR-05-07), 하루 경계 KST 0시 (FR-05-08), 내 순위 (FR-05-11)
--
-- 피드백 제출 조건 (모두 만족해야 저장)
--   1. 본인의 실제 모드 수집 기록
--   2. 수집 기록이 서버에 저장된 지 feedback_window_minutes(10)분 이내
--   3. 그 수집 기록에 대한 첫 피드백 (기록 1건당 피드백 1건)
--   4. 오늘(KST 0시 기준) 제출한 피드백이 feedback_daily_limit(3)회 미만
-- 저장되면 먹이 reward_feedback_food(5)개 지급. 응답 내용(불편/괜찮음)과 상관없이 같다.

-- ============================================================
-- 1. 설정값 (불편 사항 선택지·메모 길이는 팀 결정 전 초안 값, C-10)
-- ============================================================
insert into public.settings (key, value, description) values
    ('reward_feedback_food',      5,   '피드백 1건 보상 먹이 수 (FR-05-07, RW-4)'),
    ('feedback_daily_limit',      3,   '하루(KST 0시 기준) 피드백 제출 한도 (FR-05-07, FB-2-1)'),
    ('feedback_window_minutes',   10,  '수집 후 피드백 제출 가능 시간, 분 (FR-05-07, FB-2-1)'),
    ('feedback_memo_max_length',  100, '피드백 메모 최대 글자 수 (FB-2, 초안 값)');

-- 하루 경계: KST 0시 (FR-05-08)
create function private.kst_date(p_ts timestamptz)
returns date
language sql
immutable
as $$
    select (p_ts at time zone 'Asia/Seoul')::date;
$$;

-- ============================================================
-- 2. 불편 사항 선택지 (앱은 이 표를 읽어 체크박스를 만든다)
--    선택지가 바뀌면 이 표에 행을 추가·수정하면 된다.
-- ============================================================
create table public.feedback_discomfort_options (
    code        text    primary key,
    label       text    not null,
    sort_order  integer not null default 0
);

insert into public.feedback_discomfort_options (code, label, sort_order) values
    ('no_shade',    '그늘 없음',    1),
    ('strong_wind', '바람 강함',    2),
    ('smell',       '냄새',         3),
    ('no_seat',     '앉을 곳 없음', 4),
    ('other',       '기타',         5);

alter table public.feedback_discomfort_options enable row level security;
create policy feedback_discomfort_options_read_all on public.feedback_discomfort_options
    for select to authenticated using (true);

-- ============================================================
-- 3. 피드백 (FB-3)
-- ============================================================
create table public.feedbacks (
    id                    bigint      generated always as identity primary key,
    user_id               uuid        not null references public.users(id) on delete cascade,
    collection_record_id  bigint      not null unique                  -- 기록 1건당 피드백 1건
                          references public.collection_records(id) on delete cascade,
    sensor_name           text        not null,
    temp_feel             text        check (temp_feel in ('cold', 'ok', 'hot')),
    humidity_feel         text        check (humidity_feel in ('dry', 'ok', 'humid')),
    discomforts           text[]      not null default '{}',
    memo                  text,
    -- 집계용 불편 응답 여부: '적당하다'가 아닌 응답이 하나라도 있거나 불편 사항을 1개 이상 고름
    is_discomfort         boolean     generated always as (
                              coalesce(temp_feel <> 'ok', false)
                           or coalesce(humidity_feel <> 'ok', false)
                           or cardinality(discomforts) > 0
                          ) stored,
    submitted_date        date        not null,                         -- KST 기준 제출 날짜
    created_at            timestamptz not null default now()
);

create index feedbacks_user_date_idx   on public.feedbacks (user_id, submitted_date);
create index feedbacks_sensor_time_idx on public.feedbacks (sensor_name, created_at);

alter table public.feedbacks enable row level security;
create policy feedbacks_read_own on public.feedbacks
    for select to authenticated using (user_id = auth.uid());

-- 보상 이력의 feedback_id 를 피드백과 연결
alter table public.reward_history
    add constraint reward_history_feedback_id_fkey
    foreign key (feedback_id) references public.feedbacks(id) on delete cascade;

-- ============================================================
-- 4. 피드백 제출 + 보상
--    반환: { feedback_id, rewards: [{ food, reason: "feedback" }], food, remaining_today }
-- ============================================================
create function public.submit_feedback(
    p_collection_record_id bigint,
    p_temp_feel            text    default null,
    p_humidity_feel        text    default null,
    p_discomforts          text[]  default '{}',
    p_memo                 text    default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid         uuid := public.require_uid();
    v_record      public.collection_records;
    v_discomforts text[] := coalesce(p_discomforts, '{}');
    v_memo        text := nullif(btrim(p_memo), '');
    v_today       date := private.kst_date(now());
    v_limit       integer := private.setting('feedback_daily_limit');
    v_today_count integer;
    v_feedback_id bigint;
    v_reward_food integer := private.setting('reward_feedback_food');
    v_food        integer;
begin
    -- 같은 사용자의 요청을 하나씩 처리 (일일 한도 동시 요청 대비)
    perform 1 from public.pets where user_id = v_uid for update;
    if not found then
        perform private.app_error('NOT_REGISTERED', 403, '닉네임 등록 후 이용할 수 있습니다');
    end if;

    -- 1) 입력값
    if p_temp_feel is null and p_humidity_feel is null and cardinality(v_discomforts) = 0 then
        perform private.app_error('INVALID_VALUE', 400, '체감 온도·체감 습도·불편 사항 중 1개 이상 선택해야 합니다');
    end if;
    if p_temp_feel is not null and p_temp_feel not in ('cold', 'ok', 'hot') then
        perform private.app_error('INVALID_VALUE', 400, 'p_temp_feel은 cold, ok, hot 중 하나입니다');
    end if;
    if p_humidity_feel is not null and p_humidity_feel not in ('dry', 'ok', 'humid') then
        perform private.app_error('INVALID_VALUE', 400, 'p_humidity_feel은 dry, ok, humid 중 하나입니다');
    end if;
    if exists (
        select 1 from unnest(v_discomforts) d
        where d not in (select code from public.feedback_discomfort_options)
    ) then
        perform private.app_error('INVALID_VALUE', 400, '알 수 없는 불편 사항 코드가 있습니다');
    end if;
    if char_length(v_memo) > private.setting('feedback_memo_max_length') then
        perform private.app_error('INVALID_VALUE', 400, '메모가 최대 길이를 넘었습니다');
    end if;

    -- 2) 연결할 수집 기록: 본인의 실제 모드 기록
    select * into v_record from public.collection_records where id = p_collection_record_id;
    if v_record.id is null or v_record.user_id <> v_uid then
        perform private.app_error('RECORD_OF_OTHER_USER', 403, '본인의 수집 기록이 아닙니다');
    end if;
    if v_record.is_test then
        perform private.app_error('INVALID_VALUE', 400, '테스트 모드 기록에는 피드백할 수 없습니다');
    end if;

    -- 3) 기록 1건당 1번
    if exists (select 1 from public.feedbacks where collection_record_id = v_record.id) then
        perform private.app_error('FEEDBACK_DUPLICATE', 409, '이미 피드백한 수집 기록입니다');
    end if;

    -- 4) 수집 후 N분 이내 (서버 저장 시각 기준)
    if now() - v_record.created_at > make_interval(mins => private.setting('feedback_window_minutes')) then
        perform private.app_error('FEEDBACK_EXPIRED', 400, '피드백 제출 가능 시간이 지났습니다');
    end if;

    -- 5) 오늘(KST) 제출 횟수
    select count(*) into v_today_count
    from public.feedbacks
    where user_id = v_uid and submitted_date = v_today;

    if v_today_count >= v_limit then
        perform private.app_error('FEEDBACK_DAILY_LIMIT', 429, '오늘은 더 이상 피드백을 제출할 수 없습니다');
    end if;

    -- 저장
    begin
        insert into public.feedbacks (
            user_id, collection_record_id, sensor_name,
            temp_feel, humidity_feel, discomforts, memo, submitted_date
        ) values (
            v_uid, v_record.id, v_record.sensor_name,
            p_temp_feel, p_humidity_feel, v_discomforts, v_memo, v_today
        )
        returning id into v_feedback_id;
    exception when unique_violation then
        perform private.app_error('FEEDBACK_DUPLICATE', 409, '이미 피드백한 수집 기록입니다');
    end;

    -- 보상
    insert into public.reward_history (user_id, reason, food, feedback_id)
    values (v_uid, 'feedback', v_reward_food, v_feedback_id);

    update public.pets
    set food = food + v_reward_food, updated_at = now()
    where user_id = v_uid
    returning food into v_food;

    return jsonb_build_object(
        'feedback_id',     v_feedback_id,
        'rewards',         jsonb_build_array(jsonb_build_object('food', v_reward_food, 'reason', 'feedback')),
        'food',            v_food,
        'remaining_today', v_limit - v_today_count - 1
    );
end;
$$;

-- ============================================================
-- 5. 내 순위 (FR-05-11, RK-2) - 50위 밖이어도 반환
--    정렬 기준은 get_ranking 과 같아야 한다 (동률 기준 미정, 임시)
-- ============================================================
create function public.get_my_rank()
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_uid    uuid := public.require_uid();
    v_result jsonb;
begin
    with ranked as (
        select
            u.id, u.nickname, p.level, p.stage,
            row_number() over (order by p.level desc, p.feed_in_level desc, u.created_at asc) as rank
        from public.users u
        join public.pets p on p.user_id = u.id
    )
    select jsonb_build_object(
        'rank',     r.rank,
        'nickname', r.nickname,
        'level',    r.level,
        'stage',    r.stage,
        'total',    (select count(*) from ranked)
    )
    into v_result
    from ranked r
    where r.id = v_uid;

    if v_result is null then
        perform private.app_error('NOT_REGISTERED', 403, '닉네임 등록 후 이용할 수 있습니다');
    end if;

    return v_result;
end;
$$;

-- ============================================================
-- 6. 권한과 설명
-- ============================================================
revoke all on function public.submit_feedback(bigint, text, text, text[], text) from public, anon;
revoke all on function public.get_my_rank() from public, anon;

grant execute on function public.submit_feedback(bigint, text, text, text[], text) to authenticated;
grant execute on function public.get_my_rank() to authenticated;

comment on function public.submit_feedback(bigint, text, text, text[], text) is
    '피드백 제출 + 보상 (FR-05-07). 조건: 본인 실제 모드 기록, 수집 후 10분 이내, 기록당 1번, 오늘(KST) 3회 미만. 반환: feedback_id, rewards, food, remaining_today. 실패: NOT_REGISTERED, INVALID_VALUE, RECORD_OF_OTHER_USER, FEEDBACK_DUPLICATE, FEEDBACK_EXPIRED, FEEDBACK_DAILY_LIMIT';

comment on function public.get_my_rank() is
    '내 순위 (FR-05-11, RK-2). 반환: rank, nickname, level, stage, total. 실패: NOT_REGISTERED';
