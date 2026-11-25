# CET 백엔드 (③ 곽예찬)

Supabase(PostgreSQL) 팀 DB. **기준 문서는 [`docs/api-contract.md`](../docs/api-contract.md) (계약서 v1.2)** 이다.

앱은 `com.opensrc.cet.db.TeamDb`의 메서드를 호출하고, 그 구현이 아래 SQL 함수를 부른다.

```
앱 화면 → TeamDb (Java, com.opensrc.cet.db) → Supabase SQL 함수 (이 폴더) → DB
```

## 앱에서 쓰는 법 (팀원용)

### 1. 접속 정보 넣기 (처음 한 번)
프로젝트 루트의 `local.properties`(커밋하지 않는 파일, Android Studio가 `sdk.dir`을 넣어둔 파일)에 두 줄을 추가한다. 값은 곽예찬에게 받는다.
```properties
supabase.url=https://<project>.supabase.co
supabase.key=sb_publishable_...
```
빌드하면 `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_KEY`로 들어간다.

### 2. 팀 DB 호출 (`com.opensrc.cet.db`)
```java
TeamDb db = new SupabaseTeamDb(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, false);
TeamDb testDb = new SupabaseTeamDb(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, true); // 테스트 모드 (DC-7)

db.feedPet(userId, requestId, result -> {          // 콜백은 메인 스레드
    if (result.ok) {
        showPet(result.data.pet);
        if (result.data.evolved) playEvolution();
    } else if (result.error.code == TeamDbErrorCode.INSUFFICIENT_FOOD) {
        disableFeedButton();
    } else if (result.error.retryable) {
        // DB_UNAVAILABLE: 같은 requestId 로 다시 보내면 된다
    }
});
```
- 쓰기 요청(`registerUser`, `commitCollection`, `submitFeedback`, `feedPet`, `drawCostume`, `equipCostume`)은
  **재시도할 때 같은 ID**를 쓴다. 이미 처리된 요청이면 처음 결과가 `replayed = true`로 온다.
- 테스트 모드 인스턴스는 `commitCollection`을 테스트 저장 공간에만 기록한다. 운영 먹이·음영·랭킹은 그대로다.

### 3. 수업 공용 서버 (`com.opensrc.cet.publicapi`)
```java
PublicApiClient pub = new PublicApiClient();
pub.fetchMap(result -> { if (result.ok) show(result.rows); else showCache(result.error); });   // API 1
pub.upload(measurement, deviceId, result -> {                                                  // API 2
    switch (result.status) {
        case SUCCESS:     /* result.publicId 저장 → commitCollection(UPLOADED, publicId) */ break;
        case INPUT_ERROR: /* 같은 값으로 재전송 금지, 사유 표시 */ break;
        case NOT_SENT:    /* 5초 간격 최대 3회 재시도 후 대기열 */ break;
        case UNKNOWN:     /* PUBLIC_UNKNOWN 보류, 자동 재전송·보상 없음 */ break;
    }
});
```
- 31·32번(수리 전)은 `upload`를 부르지 않고 `commitCollection(TEMP_UNAVAILABLE, null)`만 한다.
- 공용 서버는 http 라서 `res/xml/network_security_config.xml`에서 그 주소만 허용했다.

## 계약서 메서드와 SQL 함수

| 계약서 메서드 | SQL 함수 (`POST /rest/v1/rpc/…`) | 상태 |
|---|---|---|
| `registerUser` | `register_user(p_request_id, p_nickname)` | ✅ |
| `loginByNickname` | `login_by_nickname(p_nickname)` | ✅ |
| `getUserState` | `get_user_state(p_user_id)` | ✅ |
| `commitCollection` | `commit_collection(p_input, p_test)` — `p_input`은 계약서 §06 요청 JSON 그대로 | ✅ |
| `getShadowSites` | `get_shadow_sites()` | ✅ |
| `submitFeedback` | `submit_feedback(p_input)` — `p_input`은 계약서 §09 요청 JSON 그대로 | ✅ |
| `feedPet` | `feed_pet(p_user_id, p_request_id)` | ✅ |
| `drawCostume` | `draw_costume(p_user_id, p_request_id)` | ✅ |
| `equipCostume` | `equip_costume(p_user_id, p_request_id, p_costume)` — `null`이면 벗기기 | ✅ |
| `getRanking` | `get_ranking(p_user_id)` | ✅ |
| `getSiteEnvironment` | `get_site_environment(p_sensor_no)` | ✅ |
| `getAnalysisInputs` | `get_analysis_inputs(p_as_of_date)` — `"YYYY-MM-DD"` (KST 조회일) | ✅ |

- 응답 JSON의 필드 이름은 계약서와 같다 (`userId`, `foodBalance`, `stage: "EGG"` …).
- 실패: HTTP 4xx + `{ "message": "<계약서 오류 코드>", "details": "<설명>" }`.
  Java 구현이 `message`를 `TeamDbErrorCode`로 바꿔 `TeamDbResult.failure`로 돌려준다.
- 로그인 토큰 없이 Publishable key(anon)로 호출한다. 표는 직접 읽거나 쓸 수 없고 함수만 호출할 수 있다.

## 폴더

```
backend/
├── supabase/migrations/   # DB 변경 기록. 파일 이름 순서대로 한 번씩만 실행
│   ├── 20261118 ~ 20261122_*.sql            # (폐기) 익명 로그인 방식의 초기 구조
│   ├── 20261125000000_contract_v12_users.sql        # 계약서 v1.2 재구성: 사용자·펫·닉네임 접속
│   ├── 20261125010000_contract_v12_collections.sql  # 수집 저장·음영지역·수집 보상
│   ├── 20261125020000_contract_v12_feedback.sql     # 현장 피드백·보상
│   ├── 20261125030000_contract_v12_pet.sql          # 먹이 주기·장식 뽑기·착용·랭킹
│   └── 20261125040000_contract_v12_queries.sql      # 선택 지점 환경값·집계 원자료 조회
├── supabase/dev_reset.sql   # ⚠️ 개발용: 운영 데이터 전체 초기화 (필요할 때만 수동 실행)
└── tests/
    ├── api.http        # REST Client 테스트 (위에서부터 순서대로 Send Request)
    └── .env.example    # .env 로 복사해 URL·키 입력 (.env 는 커밋 안 함)
```

## 작업 규칙

1. 마이그레이션은 **새 파일**로 만든다. 이미 실행한 파일은 고치지 않는다.
2. Supabase SQL Editor 새 탭에 붙여넣고 Run (= 배포).
3. `tests/api.http`로 확인한 뒤 커밋한다.
4. 테스트 데이터 정리: `supabase/dev_reset.sql` 실행 (사용자·수집·측정·지급 전부 삭제, 센서 측정 수 0)

## Java 테스트

| 파일 | 내용 |
|---|---|
| `app/src/test/.../db/SupabaseTeamDbTest.java` | 가짜 응답으로 요청 형식·응답 해석·오류 코드 변환 (인터넷 불필요) |
| `app/src/test/.../publicapi/PublicApiClientTest.java` | 공용 API 쿼리 형식(계약서 §05 예시)·결과 분류·지도 시각 해석 |
| `app/src/test/.../db/SupabaseTeamDbLiveTest.java` | 실제 Supabase로 계약서 §13 "첫 공동 시연" 흐름. 환경 변수 `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`가 있을 때만 실행 |

실행: `./gradlew :app:testDebugUnitTest`
(프로젝트 경로에 한글이 있으면 Gradle 테스트 실행기가 실패할 수 있다. 영문 경로에서 실행한다.)
