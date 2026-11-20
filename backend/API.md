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
- 라이브러리: [`supabase-kt`](https://github.com/supabase-community/supabase-kt)의 `Auth`, `Postgrest` 모듈

```kotlin
val supabase = createSupabaseClient(supabaseUrl = URL, supabaseKey = KEY) {
    install(Auth)
    install(Postgrest)
}
```

---

## 2. 앱 시작 흐름 (FR-05-01 ~ 03)

로그인 화면은 없다. 앱이 뒤에서 익명 세션을 만들고, 세션은 기기에 자동 저장된다.

```
앱 실행
 ├─ supabase.auth.awaitInitialization()
 ├─ 세션 없음 → supabase.auth.signInAnonymously()
 └─ get_my_profile()
      ├─ []      → 닉네임 화면 → is_nickname_available() → register_user()
      └─ [1개]   → 펫 화면
```

- **사용자 UUID** = `supabase.auth.currentUserOrNull()?.id`. 공용 API 2의 `sender`에 이 값을 넣는다.
- 앱을 삭제하거나 기기를 바꾸면 새 사용자가 된다 (현재 버전의 한계).

---

## 3. 공통 규칙

### 호출 방식
모든 함수는 `POST {URL}/rest/v1/rpc/{함수이름}`, 본문은 JSON. `supabase-kt`에서는:

```kotlin
supabase.postgrest.rpc("함수이름", buildJsonObject { put("p_nickname", "우왕이") })
```

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
| 401 | (키 없음) | 세션 없음·만료 | `signInAnonymously()` 후 재시도 |
| 400 | `NICKNAME_INVALID` | 닉네임이 2~10자가 아님 | "닉네임은 2~10자로 입력해 주세요" |
| 409 | `NICKNAME_TAKEN` | 다른 사용자가 사용 중 | "이미 사용 중인 닉네임" (US-1-1) |
| 409 | `ALREADY_REGISTERED` | 이미 닉네임을 등록한 사용자 | `get_my_profile()` 후 펫 화면으로 |
| 403 | `NOT_REGISTERED` | 닉네임 등록 전에 다른 기능 호출 | 닉네임 화면으로 |
| 400 | `INVALID_VALUE` | 측정값이 허용 범위를 벗어남 | 해당 기록은 버림 (재전송 금지) |
| 400 | `MISSING_SERVER_ID` | 실제 모드인데 공용 서버 id 없음 | 공용 API 2 저장 성공 후 호출 |
| 403 | `RECORD_OF_OTHER_USER` | 다른 사용자의 수집 기록 | 해당 기록은 버림 |
| 400 | `FEEDBACK_EXPIRED` | 수집 후 10분 초과 (계획) | "제출 가능 시간이 지났어요" |
| 409 | `FEEDBACK_DUPLICATE` | 이미 피드백한 수집 기록 (계획) | 창 닫기 |
| 429 | `FEEDBACK_DAILY_LIMIT` | 오늘 3회 제출함 (계획) | "오늘은 더 제출할 수 없어요" |
| 400 | `NOT_ENOUGH_FOOD` | 보유 먹이 부족 (계획) | 버튼 비활성화 |
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
| `get_my_rank` | 🔲 계획 | FR-05-11 | 내 순위 |
| `get_my_collection_stats` | ✅ 확정 | FR-05-06 | 수집 횟수·첫 수집 센서 |
| `submit_feedback` | 🔲 계획 | FR-05-07 | 피드백 저장 + 보상 |
| `feed_pet` | 🔲 계획 | FR-05-09 | 먹이 주기·레벨업·진화 |
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

### 5.6 `get_my_rank` 🔲
파라미터 없음. **반환**: `{ "rank": 57, "nickname": "…", "level": 3, "stage": "baby" }` (RK-2, 50위 밖이어도 반환)

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

### 5.8 `submit_feedback` 🔲
| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `p_collection_record_id` | bigint | ✅ | `add_collection_record`가 돌려준 `id` |
| `p_temp_feel` | text | | `cold` · `ok` · `hot` |
| `p_humidity_feel` | text | | `dry` · `ok` · `humid` |
| `p_discomforts` | text[] | | 불편 사항 코드 목록 (선택지 미정, C-10) |
| `p_memo` | text | | 최대 길이 미정 (C-10) |

- 조건: 본인의 실제 모드 기록 · 수집 후 10분 이내 · 그 기록에 첫 피드백 · 오늘(KST) 3회 미만
- **반환**: `{ "feedback_id": 12, "rewards": [{ "food": 5, "reason": "feedback" }], "food": 7 }`
- **실패**: `RECORD_OF_OTHER_USER`, `FEEDBACK_EXPIRED`, `FEEDBACK_DUPLICATE`, `FEEDBACK_DAILY_LIMIT`
- 피드백 창을 닫아도(FB-2-2) 아무것도 호출하지 않으면 된다.

### 5.9 `feed_pet` 🔲
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `p_request_id` | uuid | 요청마다 새로 생성. 재전송 시 같은 값 |

**반환**: 먹이를 준 뒤의 상태
```json
{ "level": 2, "feed_in_level": 0, "stage": "egg", "food": 3, "leveled_up": true, "evolved": false }
```
- `evolved: true`면 진화 연출(GR-3-1)을 보여준다.
- **실패**: `NOT_ENOUGH_FOOD`

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

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-11-18 | v0.1 최초 작성. 확정 5개, 계획 7개 |
| 2026-11-18 | 확정 함수 5개에 오류 키 적용 (`NICKNAME_INVALID`, `NICKNAME_TAKEN`, `ALREADY_REGISTERED`, `NOT_REGISTERED`, `INVALID_VALUE`, `MISSING_SERVER_ID`, `RECORD_OF_OTHER_USER`) |
| 2026-11-20 | 수집 보상 구현. `add_collection_record` 응답을 `{record, rewards, food}`로 변경, `get_my_collection_stats` 추가 |
