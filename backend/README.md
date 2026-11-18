# CET 백엔드

Supabase(PostgreSQL) 기반. 앱은 공용 서버(API 1·2·5)와 이 DB를 함께 사용한다.

> 앱 개발자용 API 명세: [API.md](API.md)

## 구조

```
backend/
├── supabase/
│   └── migrations/
│       └── 20261118000000_init_skeleton.sql   # 사용자, 펫, 수집 기록, 랭킹 뼈대
└── tests/
    ├── api.http        # REST Client용 API 테스트 (정상 흐름 + 보안·예외)
    └── .env.example    # .env 로 복사해 URL·키 입력 (.env 는 커밋 안 함)
```

## 테스트

1. VS Code 확장 **REST Client** (Huachao Mao) 설치
2. `backend/tests/.env.example` → `backend/tests/.env` 복사 후 URL·Publishable key 입력
3. `api.http` 를 열고 1번부터 순서대로 **Send Request** 클릭, 각 요청의 "기대" 주석과 비교

## 적용 방법

1. Supabase 대시보드 → Authentication → Sign In / Providers → **Allow anonymous sign-ins** 켜기
2. 마이그레이션 적용
   - **대시보드**: SQL Editor에 마이그레이션 파일 내용을 붙여넣고 실행
   - **CLI**: `supabase link --project-ref <ref>` 후 `supabase db push`

## 사용자 구분: 로그인 화면 없음

사용자는 로그인을 하지 않는다. 앱이 뒤에서 자동으로 익명 세션을 만든다.

```
앱 첫 실행
 ├─ 저장된 세션 없음 → supabase.auth.signInAnonymously()
 └─ get_my_profile() 호출
      ├─ 결과 없음 → 닉네임 화면 → register_user(닉네임)
      └─ 결과 있음 → 바로 펫 화면 (US-2-2)
```

- 세션 토큰은 기기에 저장되며, DB는 토큰으로 확인한 `auth.uid()`로만 사용자를 판단한다.
- 사용자 UUID(`auth.uid()`)는 공개돼도 안전하다 → 공용 API 2의 `sender`로 사용한다.
- 앱을 삭제하거나 기기를 바꾸면 계정이 사라진다. (추후 Google·이메일 계정 연동으로 보완 가능)

## 현재 포함된 것

| 테이블 / 함수 | 관련 RID | 설명 |
|---|---|---|
| `users` | US-1, US-1-1, US-1-2 | `auth.users`와 연결. 닉네임(2~10자, 중복 불가) |
| `pets` | US-1-3, GR-4 | 레벨, 현재 레벨에서 먹인 수, 진화 단계, 보유 먹이 |
| `collection_records` | DC-6, DC-6-1, DC-7 | 추가만 가능. `public_server_id` 중복 시 한 번만 저장 |
| `is_nickname_available()` | US-1-1 | 닉네임 중복 확인 |
| `register_user()` | US-1, US-1-3 | 사용자 + 펫 동시 생성 |
| `get_my_profile()` | US-2-1, US-2-2 | 내 사용자·펫 정보 |
| `add_collection_record()` | DC-6 | 수집 기록 저장 (재전송 안전) |
| `get_ranking()` | RK-1 | 상위 N명의 닉네임·레벨·진화 단계 (UUID 비공개) |

## 접근 원칙

- 테이블은 **본인 데이터만 조회** 가능하다.
- **쓰기는 RPC 함수로만** 한다. 함수는 앱이 보낸 사용자 ID를 받지 않고 `auth.uid()`를 쓴다.
- 먹이·레벨 변경도 이후 RPC로만 허용해 앱에서 값을 직접 바꾸지 못하게 한다.

## 다음에 추가할 것 (명세 확정 후)

- 보상: 지급 기록, 수집 횟수, 첫 수집 센서 (RW)
- 먹이 주기 / 레벨업 / 진화 (GR)
- 피드백 (FB), 코스튬·뽑기 (CS), 내 순위 (RK-2), 집계 (AG)
- 하루 경계 시각(KST 0시) 공통 처리

> 공용 API 5 팀 키 같은 비밀값은 이 레포에 커밋하지 않는다.
