-- ⚠️ 개발용 초기화: 사용자·펫·수집·측정·지급 등 모든 운영 데이터를 지우고
--    센서별 측정 수를 0으로 되돌린다. 표 구조와 함수는 그대로 남는다.
--
-- 언제: 개발 중 테스트 데이터가 음영지역 순위·랭킹을 오염시켰을 때, 시연·14주차 테스트 직전
-- 어떻게: Supabase SQL Editor 새 탭에 붙여넣고 Run (마이그레이션이 아니다. 필요할 때만 실행)
--
-- TRUNCATE 는 "수정·삭제 금지" 트리거를 거치지 않으므로 이 스크립트로만 기록을 지울 수 있다.
-- 실제 사용자가 생긴 뒤에는 절대 실행하지 않는다.

truncate table
    public.grants,
    public.first_sensor_days,
    public.test_collections,
    public.collections,
    public.measurements,
    public.register_requests,
    public.pets,
    public.owned_costumes,
    public.users
restart identity cascade;

update public.sensors set unique_measurements = 0;
