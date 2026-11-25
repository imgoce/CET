# CET 백엔드 (③ 곽예찬)

Supabase(PostgreSQL) 팀 DB. **기준 문서는 [`docs/api-contract.md`](../docs/api-contract.md) (계약서 v1.2)** 이다.

앱은 `com.opensrc.cet.db.TeamDb`의 메서드를 호출하고, 그 구현이 아래 SQL 함수를 부른다.

```
앱 화면 → TeamDb (Java, com.opensrc.cet.db) → Supabase SQL 함수 (이 폴더) → DB
```

## 계약서 메서드와 SQL 함수

| 계약서 메서드 | SQL 함수 (`POST /rest/v1/rpc/…`) | 상태 |
|---|---|---|
| `registerUser` | `register_user(p_request_id, p_nickname)` | ✅ |
| `loginByNickname` | `login_by_nickname(p_nickname)` | ✅ |
| `getUserState` | `get_user_state(p_user_id)` | ✅ |
| `commitCollection` | | 🔲 3단계 |
| `getShadowSites` | | 🔲 3단계 |
| `submitFeedback` | | 🔲 4단계 |
| `feedPet`, `drawCostume`, `equipCostume`, `getRanking` | | 🔲 5단계 |
| `getSiteEnvironment`, `getAnalysisInputs` | | 🔲 6단계 |

- 응답 JSON의 필드 이름은 계약서와 같다 (`userId`, `foodBalance`, `stage: "EGG"` …).
- 실패: HTTP 4xx + `{ "message": "<계약서 오류 코드>", "details": "<설명>" }`.
  Java 구현이 `message`를 `TeamDbErrorCode`로 바꿔 `TeamDbResult.failure`로 돌려준다.
- 로그인 토큰 없이 Publishable key(anon)로 호출한다. 표는 직접 읽거나 쓸 수 없고 함수만 호출할 수 있다.

## 폴더

```
backend/
├── supabase/migrations/   # DB 변경 기록. 파일 이름 순서대로 한 번씩만 실행
│   ├── 20261118 ~ 20261122_*.sql            # (폐기) 익명 로그인 방식의 초기 구조
│   └── 20261125000000_contract_v12_users.sql # 계약서 v1.2 재구성: 사용자·펫·닉네임 접속
└── tests/
    ├── api.http        # REST Client 테스트 (위에서부터 순서대로 Send Request)
    └── .env.example    # .env 로 복사해 URL·키 입력 (.env 는 커밋 안 함)
```

## 작업 규칙

1. 마이그레이션은 **새 파일**로 만든다. 이미 실행한 파일은 고치지 않는다.
2. Supabase SQL Editor 새 탭에 붙여넣고 Run (= 배포).
3. `tests/api.http`로 확인한 뒤 커밋한다.
4. 테스트 사용자 정리: `delete from public.users where nickname like 't%';`
