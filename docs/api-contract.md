# 캠퍼스 환경 펫 핵심 API 및 데이터 형식

2팀 · Android Java · 구현 계약 v1.2 · 2026-11-23

## 01  팀원별 연결 지점

학생이 수집한 환경 측정값과 체감 피드백을 같은 지점에서 비교해 점검 후보를 찾는다. 우왕이의 먹이·성장은 수집과 피드백 참여에 대한 보상이다. 이 문서는 수업 서버에 보내는 실제 HTTP 요청과, Android 앱에서 팀 관리형 DB를 호출할 때 공유할 데이터 형식을 정한다.

| 담당 | 구현 범위 | 다른 담당자와 주고받는 값 |
|---|---|---|
| ① 정운형 · 모바일 앱 UI·BLE 수집 | Android 앱의 BLE 스캔·권한·수집 재시도와 펫·피드백 화면, 조작 버튼을 구현한다. | 광고를 ④에 전달하고 ③의 수집 API를 호출한다. ②에서 받은 선택 지점을 복원하고, 펫·보상·수집 진행도를 표시한다. §04~11 |
| ② 이승찬 · 센서 지도·환경 개선 결과 화면 | 지도 마커·음영지역·지점 상세·갱신 실패·장기 미수신 표시와 점검 후보 목록·상세 화면을 구현한다. | ③의 지도 API와 ④의 판정 결과를 지도에 표시한다. 선택한 센서 번호를 ①에 전달하고 ④의 `SiteSlot[]`로 불편·개선 요구와 자료 부족을 표시한다. §03·07·12 |
| ③ 곽예찬 · 백엔드·DB·API | 수업 HTTP API 연동과 팀 DB 저장·조회 메서드를 구현한다. | ④의 유효성·할당량 판정과 ⑤의 보상 계산을 연결하고, 참여·잔액·보유·장착 상태를 한 번에 저장한다. §03·05~11·14 |
| ④ 김용민 · 데이터 전처리·분석 | 패킷을 해석하고 측정값·피드백 유효성, 최근값·환경 상태, 음영지역·기여도·할당량과 점검 후보를 계산한다. | ①·⑤에 환경 상태, ③·⑤에 참여 판정과 할당량 달성 여부, ②에 센서별 최신 행·음영 목록·지점별 집계를 전달한다. §04·06·07·09·10·12 |
| ⑤ 권기찬 · 캐릭터·보상 로직 | 환경별 펫 표현, 먹이·성장과 진화, 보상 조건·중복 방지, 장식 지급·장착·해제를 구현한다. | ④의 판정을 받아 보상과 펫 상태 변화를 계산한다. ③이 기록하고 ①이 표시한다. §07·09~11·14 |

**새 저장소에서의 사용법.** Markdown 원본을 `docs/api-contract.md`로 두고 DTO(모듈 간 데이터 객체)와 메서드 이름을 공유한다. PDF는 이 원본에서 생성한다. API 1·2·5는 수업 서버의 HTTP API다. `registerUser`, `commitCollection` 등은 팀 DB 접근 모듈의 메서드로, Firebase나 Supabase 선택과 관계없이 같은 입출력을 쓴다.

**근거 문서.** 2팀 요구사항 명세서 통합수정본과 시스템 아키텍처 수정본, 센서 공용 API v4, 12주차 설계 자료 35쪽. 공용 API의 키와 타입은 원문을 따른다. 팀 내부 필드명·열거값·오류 코드는 이번 계약에서 정했다. 예시 데이터는 모두 문서 검증용이다.

## 02  공통 타입과 식별자

팀 내부 키는 `camelCase`를 쓴다. 아래 형식에서 `T?`는 값이 없으면 JSON `null`, `T[]`는 배열, `Int`는 정수, `Long`은 64비트 정수, `Num`은 실수 허용, `Id`는 UUID 문자열이다. 표에서 묶어 쓴 필드는 각각 별도 키다. 명시한 nullable 키도 응답에 포함한다. 화면만 빈 값을 `--`로 바꾼다.

| 이름 · 타입 | 생성과 사용 |
|---|---|
| `userId: Id` | 신규 닉네임 등록 성공 시 발급한다. 다른 폰에서 기존 닉네임으로 접속해도 같은 사용자·펫을 가리킨다. |
| `deviceId: Id` | 앱이 생성해 기기에 보관한다. 재실행 시 재사용하며 다른 기기로 복사하지 않는다. 공용 API 2의 `sender`에만 이 기기 식별값을 사용한다. |
| `collectionId: Id` / `feedbackId: Id` | 개별 사용자 수집 기록 / 피드백 한 건의 참여 UUID. 피드백은 대상 `collectionId`도 함께 보낸다. 전송 전에 발급해 재시도 시 유지한다. |
| `windowId: Id` | 수집 세션의 완료된 10초 구간마다 발급한다. 같은 구간 내 서로 다른 센서 기록이 공유한다. |
| `requestId: Id` | 등록·먹이 주기·뽑기·착용 같은 변경 요청마다 발급한다. 같은 버튼 동작을 재전송하면 같은 값이다. |
| `sensorNo: Int` | 1~34. 앱·DB 간 표준 키. BLE 이름 또는 공용 서버 문자열과의 변환은 ④의 센서 번호 대응표를 ① 수집과 ③ 공용 통신이 공유한다. |
| `measuredAt` / `collectedAt` / `submittedAt` | 센서의 측정 시각 / 폰의 실제 현장 수신 시각 / DB가 피드백을 처음 접수한 시각. 팀 계약은 모두 UTC ISO 8601 문자열이며 소수 초를 허용한다. |
| `windowStartAt` / `storedAt` | 폰에서 구간이 시작된 시각 / 팀 DB 커밋 시각. 전송 지연이 있어도 원래 측정·수집 시각을 유지한다. |

**날짜와 경계.** 하루는 `Asia/Seoul` 00:00 이상 다음 날 00:00 미만이다. 첫 수집 보상은 `collectedAt`, 피드백 한도는 `submittedAt`의 한국 날짜를 쓴다. 수업 API 2의 `time`에만 Unix **초**로 변환한다. 예를 들어 `2026-11-23T03:00:00Z`는 한국 12시, Unix `1795402800`이다.

**팀 호출의 공통 결과.** 성공은 `{"ok":true,"data":…}`, 실패는 `{"ok":false,"error":{"code":"…","message":"…","retryable":false}}`로 통일한다. 이후 표의 출력은 성공 시 `data` 안의 내용이다. 공용 HTTP 응답은 통신 모듈에서 별도로 해석한다.

## 03  공용 API 1과 지도

**실제 HTTP 계약** · `GET http://203.255.81.72:10021/sensor/api/map` · 요청 쿼리와 본문 없음. 앱 실행, 지도 진입, 지도 표시 중 5분마다 최신 센서 정보를 받는다. 근거: SM-1~4, EX-5·7.

```json
[{"sensor":"sensor 18","latitude":36.62752,"longitude":127.453162,
  "temperature":8,"co2":1112,"time":"Tue Nov 10 07:09:55 2026",
  "fresh":false,"mode":"direct"}]
```

| 수업 서버 응답 키 | 타입 · 앱 처리 |
|---|---|
| `sensor`, `latitude`, `longitude` | String, Num, Num. 센서 설치 좌표다. 번호 1~34만 지도 대상이며 pws01·vs01·vs02는 제외한다. 응답에 없는 센서의 좌표는 생성하지 않는다. |
| `temperature`, `co2` | Int ℃(소수점 버림), Int ppm. 습도 키는 없다. 31·32의 지도 온도는 원시값과 관계없이 `--`다. |
| `time`, `mode`, `fresh` | 영문 요일·월명이 있는 KST 문자열, `direct/mobile`, Bool. 센서별로 시각을 파싱해 가장 최근 행을 고른다. 동일 시각은 표시용으로 mobile을 우선한다. |
| 지도 표시 데이터 | 앱 객체 `MapMarker{sensorNo:Int,latitude:Num,longitude:Num,temperatureC:Num?,co2Ppm:Int,lastSeenAt:Time,fresh:Bool,mode:String,isShadow:Bool?}`. 이 문서의 `Time`은 §02의 UTC 문자열이다. |

**지도에 겹쳐 표시할 두 값.** 갱신 상태는 API 1에서 읽는다. 마지막 측정이 24시간을 넘으면 장기 미수신으로 표시한다. 음영 여부는 팀 DB의 `getShadowSites()`로 받아 `sensorNo`로 연결한다. 초기 로딩·실패이고 음영 캐시도 없으면 `isShadow=null`로 두고 보너스 확정 표시를 보류한다. 공용 API의 `fresh=false`만으로 음영 판정을 만들지 않는다.

**지도와 펫 화면 연결.** ② 이승찬은 지도 진입·갱신을 ③ 곽예찬의 API 1 호출과 연결한다. ②가 맡은 지도 요구사항은 SM-2, SM-3-1, SM-4, EX-5, EX-7이다. 마커를 선택하면 ②가 `sensorNo`를 전달하고 ① 정운형이 기기에 저장·복원한다(ES-1). 펫 화면은 같은 번호로 §10의 환경값을 조회한다. 공용 조회 실패 시 캐시와 마지막 갱신 시각을 표시한다. 캐시가 없으면 조회 실패·재시도 상태로 간다. 공용 지도 응답과 팀 DB 환경값은 갱신 시각과 소수점 정밀도가 다를 수 있다.

## 04  BLE 패킷과 공통 측정 객체

**입력**은 `0x181A` 서비스 광고의 Local name, 서비스 데이터, MAC, RSSI, 수신 시각이다. 수집을 켠 동안 연속 스캔한다. 이름과 길이가 함께 맞아야 하며 모든 다중 바이트 숫자는 Little-Endian이다. 근거: DC-1~4.

| 센서 | 바이트 오프셋과 해석 |
|---|---|
| 연구실 29대 · 8바이트 | 0~1 CO₂(uint16, ppm), 2~3 온도(int16 ÷ 100, ℃), 4~7 Unix 초(4바이트). 수업용 5개 번호 제외. |
| opensrc1~5 · 13바이트 이상 | 0~1 온도(int16 ÷ 100), 2~3 습도(uint16 ÷ 100), 4 AQI(uint8), 5~6 TVOC(uint16, ppb), 7~8 eCO₂(uint16, ppm), 9~12 Unix 초(uint32). 뒤의 검증용 바이트는 읽지 않는다. |
| 이름 변환 | opensrc1→07, opensrc2→15, opensrc3→31, opensrc4→32, opensrc5→33. 예: BLE `opensrc1` → 앱 `sensorNo=7` → 공용 요청 `sensor 07`. |

**`Measurement` · ①이 수신한 BLE 광고를 ④가 정규화하고 ③의 저장·통신 모듈과 화면이 공유한다.**

| 키 | 타입 | 규칙 |
|---|---|---|
| `sensorNo`, `sensorMac` | Int, String | 등록 번호와 실제 수신한 MAC. MAC은 공용 전송에 사용한다. |
| `measuredAt`, `collectedAt`, `rssiDbm` | Time, Time, Int | 해석 가능한 측정 시각, 현장 수신 시각, RSSI ≥ −85 dBm. |
| `temperatureC`, `humidityRh` | Num?, Num? | 정상 지원 항목은 −30~50℃, 0~100%RH(경계 포함). 연구실 습도는 null. 31·32의 온습도는 수리 전 항상 null. 정상 장비의 실제 0℃는 값이다. |
| `co2Ppm`, `eco2Ppm` | Int?, Int? | 연구실은 CO₂, 수업용은 eCO₂만 채운다. 해당 값이 400~5,000 ppm 범위여야 한다. 다른 키는 null. |
| `aqi`, `tvocPpb` | Int?, Int? | 연구실은 null. 수업용은 디코딩 원시 정수. 31·32도 참고용으로 보관하며 보상·온습도 통계 판정에 쓰지 않는다. |

미등록·형식 오류·범위 밖 필수값·약한 RSSI는 후보에서 제외한다. **31·32는 eCO₂·시각·형식·신호가 유효하면 정상 수집 후보**다. 스캔 목록은 1초마다 갱신하며 두 장비의 온도는 `--`다. 표시 갱신 자체는 수집 기여를 만들지 않는다.

## 05  공용 API 2 업로드와 예외 경로

**실제 HTTP 계약** · `GET http://203.255.81.72:10021/sensor/sensing/`. 본문과 인증 헤더 없이 쿼리로 보낸다. 경로 끝에 슬래시가 필요하고 마지막 쿼리값에는 붙이지 않는다. 근거: DC-5~7, EX-4~6, NF-13.

| 쿼리 키 | 타입 · 필수 | 값 |
|---|---|---|
| `mac`, `sensor` | String · 필수 | 센서 MAC, `sensor NN`. HTTP 클라이언트로 쿼리를 인코딩한다. |
| `sender`, `mode` | String · 필수 | 전송한 폰의 `deviceId`, 고정값 `mobile`. |
| `temp`, `co2`, `time` | Num, Int, Num · 필수 | ℃, ppm, Unix 초. 수업용은 `eco2Ppm`을 `co2`로 전송한다. |
| `rssi` | Int · 공용 선택 | 팀 앱은 수신 RSSI(dBm)를 보낸다. 습도·AQI·TVOC는 팀 DB 경로로만 전달한다. |

**예시 쿼리 데이터** · HTTP 클라이언트가 아래 값을 GET 쿼리로 변환한다. 이 예시는 운영에 보내지 않는다.

```json
{"mac":"88:A2:9E:9B:5F:09","sensor":"sensor 07",
 "sender":"10000000-0000-4000-8000-000000000002","mode":"mobile",
 "temp":29.5,"co2":710,"time":1795402800,"rssi":-58}
```

**성공:** HTTP 200과 `{"status":"success","message":"Data received","id":123}`. 앱은 양의 정수 `id`를 로컬에 저장한 뒤 팀 DB 쓰기를 시작한다. **실패:** HTTP 400의 `error`를 읽는다. 필수 키 누락·빈 문자열·숫자 변환 오류 등 입력 오류는 같은 값으로 재전송하지 않는다.

| 저장 대상 | 공용 단계 | 팀 DB 단계 |
|---|---|---|
| 일반 운영 측정 | API 2 성공 확인 및 id 보관 | 운영 기록, `publicRoute="UPLOADED"`, `publicId=123` |
| 31·32 운영 측정 | 수리 전 호출 생략. 필수 temp에 오류값을 넣지 않는다. | 운영 기록, `publicRoute="TEMP_UNAVAILABLE"`, `publicId=null` |
| 테스트 | 공용 요청 0건 | 테스트 전용 공간. 운영 건수·음영 순위·보상·랭킹은 그대로다. |

전송 **전** 네트워크 실패만 5초 간격 최대 3회 재시도하고 대기열로 보낸다. 전송 후 응답 유실로 공용 저장 여부가 불명확하면 `PUBLIC_UNKNOWN`으로 보류한다. 자동 재전송·보상을 하지 않는다. 공용 성공 후 팀 DB만 실패하면 **기존 UUID와 공용 id 그대로 팀 DB만 재시도**한다. 미완료 로컬 기록은 최초 수집 후 24시간까지 보관한다.

## 06  구간 선택과 수집 저장 계약

**① 수집 → ④ 후보 판정 → ③ 팀 DB** · `commitCollection(input) → CollectionResult`. 한 완료 구간에서 유효 측정은 여러 건 저장할 수 있고, 한 사용자의 기여는 최대 1건이다. 근거: DC-3-4, DC-5~7, RW-1~6, NF-6~8.

1. 수집 시작을 기준으로 겹치지 않는 10초 구간을 만든다. 중지 시 미완료 구간은 기여에서 제외한다. 각 센서의 새 유효 측정 중 측정 시각이 가장 최근인 1건을 고른다. 동일 측정 반복 광고는 그중 가장 강한 RSSI의 수신값을 쓴다.
2. 이들 중 RSSI 최대인 센서 하나를 기여 후보로 고른다. 동률은 센서 번호가 작은 순이다. 구간 종료 시 후보와 식별자를 로컬에 확정한 뒤 전송한다. 후보가 실패해도 차순위 센서로 바꾸지 않는다.
3. 센서별로 §05의 경로를 거친다. 도착 순서가 달라도 모든 기록에 원래 `windowId`, `windowStartAt`, 같은 `candidateSensorNo`를 전달한다. 테스트 모드는 별도 테스트 저장소에 바인딩한다.

**요청 필드 전체** · `collectionId:Id, userId:Id, windowId:Id, windowStartAt:Time, candidateSensorNo:Int, measurement:Measurement, publicRoute:Enum, publicId:Long?`. `measurement.collectedAt`은 해당 구간 안에 있어야 한다. 입력의 후보 번호는 앱의 선별 결과다. 음영 여부·기여 승인·지급량은 DB가 결정한다.

```json
{"collectionId":"10000000-0000-4000-8000-000000000003",
 "userId":"10000000-0000-4000-8000-000000000001",
 "windowId":"10000000-0000-4000-8000-000000000004",
 "windowStartAt":"2026-11-23T03:00:00Z","candidateSensorNo":31,
 "measurement":{"sensorNo":31,"sensorMac":"B8:27:EB:D3:40:06",
   "measuredAt":"2026-11-23T03:00:00Z","collectedAt":"2026-11-23T03:00:03Z",
   "rssiDbm":-53,"temperatureC":null,"humidityRh":null,
   "co2Ppm":null,"eco2Ppm":715,"aqi":2,"tvocPpb":200},
 "publicRoute":"TEMP_UNAVAILABLE","publicId":null}
```

**성공 예시** · 이 예시에서 31번은 커밋 직전 음영이며, 사용자의 오늘 첫 기여다. 음영 잔여는 0에서 1이 된다.

```json
{"ok":true,"data":{"collectionId":"10000000-0000-4000-8000-000000000003",
 "credited":true,"isShadowBefore":true,"measurementAdded":true,
 "grants":[{"reason":"FIRST_SENSOR_DAY","food":2}],"foodBalance":2,
 "normalRemainder":0,"shadowRemainder":1,"replayed":false}}
```

결과 타입은 순서대로 Id, Bool, Bool, Bool, Grant[], Long, Int, Int, Bool이다. `credited=false`도 유효한 저장 성공이다. 비후보·중복 측정 기여에는 지급 목록이 비어 있다. `replayed=true`는 같은 요청을 돌려받은 것으로, UI는 보상 알림을 다시 띄우지 않는다.

## 07  음영지역과 보상을 한 번만 반영하는 방법

**전처리·보상·DB가 공유하는 저장 순서.** `commitCollection`의 운영 처리는 한 트랜잭션에서 순서를 보장한다. 두 폰이 동시에 제출하면 한 건이 먼저 확정된 것과 같은 결과여야 한다. 근거: SM-3, RW-1~7, NF-6·23.

1. 기존 `collectionId`면 저장된 처리 결과를 반환한다. 같은 ID를 다른 내용에 사용하면 `ID_CONFLICT`다. 동일 사용자의 같은 센서·측정 시각은 다시 기여로 인정하지 않는다.
2. 1~34번 전 지점의 **프로젝트 시작 이후** 고유 측정 수를 오름차순으로 정렬한다. 동률은 센서 번호순이다. 커밋 직전 하위 5곳에 해당하는지 `isShadowBefore`로 고정한다. 처음엔 모두 0건이므로 01~05다.
3. 환경 측정은 `(sensorNo, measuredAt)`별 1건이다. 새 측정이면 해당 센서 건수를 1 올린다. 다른 사용자가 같은 측정을 수집하면 환경 표본은 그대로여도 해당 사용자의 첫 기여는 가능하다. 31·32의 유효 eCO₂도 이 건수에 포함한다.
4. 선택된 센서이고 같은 사용자·구간과 같은 사용자·측정에 기존 인정 기록이 없을 때만 기여 1회를 확정한다. 같은 계정의 서로 다른 폰이 겹치는 10초 구간을 제출하면 이미 확정된 기여 구간과 겹치면 후속 건의 개인 기여·보상은 0이다. 새 유효 환경 표본은 3번에 따라 저장한다. `[시작, 시작+10초)`의 경계가 맞닿은 두 구간은 겹치지 않는다.
5. 오늘 해당 센서 첫 기여면 먹이 2개. 첫 기여도 해당 지역 카운터에 포함한다. 일반 5회당 1개, 음영 5회당 2개를 지급한다. 두 카운터의 잔여 0~4회는 날짜·세션을 넘어 유지한다.
6. 참여 기록·카운터·잔액·지급 이력을 함께 저장한 후 갱신된 음영 목록을 공개한다. 보상 이력의 유일 키는 `(참여 UUID, reason)`이다. 한 건에 첫 수집과 누적 달성이 겹치면 두 사유가 함께 지급된다.

**`getShadowSites() → {sites: ShadowSite[], updatedAt: Time?}`**. `ShadowSite{sensorNo:Int, uniqueMeasurements:Long}`. 정렬된 5개를 반환한다. 초기 시각은 null일 수 있다. 앱은 팀 DB 변경을 구독하거나 변경 시 다시 조회하여 지도 아이콘을 갱신한다.

**경계 예시.** 05번이 기존 음영이고 사용자의 음영 잔여가 4회다. 05번 새 측정이 저장되면서 06번이 음영에 들어와도 **이번 건은 음영 5회 보상 2개**다. 이미 오늘 05번 첫 보상을 받았다면 추가 지급은 이 2개뿐이다. 다음 05번 기여는 당시 새 목록으로 판정한다.

## 08  닉네임 접속과 펫 상태

**앱 ↔ 팀 DB 접근 모듈** · 아래 호출은 HTTPS로 연결하는 관리형 DB 어댑터가 제공한다. 화면 담당은 공급사 SDK 대신 같은 입력과 결과를 사용한다. 근거: US-1~3, NF-11·19·21·24.

| 호출 | 요청 | 성공 시 data / 주요 실패 |
|---|---|---|
| `registerUser` | `{requestId:Id,nickname:String}` | `UserState`. 닉네임 2~10자, 중복이면 `NICKNAME_TAKEN`. 사용자·기본 펫을 한 번에 만든다. |
| `loginByNickname` | `{nickname:String}` | 기존 `UserState`. 없으면 `NICKNAME_NOT_FOUND`. 새 사용자나 펫을 만들지 않는다. |
| `getUserState` | `{userId:Id}` | `UserState`. 기기에 남아 있는 사용자로 자동 접속할 때 사용한다. 알 수 없는 UUID는 `USER_NOT_FOUND`. |

**`UserState`** = `{userId:Id,nickname:String,pet:PetState,normalTotal:Long,shadowTotal:Long,normalRemainder:Int,shadowRemainder:Int}`. Total은 승인 기여 누계, Remainder는 해당 누계를 5로 나눈 나머지다. ①은 일반·음영 진행도를 각각 0/5~4/5로 표시하고 수집 성공 시 §06 결과로 갱신한다.

**`PetState`** · 펫 화면·보상 로직이 동일하게 읽는 키

| 필드 | 타입과 초기값 | 의미 |
|---|---|---|
| `level`, `progress`, `foodBalance` | Long, 각각 1·0·0 | 현재 레벨, 그 레벨에서 먹인 개수, 보유 먹이 |
| `stage` | Enum, `EGG` | `EGG / BABY / HATCHLING / ADULT`. 1·6·11·16레벨부터 변경 |
| `drawCount`, `nextDrawCost` | Long, 0·24 | 성공 횟수와 다음 비용. 차감 가능 여부는 레벨·보유 목록도 확인 |
| `ownedCostumes`, `equippedCostume` | String[], String?, 초기 []·null | 보유 중인 고유 코드와 착용 1개. 네 코드는 §11에 정의 |

**닉네임 처리.** 입력 양끝 공백을 제거하고 NFC 정규화한 문자열로 길이·중복을 검사한다. 길이는 유니코드 코드포인트 수, 대소문자는 구분한다. 등록은 DB에서 원자적으로 유일성을 검사한다. 같은 닉네임으로 폰 B에 접속하면 폰 A와 같은 `userId`·펫이 반환되고 공용 업로드 `sender`는 B의 `deviceId`다. 기존 닉네임만으로 접속하는 방식에는 본인 확인이 없다.

**로컬 설정.** 마지막 선택 `sensorNo`, 말풍선 표시 여부, 자동 접속용 `userId`, 이 폰의 `deviceId`를 보관한다. 마지막 지점과 말풍선 토글은 앱 재실행 후 복원한다.

## 09  현장 피드백 제출

**`submitFeedback(input) → FeedbackResult`** · ① 정운형의 입력 → ④ 김용민의 유효성 판정 → ⑤ 권기찬의 보상 계산. ③ 곽예찬의 DB 쓰기 경로가 조건 확인·지급·저장을 한 번에 확정한다. 근거: FB-1~3, RW-4·5, NF-6·10.

| 요청 키 | 타입 | 허용값 |
|---|---|---|
| `feedbackId`, `collectionId`, `userId` | Id 각각 | 새 피드백 UUID, 연결할 본인의 인정 수집 UUID, 접속 사용자. 센서 번호는 연결 기록에서 가져온다. |
| `feltTemp`, `feltHumidity` | Enum 각각 | 온도 `COLD / OK / HOT`, 습도 `DRY / OK / HUMID`. 센서가 습도를 지원하지 않아도 학생의 체감은 입력할 수 있다. |
| `issues` | Enum[] | 1개 이상 중복 없이 `NONE / NO_SHADE / STRONG_WIND / ODOR / NO_SEAT / OTHER`. ‘개선 필요 없음’인 NONE은 단독 선택. |
| `memo` | String? | 선택. 최대 100자(코드포인트). 공백만 있으면 null. |

```json
{"feedbackId":"10000000-0000-4000-8000-000000000005",
 "collectionId":"10000000-0000-4000-8000-000000000003",
 "userId":"10000000-0000-4000-8000-000000000001",
 "feltTemp":"HOT","feltHumidity":"OK","issues":["NO_SHADE"],"memo":null}
```

```json
{"ok":true,"data":{"feedbackId":"10000000-0000-4000-8000-000000000005",
 "submittedAt":"2026-11-23T03:04:00Z","todayCount":1,
 "grants":[{"reason":"FEEDBACK","food":5}],"foodBalance":7,"replayed":false}}
```

**판정 순서.** 먼저 기존 피드백 UUID의 결과를 확인한다. 신규 요청이면 본인 소유·기여 승인·운영 기록인지 확인하고, 현장 수집 시각부터 DB 접수 시각까지 **0초 이상 600초 이하**인지 검사한다. 수집 기록당 1건, KST 제출일당 3건 이하를 동시에 확인한 뒤 피드백·횟수·먹이 5개·이력을 원자적으로 저장한다. 다음 날 재전송돼도 이미 성공한 피드백은 원래 결과를 반환한다.

**화면 처리.** 10분 1초는 `FEEDBACK_EXPIRED`, 다른 사용자의 기록이나 비후보는 `NOT_ELIGIBLE`, 같은 수집에 새 피드백 ID를 붙이면 `FEEDBACK_EXISTS`, 당일 네 번째는 `DAILY_LIMIT`다. 실패 시 먹이와 횟수는 그대로다. 창을 닫으면 호출하지 않는다. `NONE`도 유효하면 5개다.

## 10  환경값 조회와 우왕이 표현

**`getSiteEnvironment({sensorNo:Int}) → SiteEnvironment`** · 팀 DB는 지점별 항목의 마지막 유효값과 시각을 제공한다. ④ 김용민이 마지막 유효값과 상태를 판정한다(ES-2-2). ⑤ 권기찬이 상태를 행동·배경에 연결하고 ① 정운형이 화면에 표시한다. 근거: SM-4, ES-1~5, NF-5·9.

| SiteEnvironment 키 | 반환 형식 |
|---|---|
| `sensorNo`, `siteName` | Int, String. 팀 지점 목록에서 선택한 같은 지점이다. |
| `temperatureC`, `tempMeasuredAt` | Num?, Time?. 온도 값과 그 값을 얻은 측정 시각. |
| `humidityRh`, `humidityMeasuredAt` | Num?, Time?. 유효 습도와 그 값의 독립적인 측정 시각. |
| `eco2Ppm`, `eco2MeasuredAt` | Int?, Time?. 유효 eCO₂와 그 시각. 후순위 기침 연출에서 사용할 수 있다. |

```json
{"ok":true,"data":{"sensorNo":18,"siteName":"sensor 18",
 "temperatureC":29,"tempMeasuredAt":"2026-11-20T03:00:00Z",
 "humidityRh":null,"humidityMeasuredAt":null,"eco2Ppm":null,"eco2MeasuredAt":null}}
```

등록 지점에 측정이 없으면 성공과 함께 각 값·시각 모두 null이다. 지점명이 등록되지 않은 경우 표시명은 `sensor NN`으로 대신한다. 값이 있으면 기한 없이 표시하되 측정 시각을 함께 보여준다. 31·32는 수리 확인 전 온습도의 과거 기록도 차단한다. 화면에서 지점을 전환하면 이전 조회의 늦은 응답은 응답의 `sensorNo`로 구분한다.

| 앱 계산 | 표시 |
|---|---|
| 온도 ≤10 / ≥28 / 사이 / null | `COLD / HOT / NORMAL / MISSING` → 떨기 / 땀 / 기본 / 기본 행동 |
| 습도 ≤30 / ≥65 / 사이 / null | `DRY / HUMID / NORMAL / MISSING` → 사막 / 물방울 / 기본 / 기본 배경 |
| 말풍선 꺼짐 / 선택 지점 없음 | 토글 시 설정 저장. 지점 미선택은 기본 펫·기본 배경과 `--`. 조회·토글은 기여 0회. |

**인수 예시.** A의 습도 70%를 보고 B(연구실 센서, 29℃)로 전환하면 B는 땀·기본 배경·습도 `--`다. A의 물방울이 남지 않는다. 앱을 다시 열면 B가 복원된다. 데이터가 오래돼도 같은 지점의 값과 시각을 유지한다.

## 11  먹이 사용과 코스튬 및 랭킹

**변경은 팀 DB에서 한 번만 반영한다.** `userId`와 `requestId`는 세 변경 호출의 필수 Id다. 같은 요청의 재전송에는 같은 결과를 돌려준다. 앱은 잔액을 DB의 결과로 갱신한다. 근거: GR-1~4, CS-1~4, RK-1~2, NF-22~24.

| 메서드 | 추가 입력 | 성공 시 data |
|---|---|---|
| `feedPet` | 없음 | `{pet:PetState,leveledUp:Bool,evolved:Bool,replayed:Bool}` |
| `drawCostume` | 없음 | `{pet:PetState,drawnCostume:String,spentFood:Long,replayed:Bool}` |
| `equipCostume` | `costume:String?`. null은 벗기기 | `{pet:PetState,replayed:Bool}` |
| `getRanking` | 읽기 요청 `{userId:Id}` | `{top:RankRow[],me:RankRow}`. 응답에 사용자 UUID를 노출하지 않는다. |

**성장.** 먹이 1개 차감 후 진행도를 1 올린다. 현재 레벨 L에서 진행도가 L+1에 도달하면 레벨을 올리고 진행도를 0으로 만든다. 알 1~5, 새끼 6~10, 해츨링 11~15, 성체 16 이상. 6·11·16 도달 시 진화가 발생한다. 20 이후에도 레벨은 계속 오른다. 먹이 부족은 `INSUFFICIENT_FOOD`다.

**뽑기와 착용.** 레벨 ≥16, 미보유 장식 존재, 잔액 ≥ `24 + drawCount`를 확인한다. 4종은 `SPROUT` 새싹, `RIBBON` 리본, `STAR` 별, `CROWN` 왕관이다. 미보유 중 균등 추첨해 착용 전 상태로 지급한다. 잔액 차감·아이템 추가·성공 횟수 증가와 재전송용 결과를 함께 저장한다. 재전송 시 재추첨하지 않는다. 실패는 `LEVEL_LOCKED / ALL_OWNED / INSUFFICIENT_FOOD / NOT_OWNED`. 보유 장식 중 한 개만 착용한다.

**랭킹.** `RankRow{rank:Int,nickname:String,level:Long,progress:Long,stage:Enum}`. 레벨·진행도 내림차순으로 경쟁 순위(1, 2, 2, 4)를 부여한다. 같은 순위의 표시 순서는 NFC 닉네임 코드포인트 오름차순이다. 전체에서 순위를 매긴 뒤 첫 50명과 본인의 행을 별도 반환한다.

**연결 테스트.** Lv.5 진행도 5, 먹이 1개에서 `feedPet` 한 번 → 먹이 0, Lv.6, 진행도 0, BABY, `evolved=true`. 동일 requestId로 재호출해도 이 결과다. 별도 사례로 성체가 먹이 30개로 첫 뽑기에 성공하면 잔액 6, 성공 횟수 1, 다음 비용 25, 진행도 변화 0이다.

## 12  장소별 집계와 점검 후보

**③ 곽예찬의 팀 DB 조회 → ④ 김용민의 분석 → ② 이승찬의 환경 개선 결과 화면**. 앱의 분석 함수는 운영 DB가 제공한 표본·피드백을 입력받는다. 다른 모듈이 별도로 비율이나 후보 기준을 다시 계산하지 않는다. 근거: AG-1~7, NF-4·8.

| 연결 | 입력과 결과 |
|---|---|
| DB `getAnalysisInputs` | 입력 `{asOfDate:Date}`(KST 조회일, YYYY-MM-DD). 결과 `{samples:Sample[],feedbacks:FeedbackRow[]}`. 조회일과 직전 13일이 대상이다. |
| `Sample` | `{sensorNo:Int,measuredAt:Time,temperatureC:Num?,humidityRh:Num?}`. 센서·측정 시각별 고유 환경 표본. |
| `FeedbackRow` | `{feedbackId:Id,sensorNo:Int,collectedAt:Time,feltTemp:Enum,feltHumidity:Enum,issues:Enum[]}`. 본문 메모나 사용자 식별값은 집계에 필요하지 않다. |
| 앱 `aggregateSites` | 입력은 같은 `asOfDate`와 위 두 배열. 결과 `SiteSlot[]`를 센서 번호·구간 순으로 반환한다. 목록과 상세 표는 동일한 결과를 사용한다. |

**`SiteSlot`** = `{sensorNo:Int,slotStartHour:Int,tempMedian:Num?,humidityMedian:Num?,tempN:Int,humidityN:Int,responseN:Int,uncomfortableN:Int,discomfortRate:Num?,distinctDiscomfortDays:Int,status:Enum,topIssues:IssueCount[]}`. `IssueCount{issue:Enum,count:Int}`. 등록된 34곳 × 4구간을 포함해 0건과 조회 실패를 구분한다.

- KST 09~12, 12~15, 15~18, 18~21시. 앞 경계 포함, 뒤 경계 제외. 환경 표본은 `measuredAt`, 의견은 연결 수집의 `collectedAt`으로 날짜와 구간을 정한다. 시간대 밖은 보관하되 이 집계에서 제외한다.
- 온습도는 각각 null과 오류값을 제외한 중앙값·표본 수를 계산한다. 짝수 개는 가운데 둘의 평균이다. 31·32 온습도는 통계에서 제외한다. 표본 0개면 해당 중앙값 null.
- 체감 온도 또는 체감 습도가 OK 외이거나 NONE 외 불편을 하나라도 선택한 응답은 불편 1건이다. 비율은 `uncomfortableN / responseN`(0~1), 화면만 %로 표시한다. 응답 0건이면 null이다.
- 응답 <10 → `INSUFFICIENT`. 응답 ≥10이고 불편 비율 ≥0.5이며 불편이 발생한 KST 날짜 ≥3일 → `CANDIDATE`. 나머지는 `NORMAL`. 상위 불편 항목은 NONE을 빼고 빈도 순 최대 3개, 같은 횟수면 §09의 선택지 순서(그늘 없음 → 바람 강함 → 냄새 → 앉을 곳 없음 → 기타)로 먼저 정렬한 뒤 최대 3개를 표시한다.

**후순위 공용 API 5.** 분석 스크립트만 `GET /sensor/api/history`를 사용한다(§03과 같은 호스트). 헤더 `X-API-Key: <팀 키>`, 쿼리 `sensor,start` 필수, `end,mode,limit,cursor` 선택. 응답 `rows[]`의 `time,timestamp,sensor,mode,temp,co2,rssi,mac,sender`와 `next_cursor`를 사용한다. 한 센서·최대 7일·페이지 최대 5,000행, 키당 분당 30회. 401은 키, 400은 요청 확인, 429·503은 Retry-After 초 뒤 재시도한다.

## 13  실패 처리와 공통 검증 사례

**DB 메서드의 오류.** 입력 타입·범위·선택지 오류는 `INVALID_INPUT`, 이미 쓴 ID에 다른 입력을 보낸 경우는 `ID_CONFLICT`다. 앱이 처리할 업무 오류는 §08·09·11에 정의했다. DB 연결 실패는 `DB_UNAVAILABLE`로 같은 ID로 재시도한다. 쓰기 응답 유실은 성공 여부가 불분명하므로 결과 확인도 같은 ID로 한다. 읽기 실패는 캐시 또는 재시도 안내를 표시한다.

**재시도 규약.** 변경 성공 결과와 요청 지문(사용자·작업 종류·입력)을 함께 보관한다. 같은 요청이 성공했으면 일일 한도·잔액·기간을 다시 검사하기 전에 기존 결과를 반환한다. 등록 요청도 같은 원리로 중복 생성을 막는다. 재생 응답의 잔액은 최초 커밋 당시 값일 수 있어, 이미 최신 화면을 표시 중이면 `getUserState`로 현재 잔액을 다시 읽는다. 임의의 보상액·현재 레벨을 클라이언트 쓰기 인자로 받지 않는다.

| 통합 시나리오 | 기대 결과 |
|---|---|
| A·B가 같은 구간, A 3개 광고 중 최신 유효 측정 −55 dBm, B −72 dBm | 각 센서 최신 측정 최대 1건 저장, 기여는 A만 1회. 동률이면 작은 센서 번호. |
| 31번 원시 온습도 0, eCO₂ 715, 유효 시각·신호 | 공용 요청 0, 팀 운영 DB 온습도 null, 고유 측정 +1. 후보면 기여 +1. 펫은 기본 상태. |
| 같은 계정 두 폰에서 [0,10)초를 먼저 확정한 뒤 [5,15)초 또는 [10,20)초 제출 | 서로 다른 유효 측정이라고 할 때 앞 사례의 후속 기여·보상은 0, 뒤 사례의 후속 기여는 1회다. |
| 같은 센서의 direct·mobile 측정 시각 동일 / 불편 4종 각각 2건 | 지도에는 mobile 행, 집계에는 정해진 선택지 순으로 처음 3종을 표시한다. |
| 같은 collectionId를 DB 타임아웃 후 재전송 | 데이터·첫 수집·누적 지급은 각각 한 번, 식별자·공용 id 유지. |
| 일반 잔여 4, 오늘 해당 센서 첫 기여 | 첫 수집 2 + 누적 1 = 3개. 일반 잔여 0. 음영 잔여는 유지. |
| 수집 23:59 KST, 다음 날 00:01 피드백 | 첫 수집 판정은 전날, 피드백 한도는 다음 날. 함께 다른 폰으로 4건을 내도 하루 승인 최대 3건. |
| 수집 후 600초 / 601초 경과 | 본인·인정·한도 충족 시 600초는 승인, 601초는 거절. 같은 성공 UUID의 재전송은 기존 결과. |
| 잔액 1에서 두 폰이 서로 다른 먹이 주기 요청 | 하나만 성공, 다른 하나는 먹이 부족. 잔액 0, 진행도 증가 총 1회(레벨업 시 진행도 초기화). |
| 10건 중 불편 5건, 서로 다른 3일 / 9건 중 불편 9건 | 첫 경우 CANDIDATE, 둘째 INSUFFICIENT. 센서 습도 결측은 응답 분모에 영향을 주지 않는다. |
| 모의 BLE 입력을 테스트 모드로 실행 | 운영 업로드·운영 측정·먹이·랭킹 변경 모두 0. 테스트 전용 공간에서 흐름을 검증한다. |

**첫 공동 시연.** 신규 닉네임 → 정상 센서 첫 유효 기여 → 펫 잔액 2 → 같은 수집 재전송 후에도 2 → 그 수집의 피드백 제출 후 7 → 다른 폰에서 같은 닉네임으로 접속해 7 확인. 테스트 시 두 폰의 deviceId가 다른지도 함께 확인한다.

## 14  모듈 간 저장 키와 결과 코드

이 표는 Firebase 컬렉션 또는 SQL 테이블에 대응시킬 수 있는 **논리적 키**다. 실제 DB 제품·물리적 데이터 구조는 DB 담당자가 정한다. 업무 규칙의 계산은 한 곳에서 호출하고, 운영 앱에서 이미 저장한 참여·피드백·지급 기록은 수정하거나 삭제하지 않는다.

| 논리 데이터 | 유일성 및 사용 목적 |
|---|---|
| 사용자 / 펫 | 사용자 PK `userId`, 정규화한 `nickname` 고유. 사용자당 펫 하나. 최신 잔액과 진행도를 변경 결과와 함께 읽는다. |
| 환경 측정 / 센서별 건수 | `(sensorNo, measuredAt)` 고유. 유효 측정 신규 삽입과 지점별 누적 수 증가가 함께 확정된다. |
| 개인 수집 기록 | `collectionId` 고유. 사용자·구간·측정·후보·승인·반영 직전 지역·공용 경로·공용 id·저장 시각을 보존한다. 한 구간에 비후보 기록도 여러 건 있을 수 있다. |
| 인정된 기여 / 일일 최초 | `(userId,windowId)`, `(userId,sensorNo,measuredAt)` 각각 기여 최대 1회. `(userId,sensorNo,KST 수집일)`은 첫 보상 최대 1회. 폰이 다르면 §07의 겹침 검사도 함께 적용한다. |
| 피드백 / 일일 제출 | `feedbackId` 고유, 인정된 `collectionId`당 최대 1건. `(userId,KST 제출일)`별 성공 횟수 최대 3. |
| 지급 이력 | `(참여 UUID,reason)` 고유. `userId,reason,food,grantedAt`과 관련 참여 UUID를 저장한다. |
| 성장·장식 변경 / 재전송 | `(userId,requestId)`별 입력 지문·처리 결과 고정. `(userId,costume)` 고유. 착용은 사용자당 최대 1종. |

**`Grant`** = `{reason:Enum,food:Int}`. `FIRST_SENSOR_DAY` 2개, `NORMAL_FIVE` 1개, `SHADOW_FIVE` 2개, `FEEDBACK` 5개. 한 수집의 배열에는 첫 보상과 해당 지역 5회 보상이 함께 들어갈 수 있다. 실제 지급이 없으면 빈 배열이다. 화면은 새로 받은 보상 합계와 사유를 2초간 표시한다.

**`FeedbackResult`** = `{feedbackId:Id,submittedAt:Time,todayCount:Int,grants:Grant[],foodBalance:Long,replayed:Bool}`. `todayCount`는 원래 제출일의 성공 횟수다. 읽기나 계산에서 빈 목록은 `[]`, 집계의 분모가 0이면 비율은 `null`, 실제 0회와 먹이 0개는 숫자 0이다.

**공통 테스트 더블.** 각 브랜치는 §06의 저장 요청·성공 응답, §09의 연결 피드백, §10의 조회 응답을 그대로 고정 입력으로 써도 된다. 통합할 때는 §13의 순서로 테스트 공간을 사용한다. 후순위 API 5, 기침 연출·배고픔 알림·그래프는 필수 경로 완주와 독립적으로 구현한다.

