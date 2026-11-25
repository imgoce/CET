-- 계약서 v1.2 기준 DB 재구성 (5/5): 선택 지점 환경값·집계 원자료 조회
--
-- 기준: docs/api-contract.md §10, §12
-- 요구사항: ES-1, ES-2-2, ES-4, SM-4, AG-1
--
-- 두 함수 모두 운영 데이터만 읽는다 (테스트 모드 기록은 test_collections 에만 있어 포함되지 않는다).

-- ============================================================
-- 1. getSiteEnvironment({sensorNo}) → SiteEnvironment (§10)
--    항목별 마지막 유효값과 그 값의 측정 시각을 각각 돌려준다.
--    측정이 없으면 성공 + 값·시각 모두 null. 값은 기한 없이 유지한다 (ES-2-2).
--    31·32 는 수리 확인 전까지 온습도의 과거 기록도 돌려주지 않는다.
--    실패: INVALID_INPUT (1~34 가 아닌 번호)
-- ============================================================
create function public.get_site_environment(p_sensor_no integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_sensor    public.sensors;
    v_temp      numeric;
    v_temp_at   timestamptz;
    v_hum       numeric;
    v_hum_at    timestamptz;
    v_eco2      integer;
    v_eco2_at   timestamptz;
begin
    select * into v_sensor from public.sensors where sensor_no = p_sensor_no;
    if not found then
        perform private.app_error('INVALID_INPUT', 400, '등록되지 않은 센서 번호입니다 (1~34)');
    end if;

    if not v_sensor.temp_humidity_unavailable then
        select temperature_c, measured_at into v_temp, v_temp_at
        from public.measurements
        where sensor_no = p_sensor_no and temperature_c is not null
        order by measured_at desc limit 1;

        select humidity_rh, measured_at into v_hum, v_hum_at
        from public.measurements
        where sensor_no = p_sensor_no and humidity_rh is not null
        order by measured_at desc limit 1;
    end if;

    select eco2_ppm, measured_at into v_eco2, v_eco2_at
    from public.measurements
    where sensor_no = p_sensor_no and eco2_ppm is not null
    order by measured_at desc limit 1;

    return jsonb_build_object(
        'sensorNo',           p_sensor_no,
        'siteName',           v_sensor.site_name,
        'temperatureC',       v_temp,
        'tempMeasuredAt',     v_temp_at,
        'humidityRh',         v_hum,
        'humidityMeasuredAt', v_hum_at,
        'eco2Ppm',            v_eco2,
        'eco2MeasuredAt',     v_eco2_at
    );
end;
$$;

-- ============================================================
-- 2. getAnalysisInputs({asOfDate}) → { samples: Sample[], feedbacks: FeedbackRow[] } (§12)
--    조회일(KST)과 직전 13일, 총 14개 KST 날짜의 운영 데이터.
--      - samples   : 센서·측정 시각별 고유 환경 표본, measuredAt 기준
--      - feedbacks : 유효 피드백, 연결 수집의 collectedAt 기준. 메모·사용자 식별값은 넣지 않는다.
--    시간대(09~21시) 구분·중앙값·후보 판정은 ④ aggregateSites 가 한다.
--    실패: INVALID_INPUT (asOfDate 없음)
-- ============================================================
create function public.get_analysis_inputs(p_as_of_date date)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_from timestamptz;
    v_to   timestamptz;
begin
    if p_as_of_date is null then
        perform private.app_error('INVALID_INPUT', 400, 'asOfDate(YYYY-MM-DD)가 필요합니다');
    end if;

    -- KST [조회일-13일 00:00, 조회일+1일 00:00)
    v_from := (p_as_of_date - 13)::timestamp at time zone 'Asia/Seoul';
    v_to   := (p_as_of_date + 1)::timestamp at time zone 'Asia/Seoul';

    return jsonb_build_object(
        'samples', coalesce((
            select jsonb_agg(jsonb_build_object(
                       'sensorNo',     m.sensor_no,
                       'measuredAt',   m.measured_at,
                       'temperatureC', m.temperature_c,
                       'humidityRh',   m.humidity_rh
                   ) order by m.sensor_no, m.measured_at)
            from public.measurements m
            where m.measured_at >= v_from and m.measured_at < v_to
        ), '[]'::jsonb),
        'feedbacks', coalesce((
            select jsonb_agg(jsonb_build_object(
                       'feedbackId',   f.feedback_id,
                       'sensorNo',     f.sensor_no,
                       'collectedAt',  f.collected_at,
                       'feltTemp',     f.felt_temp,
                       'feltHumidity', f.felt_humidity,
                       'issues',       to_jsonb(f.issues)
                   ) order by f.sensor_no, f.collected_at)
            from public.feedbacks f
            where f.collected_at >= v_from and f.collected_at < v_to
        ), '[]'::jsonb)
    );
end;
$$;

create index measurements_measured_at_idx on public.measurements (measured_at);

-- ============================================================
-- 3. 권한과 설명
-- ============================================================
revoke all on function public.get_site_environment(integer) from public;
revoke all on function public.get_analysis_inputs(date)     from public;

grant execute on function public.get_site_environment(integer) to anon, authenticated;
grant execute on function public.get_analysis_inputs(date)     to anon, authenticated;

comment on function public.get_site_environment(integer) is
    'getSiteEnvironment (§10). 항목별 마지막 유효값과 측정 시각. 31·32 온습도는 항상 null. 실패: INVALID_INPUT';
comment on function public.get_analysis_inputs(date) is
    'getAnalysisInputs (§12). 조회일 포함 최근 KST 14일의 samples·feedbacks. 실패: INVALID_INPUT';
