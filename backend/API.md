# CET 백엔드 API 명세

> 대상: 앱(안드로이드) 개발자 · 버전 v0.1 (2026-11-18) · 작성: 백엔드·DB 담당
>
> - **확정**: 구현과 테스트가 끝난 함수. 파라미터·반환값의 최신 상세는 Supabase 대시보드 **API Docs**가 기준이다.
> - **계획**: 아직 구현 전. 이 문서가 앱·백엔드 사이의 약속이며, 바뀌면 이 문서를 먼저 고친다.
> - 관련 요구사항: FR-05-01 ~ FR-05-12, NFR-05-01 ~ NFR-05-06

---

## 1. 접속

| 항목 | 값 |
|---|---|
| Project URL | 백엔드 담당에게 별도 전달 (`https://<project>.supabase.co`) |
| Publishable key | 백엔드 담당에게 별도 전달 (`sb_publishable_...`) |

- 두 값은 공개돼도 되지만 `local.properties` 등 **커밋하지 않는 파일**에 둔다.
- Secret key와 DB 비밀번호는 앱에 넣지 않는다.
- 앱은 **Java**. 일반 HTTP 라이브러리(Retrofit + OkHttp 등)로 호출한다. 예시는 [부록 A](#부록-a-java-예시-retrofit).

---

## 2. 앱 시작 흐름과 토큰 관리 (FR-05-01 ~ 03)

로그인 화면은 없다. 앱이 뒤에서 익명 세션을 만들고, **토큰을 기기에 직접 저장·갱신한다.**

### 받는 값 (익명 로그인·갱신 응답)
| 필드 | 설명 | 저장 |
|---|---|---|
| `access_token` | 모든 API 요청의 `Authorization: Bearer …`에 넣는 값. **1시간 뒤 만료** | SharedPreferences |
| `refresh_token` | 새 `access_token`을 받는 값. **한 번 쓰면 새 값으로 바뀐다** | SharedPreferences |
| `expires_at` | `access_token` 만료 시각 (Unix 초) | SharedPreferences |
| `user.id` | 사용자 UUID. 공용 API 2의 `sender`에 넣는다 | SharedPreferences |

### 흐름
```
앱 실행
 ├─ 저장된 refresh_token 없음 (첫 실행)
 │    └─ POST /auth/v1/signup  {}                         → 4개 값 저장
 ├─ 저장된 refresh_token 있음
 │    └─ POST /auth/v1/token?grant_type=refresh_token
 │         { "refresh_token": "…" }                       → 4개 값 새로 저장
 └─ rpc/get_my_profile
      ├─ []      → 닉네임 화면 → is_nickname_available → register_user
      └─ [1개]   → 펫 화면

API 호출 중 401 이 오면
 └─ 토큰 갱신(위 refresh) 후 같은 요청을 1번 재시도
```

- ⚠️ **401이 와도 `/auth/v1/signup`을 다시 부르지 않는다.** 새 계정이 만들어져 기존 펫에 접근할 수 없게 된다.
- 갱신 응답의 `refresh_token`은 **매번 새 값으로 덮어쓴다.** 예전 값은 다시 쓸 수 없다.
- 앱을 삭제하거나 기기를 바꾸면 저장된 토큰이 사라져 새 사용자가 된다 (현재 버전의 한계).

---

## 3. 공통 규칙

### 호출 방식
모든 함수는 `POST {URL}/rest/v1/rpc/{함수이름}`, 본문은 JSON, 헤더는 아래 3개.

```
apikey: {Publishable key}
Authorization: Bearer {access_token}
Content-Type: application/json
```

- 익명 로그인·토큰 갱신(`/auth/v1/...`)은 `apikey`만 넣고 `Authorization`은 넣지 않는다.
- 네트워크 호출은 메인 스레드에서 하지 않는다 (Retrofit `enqueue` 사용).

- 파라미터 이름은 모두 `p_` 로 시작한다.
- 시각은 ISO 8601 UTC 문자열로 주고받는다 (`2026-11-18T12:58:25Z`). 화면 표시는 앱에서 KST로 변환한다.
- 하루의 기준은 **KST 0시**다 (FR-05-08). 일일 한도는 서버가 계산한다.

### 재전송 안전
응답을 받지 못했으면 **같은 값으로 다시 보내도 된다.** 아래 함수는 두 번 반영되지 않는다.

| 함수 | 중복 판단 기준 |
|---|---|
| `add_collection_record` | `p_public_server_id` |
| `feed_pet`, `draw_costume` (계획) | `p_request_id` — 앱이 요청마다 UUID를 새로 만들고, 재전송 때는 같은 값을 쓴다 |

---

## 4. 실패 응답 (FR-05-12)

실패하면 HTTP 4xx와 함께 아래 JSON이 온다. **앱은 `message`의 오류 키로 구분한다.**

```json
{ "code": "PT409", "message": "NICKNAME_TAKEN", "details": "이미 사용 중인 닉네임입니다", "hint": null }
```

| HTTP | 오류 키 (`message`) | 의미 | 앱 처리 (제안) |
|---|---|---|---|
| 401 | (키 없음) | 토큰 없음·만료 | 토큰 갱신 후 1번 재시도 (2장). **signup 다시 부르지 않기** |
| 400 | `NICKNAME_INVALID` | 닉네임이 2~10자가 아님 | "닉네임은 2~10자로 입력해 주세요" |
| 409 | `NICKNAME_TAKEN` | 다른 사용자가 사용 중 | "이미 사용 중인 닉네임" (US-1-1) |
| 409 | `ALREADY_REGISTERED` | 이미 닉네임을 등록한 사용자 | `get_my_profile()` 후 펫 화면으로 |
| 403 | `NOT_REGISTERED` | 닉네임 등록 전에 다른 기능 호출 | 닉네임 화면으로 |
| 400 | `INVALID_VALUE` | 측정값이 허용 범위를 벗어남 | 해당 기록은 버림 (재전송 금지) |
| 400 | `MISSING_SERVER_ID` | 실제 모드인데 공용 서버 id 없음 | 공용 API 2 저장 성공 후 호출 |
| 403 | `RECORD_OF_OTHER_USER` | 다른 사용자의 수집 기록 | 해당 기록은 버림 |
| 400 | `FEEDBACK_EXPIRED` | 수집 후 10분 초과 | "제출 가능 시간이 지났어요" |
| 409 | `FEEDBACK_DUPLICATE` | 이미 피드백한 수집 기록 | 창 닫기 |
| 429 | `FEEDBACK_DAILY_LIMIT` | 오늘 3회 제출함 | "오늘은 더 제출할 수 없어요" |
| 400 | `NOT_ENOUGH_FOOD` | 보유 먹이 부족 | 버튼 비활성화 |
| 409 | `REQUEST_ID_REUSED` | 다른 사용자가 이미 쓴 요청 ID | 새 UUID로 다시 요청 |
| 403 | `GACHA_LOCKED` | 성체가 아니라 뽑기 불가 (계획) | 뽑기 메뉴 숨김 |

- `details`는 사람이 읽는 설명이다. 화면 문구는 앱에서 오류 키별로 정한다.
- 세션이 없으면(401) 오류 키 없이 `"message": "permission denied for function …"`이 온다.

---

## 5. 함수 목록

| 함수 | 상태 | 요구사항 | 용도 |
|---|---|---|---|
| `is_nickname_available` | ✅ 확정 | FR-05-02 | 닉네임 중복 확인 |
| `register_user` | ✅ 확정 | FR-05-02, 03 | 닉네임 등록 + 펫 생성 |
| `get_my_profile` | ✅ 확정 | FR-05-03 | 내 정보 |
| `add_collection_record` | ✅ 확정 | FR-05-04, 05 | 수집 기록 저장 + 수집 보상 |
| `get_ranking` | ✅ 확정 | FR-05-11 | 상위 랭킹 |
| `get_my_rank` | ✅ 확정 | FR-05-11 | 내 순위 |
| `get_my_collection_stats` | ✅ 확정 | FR-05-06 | 수집 횟수·첫 수집 센서 |
| `submit_feedback` | ✅ 확정 | FR-05-07, 08 | 피드백 저장 + 보상 |
| `feed_pet` | ✅ 확정 | FR-05-09 | 먹이 주기·레벨업·진화 |
| `draw_costume` | 🔲 계획 | FR-05-10 | 코스튬 뽑기 |
| `get_my_costumes` | 🔲 계획 | FR-05-10 | 보관함 |
| `equip_costume` | 🔲 계획 | FR-05-10 | 코스튬 입히기·벗기기 |

---

### 5.1 `is_nickname_available` ✅
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `p_nickname` | text | 확인할 닉네임 |

**반환**: `true` / `false`

### 5.2 `register_user` ✅
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `p_nickname` | text | 2~10자 |

**반환**: 생성된 펫
```json
{ "user_id": "3397…", "level": 1, "feed_in_level": 0, "stage": "egg", "food": 0, "updated_at": "…" }
```
**실패**: `NICKNAME_INVALID`, `NICKNAME_TAKEN`, `ALREADY_REGISTERED`

### 5.3 `get_my_profile` ✅
파라미터 없음 (`{}`).

**반환**: 배열. 닉네임 등록 전이면 `[]`
```json
[{ "user_id": "3397…", "nickname": "test3173", "level": 1, "feed_in_level": 0, "stage": "egg", "food": 0 }]
```
- `stage`: `egg`(알) · `baby`(새끼) · `hatchling`(해츨링) · `adult`(성체)
- `feed_in_level`: 현재 레벨에서 먹인 먹이 수. 다음 레벨까지 필요한 수는 `level + 1` (게이지 표시용, GR-2-1)

### 5.4 `add_collection_record` ✅
**공용 API 2 저장에 성공한 뒤** 호출한다. 테스트 모드면 공용 서버로 보내지 않고 바로 호출한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `p_sensor_name` | text | ✅ | **변환된 이름** (`opensrc1` → `sensor 07`) |
| `p_temperature` | numeric | | °C, -30~50. 없으면 `null` |
| `p_humidity` | numeric | | %RH, 0~100. 연구실 센서·opensrc3·4는 `null` |
| `p_aqi` | integer | | 없으면 `null` |
| `p_tvoc` | integer | | 없으면 `null` |
| `p_co2` | integer | | 연구실: CO2, 오픈소스: eCO2. 400~5000 |
| `p_rssi` | integer | ✅ | dBm |
| `p_measured_at` | timestamptz | ✅ | 센서 측정 시각 |
| `p_public_server_id` | text | 실제 모드 ✅ | 공용 API 2가 돌려준 `id` |
| `p_is_shadow_area` | boolean | ✅ | 음영지역 센서 여부 |
| `p_is_test` | boolean | | 테스트 모드면 `true` (기본 `false`) |

> 오픈소스 센서의 온도·습도가 **둘 다 0**이면 `null`로 보낸다. 0을 보내면 정상값으로 저장된다.

**반환**: 저장된 기록, 이번에 지급된 보상, 지급 후 보유 먹이. 앱은 `rewards`가 비어 있지 않으면 보상 알림(RW-7)을 띄운다.
```json
{
  "record": { "id": 1, … },
  "rewards": [ { "food": 2, "reason": "first_collection" } ],
  "food": 2
}
```
- `reason`: `first_collection`(센서별 첫 수집) · `collection`(일반 5회) · `shadow_collection`(음영지역 5회)
- 재전송이면 `record`는 기존 기록(같은 `id`), `rewards`는 `[]` (이미 지급됨)
- 테스트 모드 기록은 보상 없음
- 한 번에 보상이 2개 올 수 있다 (예: 새 센서 첫 수집이 마침 5회째)
- 보상 개수·주기는 DB `settings` 표의 값을 따른다 (현재: 첫 수집 2개, 일반 5회마다 1개, 음영지역 5회마다 2개)

**실패**: `NOT_REGISTERED`, `INVALID_VALUE`, `MISSING_SERVER_ID`, `RECORD_OF_OTHER_USER`

### 5.5 `get_ranking` ✅
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `p_limit` | integer | 기본 50, 최대 100 |

**반환**: 배열 (UUID 없음)
```json
[{ "rank": 1, "nickname": "test3173", "level": 1, "stage": "egg", "is_me": true }]
```
- 동률 기준은 미정. 임시로 `현재 레벨에서 먹인 수 ↓, 가입 순 ↑`

### 5.6 `get_my_rank` ✅
파라미터 없음. **반환**: `{ "rank": 57, "nickname": "…", "level": 3, "stage": "baby", "total": 120 }` (RK-2, 50위 밖이어도 반환)
- 정렬 기준은 `get_ranking`과 같다. **실패**: `NOT_REGISTERED`

### 5.7 `get_my_collection_stats` ✅
파라미터 없음. 다음 보상까지 남은 횟수 표시용. 실제 모드 기록만 센다.
```json
{
  "collection_count": 5,
  "shadow_collection_count": 5,
  "next_collection_reward_in": 5,
  "next_shadow_reward_in": 5,
  "first_sensors": ["sensor 15", "sensor 31"]
}
```

### 5.8 `submit_feedback` ✅
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `p_collection_record_id` | bigint | ✅ | `add_collection_record`가 돌려준 `record.id` |
| `p_temp_feel` | text | | `cold`(춥다) · `ok`(적당하다) · `hot`(덥다) |
| `p_humidity_feel` | text | | `dry`(건조하다) · `ok`(적당하다) · `humid`(습하다) |
| `p_discomforts` | text[] | | 불편 사항 코드 목록. 예: `["no_shade", "no_seat"]` |
| `p_memo` | text | | 최대 100자 (설정값) |

- 체감 온도·체감 습도·불편 사항 중 **1개 이상** 보내야 한다.
- **불편 사항 선택지**는 앱에 하드코딩하지 말고 표에서 읽는다:
  `GET {URL}/rest/v1/feedback_discomfort_options?select=code,label&order=sort_order`
  (현재: `no_shade` 그늘 없음 · `strong_wind` 바람 강함 · `smell` 냄새 · `no_seat` 앉을 곳 없음 · `other` 기타. 팀 결정 전 초안, C-10)
- 조건: 본인의 실제 모드 기록 · 수집 후 10분 이내 · 그 기록에 첫 피드백 · 오늘(**KST 0시 기준**) 3회 미만
- **반환**: `{ "feedback_id": 12, "rewards": [{ "food": 5, "reason": "feedback" }], "food": 7, "remaining_today": 2 }`
- **실패**: `NOT_REGISTERED`, `INVALID_VALUE`, `RECORD_OF_OTHER_USER`, `FEEDBACK_DUPLICATE`, `FEEDBACK_EXPIRED`, `FEEDBACK_DAILY_LIMIT`
- 피드백 창을 닫아도(FB-2-2) 아무것도 호출하지 않으면 된다.
- 보상 개수, 하루 한도, 제출 가능 시간, 메모 길이는 DB `settings` 표의 값을 따른다.

### 5.9 `feed_pet` ✅
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `p_request_id` | uuid | 요청마다 새로 생성. 재전송 시 같은 값 |

**반환**: 먹이를 준 뒤의 상태
```json
{ "level": 2, "feed_in_level": 0, "feed_needed": 3, "stage": "egg", "food": 3, "leveled_up": true, "evolved": false }
```
- 레벨 n에서 먹이 n+1개를 먹이면 레벨업. 게이지(GR-2-1)는 `feed_in_level / feed_needed`
- `leveled_up: true`면 레벨업, `evolved: true`면 진화 연출(GR-3-1)을 보여준다.
- 진화 레벨은 DB `settings` 표의 값을 따른다 (현재 임시값: 새끼 3, 해츨링 6, 성체 10)
- 같은 `p_request_id`로 재전송하면 먹이를 다시 쓰지 않고 처음 응답을 그대로 돌려준다.
- **실패**: `NOT_REGISTERED`, `NOT_ENOUGH_FOOD`, `REQUEST_ID_REUSED`

### 5.10 `draw_costume` 🔲
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `p_request_id` | uuid | 요청마다 새로 생성. 재전송 시 같은 값 |

**반환**: `{ "costume_id": "…", "name": "…", "cost": 24, "food": 3, "draw_count": 1, "next_cost": 25 }`
- 비용 = 24 + 지금까지 뽑은 횟수. 코스튬 목록·중복 처리는 미정
- **실패**: `GACHA_LOCKED`, `NOT_ENOUGH_FOOD`

### 5.11 `get_my_costumes` 🔲
파라미터 없음. **반환**: `[{ "costume_id": "…", "name": "…", "equipped": true }]`

### 5.12 `equip_costume` 🔲
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `p_costume_id` | text | 입힐 코스튬. `null`이면 벗기기 |

**반환**: `get_my_costumes`와 같은 목록

---

## 6. 앱이 지켜야 할 약속 (요약)

1. 공용 API 2의 `sender` = 세션의 사용자 UUID
2. `add_collection_record`는 공용 API 2 **저장 성공 후**, 받은 `id`를 `p_public_server_id`로
3. 테스트 모드는 공용 서버로 보내지 않고 `p_is_test: true`
4. 센서 이름은 변환된 이름, 온습도가 둘 다 0이면 `null`
5. 응답을 못 받으면 같은 값으로 재전송 (`p_public_server_id`, `p_request_id` 유지)
6. 먹이·레벨 등은 화면에서 계산하지 말고 **서버가 돌려준 값**으로 표시

## 7. 범위 밖

- 장소·시간대 집계와 점검 후보 (FR-04-09): 기준 확정(C-10) 후 별도 추가
- 공용 API 1·2·5 호출: 앱이 공용 서버에 직접 요청

## 8. 테스트

[`tests/api.http`](tests/api.http)에 실제 요청·응답 예시가 있다 (VS Code REST Client).

## 부록 A. Java 예시 (Retrofit)

> 구조를 보여주는 예시이며 백엔드 쪽에서 빌드해 보지는 않았다. 패키지·클래스 이름은 앱에 맞게 바꾼다.

**의존성** (`app/build.gradle`)
```gradle
implementation 'com.squareup.retrofit2:retrofit:2.11.0'
implementation 'com.squareup.retrofit2:converter-gson:2.11.0'
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```
URL·키는 `local.properties`에 두고 `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_KEY`로 읽는다.

**API 인터페이스**
```java
public interface SupabaseApi {
    @POST("auth/v1/signup")
    Call<JsonObject> signUpAnonymously(@Body JsonObject empty);           // new JsonObject()

    @POST("auth/v1/token?grant_type=refresh_token")
    Call<JsonObject> refreshToken(@Body JsonObject body);                 // {"refresh_token": "…"}

    @POST("rest/v1/rpc/{function}")
    Call<JsonElement> rpc(@Path("function") String function, @Body JsonObject params);
}
```

**클라이언트: 헤더 자동 추가 + 401이면 토큰 갱신 후 재시도**
```java
OkHttpClient http = new OkHttpClient.Builder()
    .addInterceptor(chain -> {
        Request req = chain.request();
        Request.Builder b = req.newBuilder().header("apikey", BuildConfig.SUPABASE_KEY);
        String token = tokenStore.getAccessToken();
        if (token != null && !req.url().encodedPath().startsWith("/auth/")) {
            b.header("Authorization", "Bearer " + token);
        }
        return chain.proceed(b.build());
    })
    .authenticator((route, response) -> {
        if (response.priorResponse() != null) return null;   // 이미 한 번 재시도함
        String newToken = tokenStore.refreshBlocking();        // refresh 호출 후 4개 값 저장, 실패 시 null
        if (newToken == null) return null;
        return response.request().newBuilder()
            .header("Authorization", "Bearer " + newToken).build();
    })
    .build();

SupabaseApi api = new Retrofit.Builder()
    .baseUrl(BuildConfig.SUPABASE_URL + "/")
    .client(http)
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(SupabaseApi.class);
```

**함수 호출과 오류 키 읽기**
```java
JsonObject params = new JsonObject();
params.addProperty("p_nickname", nickname);

api.rpc("register_user", params).enqueue(new Callback<JsonElement>() {
    @Override
    public void onResponse(Call<JsonElement> call, Response<JsonElement> res) {
        if (res.isSuccessful()) {
            JsonObject pet = res.body().getAsJsonObject();      // level, stage, food …
        } else {
            String key = errorKey(res);                         // "NICKNAME_TAKEN" 등 (4장 표)
        }
    }
    @Override
    public void onFailure(Call<JsonElement> call, Throwable t) {
        // 네트워크 오류: 같은 값으로 재시도해도 안전한 함수인지 3장 확인
    }
});

static String errorKey(Response<?> res) {
    try {
        return JsonParser.parseString(res.errorBody().string())
                .getAsJsonObject().get("message").getAsString();
    } catch (Exception e) {
        return null;
    }
}
```

**먹이 주기: 요청 ID는 재시도할 때 같은 값을 쓴다**
```java
String requestId = UUID.randomUUID().toString();   // 버튼을 누를 때 1번 생성
JsonObject p = new JsonObject();
p.addProperty("p_request_id", requestId);
api.rpc("feed_pet", p).enqueue(callback);          // 실패해 재시도할 때도 같은 requestId
```

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-11-18 | v0.1 최초 작성. 확정 5개, 계획 7개 |
| 2026-11-18 | 확정 함수 5개에 오류 키 적용 (`NICKNAME_INVALID`, `NICKNAME_TAKEN`, `ALREADY_REGISTERED`, `NOT_REGISTERED`, `INVALID_VALUE`, `MISSING_SERVER_ID`, `RECORD_OF_OTHER_USER`) |
| 2026-11-20 | 수집 보상 구현. `add_collection_record` 응답을 `{record, rewards, food}`로 변경, `get_my_collection_stats` 추가 |
| 2026-11-21 | `feed_pet` 구현 (레벨업·진화, 요청 ID 재전송 처리). 응답에 `feed_needed` 추가, 오류 키 `REQUEST_ID_REUSED` 추가 |
| 2026-11-22 | `submit_feedback` 구현 (KST 0시 하루 경계, 불편 사항 선택지 표), `get_my_rank` 구현 (`total` 추가) |
| 2026-11-22 | 앱이 Java로 확정 → Kotlin 예시 삭제, 토큰 저장·갱신 흐름(2장)과 Java 예시(부록 A) 추가. 401 처리를 "signup 재호출"에서 "토큰 갱신 후 재시도"로 수정 |
