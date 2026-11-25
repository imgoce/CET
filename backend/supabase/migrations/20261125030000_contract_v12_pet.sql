-- 계약서 v1.2 기준 DB 재구성 (4/5): 먹이 주기·장식 뽑기·착용·랭킹
--
-- 기준: docs/api-contract.md §11, §13, §14
-- 요구사항: GR-1, GR-2, GR-3, GR-4, CS-1, CS-2, CS-2-1, CS-2-2, CS-3, CS-4, RK-1, RK-2
-- 같은 규칙의 Java 구현: ⑤ 권기찬 PetGrowth / PetCostumes / PetRanking (결과가 같아야 한다)
--
-- 변경 3종(feed_pet, draw_costume, equip_costume)은 (userId, requestId) 로 한 번만 반영한다.
--   - 성공 결과를 pet_requests 에 보관하고, 같은 요청이 다시 오면 그 결과를 돌려준다 (replayed=true).
--   - 같은 requestId 에 다른 작업·입력이 오면 ID_CONFLICT.
--   - 펫 행 잠금으로 같은 사용자의 변경을 한 건씩 처리한다 (잔액 1에서 두 폰이 동시에 먹여도 1번만).

-- ============================================================
-- 1. 변경 요청 기록 (§14 성장·장식 변경 / 재전송)
-- ============================================================
create table public.pet_requests (
    user_id     uuid        not null references public.users(user_id) on delete cascade,
    request_id  uuid        not null,
    input       jsonb       not null,   -- {"kind": "FEED" | "DRAW" | "EQUIP", "costume": …}
    result      jsonb       not null,   -- 처음 돌려준 응답
    created_at  timestamptz not null default now(),
    primary key (user_id, request_id)
);

alter table public.pet_requests enable row level security;
revoke all on public.pet_requests from anon, authenticated;

-- 공통 시작 처리: 펫 잠금 → 재전송 확인. 처음 요청이면 null, 재전송이면 처음 결과(replayed=true)
create function private.begin_pet_request(p_user_id uuid, p_request_id uuid, p_input jsonb)
returns jsonb
language plpgsql
set search_path = public
as $$
declare
    v_prev record;
begin
    if p_user_id is null or p_request_id is null then
        perform private.app_error('INVALID_INPUT', 400, 'userId와 requestId가 필요합니다');
    end if;

    perform 1 from public.pets where user_id = p_user_id for update;
    if not found then
        perform private.app_error('USER_NOT_FOUND', 404, '알 수 없는 사용자입니다');
    end if;

    select input, result into v_prev
    from public.pet_requests
    where user_id = p_user_id and request_id = p_request_id;

    if not found then
        return null;
    end if;
    if v_prev.input <> p_input then
        perform private.app_error('ID_CONFLICT', 409, '이미 다른 요청에 사용한 requestId입니다');
    end if;
    return v_prev.result || jsonb_build_object('replayed', true);
end;
$$;

create function private.finish_pet_request(p_user_id uuid, p_request_id uuid, p_input jsonb, p_result jsonb)
returns jsonb
language sql
set search_path = public
as $$
    insert into public.pet_requests (user_id, request_id, input, result)
    values (p_user_id, p_request_id, p_input, p_result)
    returning result;
$$;

-- ============================================================
-- 2. feedPet({userId, requestId}) → { pet, leveledUp, evolved, replayed }
--    먹이 1개 → 진행도 +1. 레벨 L 에서 진행도가 L+1 이 되면 레벨 +1, 진행도 0.
--    실패: INSUFFICIENT_FOOD, USER_NOT_FOUND, ID_CONFLICT
-- ============================================================
create function public.feed_pet(p_user_id uuid, p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_input      jsonb := jsonb_build_object('kind', 'FEED');
    v_replay     jsonb;
    v_pet        public.pets;
    v_leveled_up boolean := false;
    v_old_stage  text;
begin
    v_replay := private.begin_pet_request(p_user_id, p_request_id, v_input);
    if v_replay is not null then
        return v_replay;
    end if;

    select * into v_pet from public.pets where user_id = p_user_id;
    if v_pet.food_balance < 1 then
        perform private.app_error('INSUFFICIENT_FOOD', 400, '보유 먹이가 없습니다');
    end if;

    v_old_stage := private.stage_for_level(v_pet.level);
    if v_pet.progress = v_pet.level then          -- 이번 1개로 L+1 달성
        v_pet.level := v_pet.level + 1;
        v_pet.progress := 0;
        v_leveled_up := true;
    else
        v_pet.progress := v_pet.progress + 1;
    end if;

    update public.pets
    set food_balance = food_balance - 1, level = v_pet.level, progress = v_pet.progress, updated_at = now()
    where user_id = p_user_id;

    return private.finish_pet_request(p_user_id, p_request_id, v_input, jsonb_build_object(
        'pet',       private.pet_state(p_user_id),
        'leveledUp', v_leveled_up,
        'evolved',   private.stage_for_level(v_pet.level) <> v_old_stage,
        'replayed',  false
    ));
end;
$$;

-- ============================================================
-- 3. drawCostume({userId, requestId}) → { pet, drawnCostume, spentFood, replayed }
--    Lv 16 이상 · 미보유 장식 있음 · 잔액 ≥ 24 + drawCount. 미보유 중 균등 추첨.
--    재전송은 다시 추첨하지 않는다.
--    실패(검사 순서): LEVEL_LOCKED → ALL_OWNED → INSUFFICIENT_FOOD
-- ============================================================
create function public.draw_costume(p_user_id uuid, p_request_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_input   jsonb := jsonb_build_object('kind', 'DRAW');
    v_replay  jsonb;
    v_pet     public.pets;
    v_cost    bigint;
    v_drawn   text;
begin
    v_replay := private.begin_pet_request(p_user_id, p_request_id, v_input);
    if v_replay is not null then
        return v_replay;
    end if;

    select * into v_pet from public.pets where user_id = p_user_id;
    v_cost := 24 + v_pet.draw_count;

    if v_pet.level < 16 then
        perform private.app_error('LEVEL_LOCKED', 403, '성체(Lv 16)부터 뽑기를 할 수 있습니다');
    end if;

    select c into v_drawn
    from unnest(array['SPROUT', 'RIBBON', 'STAR', 'CROWN']) c
    where not exists (select 1 from public.owned_costumes o where o.user_id = p_user_id and o.costume = c)
    order by random()
    limit 1;

    if v_drawn is null then
        perform private.app_error('ALL_OWNED', 409, '모든 장식을 이미 가지고 있습니다');
    end if;
    if v_pet.food_balance < v_cost then
        perform private.app_error('INSUFFICIENT_FOOD', 400, '뽑기 비용만큼 먹이가 없습니다');
    end if;

    insert into public.owned_costumes (user_id, costume) values (p_user_id, v_drawn);
    update public.pets
    set food_balance = food_balance - v_cost, draw_count = draw_count + 1, updated_at = now()
    where user_id = p_user_id;

    return private.finish_pet_request(p_user_id, p_request_id, v_input, jsonb_build_object(
        'pet',          private.pet_state(p_user_id),
        'drawnCostume', v_drawn,
        'spentFood',    v_cost,
        'replayed',     false
    ));
end;
$$;

-- ============================================================
-- 4. equipCostume({userId, requestId, costume}) → { pet, replayed }
--    costume = null 이면 벗기기. 한 번에 1개만 착용.
--    실패: INVALID_INPUT(없는 코드), NOT_OWNED
-- ============================================================
create function public.equip_costume(p_user_id uuid, p_request_id uuid, p_costume text default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_input  jsonb := jsonb_build_object('kind', 'EQUIP', 'costume', p_costume);
    v_replay jsonb;
begin
    v_replay := private.begin_pet_request(p_user_id, p_request_id, v_input);
    if v_replay is not null then
        return v_replay;
    end if;

    if p_costume is not null then
        if p_costume not in ('SPROUT', 'RIBBON', 'STAR', 'CROWN') then
            perform private.app_error('INVALID_INPUT', 400, '알 수 없는 장식 코드입니다');
        end if;
        if not exists (select 1 from public.owned_costumes where user_id = p_user_id and costume = p_costume) then
            perform private.app_error('NOT_OWNED', 403, '보유하지 않은 장식입니다');
        end if;
    end if;

    update public.pets set equipped_costume = p_costume, updated_at = now() where user_id = p_user_id;

    return private.finish_pet_request(p_user_id, p_request_id, v_input, jsonb_build_object(
        'pet',      private.pet_state(p_user_id),
        'replayed', false
    ));
end;
$$;

-- ============================================================
-- 5. getRanking({userId}) → { top: RankRow[≤50], me: RankRow }
--    레벨·진행도 내림차순, 같으면 공동 순위(1, 2, 2, 4).
--    같은 순위의 표시 순서는 NFC 닉네임 코드포인트 오름차순 (COLLATE "C" = 코드포인트 순).
--    사용자 UUID 는 응답에 넣지 않는다.
-- ============================================================
create function public.get_ranking(p_user_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_result jsonb;
begin
    if p_user_id is null or not exists (select 1 from public.users where user_id = p_user_id) then
        perform private.app_error('USER_NOT_FOUND', 404, '알 수 없는 사용자입니다');
    end if;

    with ranked as (
        select
            u.user_id,
            jsonb_build_object(
                'rank',     rank() over (order by p.level desc, p.progress desc),
                'nickname', u.nickname,
                'level',    p.level,
                'progress', p.progress,
                'stage',    private.stage_for_level(p.level)
            ) as row,
            row_number() over (order by p.level desc, p.progress desc, u.nickname collate "C") as pos
        from public.users u
        join public.pets p using (user_id)
    )
    select jsonb_build_object(
        'top', coalesce((select jsonb_agg(row order by pos) from ranked where pos <= 50), '[]'::jsonb),
        'me',  (select row from ranked where user_id = p_user_id)
    )
    into v_result;

    return v_result;
end;
$$;

-- ============================================================
-- 6. 권한과 설명
-- ============================================================
revoke all on function public.feed_pet(uuid, uuid)            from public;
revoke all on function public.draw_costume(uuid, uuid)        from public;
revoke all on function public.equip_costume(uuid, uuid, text) from public;
revoke all on function public.get_ranking(uuid)               from public;

grant execute on function public.feed_pet(uuid, uuid)            to anon, authenticated;
grant execute on function public.draw_costume(uuid, uuid)        to anon, authenticated;
grant execute on function public.equip_costume(uuid, uuid, text) to anon, authenticated;
grant execute on function public.get_ranking(uuid)               to anon, authenticated;

comment on function public.feed_pet(uuid, uuid) is
    'feedPet (§11). 반환: {pet, leveledUp, evolved, replayed}. 실패: INSUFFICIENT_FOOD, USER_NOT_FOUND, ID_CONFLICT';
comment on function public.draw_costume(uuid, uuid) is
    'drawCostume (§11). 반환: {pet, drawnCostume, spentFood, replayed}. 실패: LEVEL_LOCKED, ALL_OWNED, INSUFFICIENT_FOOD, USER_NOT_FOUND, ID_CONFLICT';
comment on function public.equip_costume(uuid, uuid, text) is
    'equipCostume (§11). p_costume = null 이면 벗기기. 반환: {pet, replayed}. 실패: INVALID_INPUT, NOT_OWNED, USER_NOT_FOUND, ID_CONFLICT';
comment on function public.get_ranking(uuid) is
    'getRanking (§11). 반환: {top: RankRow[≤50], me: RankRow}. 공동 순위. 실패: USER_NOT_FOUND';
