-- 먹이 주기·레벨업·진화 (FR-05-09, NFR-05-01, NFR-05-02)
--
-- 규칙
--   - 먹이 1개를 주면 보유 먹이 -1, 현재 레벨에서 먹인 수 +1
--   - 현재 레벨이 n일 때 먹인 수가 n+1 에 도달하면 레벨 +1, 먹인 수 0 (Lv1→2: 2개, Lv2→3: 3개 …)
--   - 진화 단계는 settings 의 레벨 기준을 따른다 (임시값: 새끼 3, 해츨링 6, 성체 10)
--   - 앱은 요청마다 p_request_id(UUID)를 새로 만든다. 같은 값으로 다시 보내면
--     먹이를 다시 쓰지 않고 처음 결과를 그대로 돌려준다 (응답을 못 받아 재전송한 경우).

-- ============================================================
-- 1. 진화 레벨 설정 (팀 결정 전 임시값)
-- ============================================================
insert into public.settings (key, value, description) values
    ('stage_baby_level',      3,  '새끼로 진화하는 레벨 (GR-3, 임시값)'),
    ('stage_hatchling_level', 6,  '해츨링으로 진화하는 레벨 (GR-3, 임시값)'),
    ('stage_adult_level',     10, '성체로 진화하는 레벨 (GR-3, 임시값)');

create function private.stage_for_level(p_level integer)
returns text
language sql
stable
set search_path = public
as $$
    select case
        when p_level >= private.setting('stage_adult_level')     then 'adult'
        when p_level >= private.setting('stage_hatchling_level') then 'hatchling'
        when p_level >= private.setting('stage_baby_level')      then 'baby'
        else 'egg'
    end;
$$;

-- ============================================================
-- 2. 먹이 주기 요청 기록 (같은 요청 ID 중복 처리 방지)
-- ============================================================
create table public.feed_requests (
    request_id  uuid        primary key,
    user_id     uuid        not null references public.users(id) on delete cascade,
    result      jsonb       not null,   -- 처음 처리했을 때 돌려준 응답
    created_at  timestamptz not null default now()
);

comment on table public.feed_requests is '처리한 먹이 주기 요청. 재전송 시 같은 결과를 돌려주기 위해 보관';

alter table public.feed_requests enable row level security;
create policy feed_requests_read_own on public.feed_requests
    for select to authenticated using (user_id = auth.uid());

-- ============================================================
-- 3. 먹이 주기
--    반환: { level, feed_in_level, feed_needed, stage, food, leveled_up, evolved }
-- ============================================================
create function public.feed_pet(p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid        uuid := public.require_uid();
    v_pet        public.pets;
    v_prev       record;
    v_old_stage  text;
    v_leveled_up boolean := false;
    v_result     jsonb;
begin
    if p_request_id is null then
        perform private.app_error('INVALID_VALUE', 400, 'p_request_id가 필요합니다');
    end if;

    -- 펫 행을 잠가 같은 사용자의 요청을 하나씩 처리한다 (동시 요청 대비)
    select * into v_pet from public.pets where user_id = v_uid for update;
    if not found then
        perform private.app_error('NOT_REGISTERED', 403, '닉네임 등록 후 이용할 수 있습니다');
    end if;

    -- 이미 처리한 요청이면 처음 결과를 그대로 반환
    select user_id, result into v_prev from public.feed_requests where request_id = p_request_id;
    if found then
        if v_prev.user_id <> v_uid then
            perform private.app_error('REQUEST_ID_REUSED', 409, '이미 사용된 요청 ID입니다');
        end if;
        return v_prev.result;
    end if;

    if v_pet.food < 1 then
        perform private.app_error('NOT_ENOUGH_FOOD', 400, '보유 먹이가 부족합니다');
    end if;

    v_old_stage         := v_pet.stage;
    v_pet.food          := v_pet.food - 1;
    v_pet.feed_in_level := v_pet.feed_in_level + 1;

    if v_pet.feed_in_level >= v_pet.level + 1 then
        v_pet.level         := v_pet.level + 1;
        v_pet.feed_in_level := 0;
        v_leveled_up        := true;
    end if;

    v_pet.stage := private.stage_for_level(v_pet.level);

    update public.pets
    set food = v_pet.food,
        feed_in_level = v_pet.feed_in_level,
        level = v_pet.level,
        stage = v_pet.stage,
        updated_at = now()
    where user_id = v_uid;

    v_result := jsonb_build_object(
        'level',         v_pet.level,
        'feed_in_level', v_pet.feed_in_level,
        'feed_needed',   v_pet.level + 1,
        'stage',         v_pet.stage,
        'food',          v_pet.food,
        'leveled_up',    v_leveled_up,
        'evolved',       v_pet.stage <> v_old_stage
    );

    insert into public.feed_requests (request_id, user_id, result)
    values (p_request_id, v_uid, v_result);

    return v_result;
end;
$$;

-- ============================================================
-- 4. 권한과 설명
-- ============================================================
revoke all on function public.feed_pet(uuid) from public, anon;
grant execute on function public.feed_pet(uuid) to authenticated;

comment on function public.feed_pet(uuid) is
    '먹이 1개 주기 + 레벨업·진화 (FR-05-09). p_request_id는 요청마다 새 UUID, 재전송 시 같은 값. 반환: level, feed_in_level, feed_needed, stage, food, leveled_up, evolved. 실패: NOT_REGISTERED, NOT_ENOUGH_FOOD, REQUEST_ID_REUSED, INVALID_VALUE';
