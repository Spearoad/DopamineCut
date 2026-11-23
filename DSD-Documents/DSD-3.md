# 3. 소프트웨어 기술
## 3.1 모듈별 세부 설계
### 3.1.1 앱별 숏폼 감지 모듈

#### [1] AppManagerInterface
YouTube·Instagram·카카오톡의 화면 구조 차이를 각 Manager에서 처리하고, 공통 인터페이스로 진입 판정·식별 정보·후속 처리 명령을 전달한다. UI 트리를 그대로 여러 번 순회하기보다 ScreenSnapshot을 입력으로 사용한다.

변수: platform (지원 플랫폼), packageName (플랫폼 패키지명).

| 함수명 | 입력 | 처리 | 출력 |
| :--- | :--- | :--- | :--- |
| isShortformSection | ScreenSnapshot | 플랫폼별 진입 조건 확인 | Boolean |
| detectShortformScreen | ScreenSnapshot | 진입 여부·신뢰도·판정 근거를 보존 | ShortformScreenDetection |
| isAdContent | ScreenSnapshot | 광고 UI 단서 확인. 이 값만으로 전체 콘텐츠 분류를 대체하지 않음 | Boolean |
| getVideoIdentifier | ScreenSnapshot | 플랫폼별 단서를 정규화하여 식별자 후보 생성 | String? |
| getVideoIdentity | ScreenSnapshot, 관측 경과시간 | 식별자·작성자·제목 등과 식별 품질 제공 | VideoIdentity? |
| contentTrackingCommand | ScreenSnapshot, ContentTrackingContext | 플랫폼별 현재 상태에 맞는 후속 처리 선택 | ContentTrackingCommand |

식별자 생성과 시청 이력 검사는 분리한다. 접근성 기반 작성자·제목 등은 영상 콘텐츠의 의미 분석용 OCR과 구분하며, 텍스트 변화가 항상 새 영상임을 뜻하지 않는다.

#### [2] ShortformSessionTracker

| 함수명 | 입력 | 처리 | 출력 |
| :--- | :--- | :--- | :--- |
| beginProvisional | 플랫폼, 식별자 후보 | CV 확정 전 임시 세션 시작 | 없음 |
| resolveProvisional | 예상 세션 ID, 유형, 식별자 | 현재 세션과 일치하는 결과만 확정 | Boolean |
| onScreenChanged | 시청 여부, 플랫폼, 식별자, 유형 | 영상 전환·동일 영상 유지·시청 종료 처리 | 없음 |
| tick | 없음 | 단조 시계로 경과시간 계산, 집계 조건·체크포인트 통지 | 없음 |
| onInteractionOverlay | 없음 | 확정된 시청 세션의 시간 지속 가능 여부에 따라 상태 유지 | 없음 |
| setPaused | Boolean | 실제 측정 정지가 필요한 상태에서 경과시간 처리 정지·재개 | 없음 |
| release | 없음 | 진행 세션 종료·결과 전달 | 없음 |
| clearRecentCountHistory | 없음 | 계정·날짜 등 경계에서 최근 중복 검사 이력 초기화 | 없음 |

일반 숏폼은 기본 5초 집계 조건과 목표의 시청 허용 여부를 확인하여 1회 반영한다. 플랫폼별 최근 집계 식별자 최대 3개를 메모리에 보관하여 재집계를 줄이며, 영구적 전체 시청 이력 조회는 아니다. 세션 ID는 하나의 시청 구간, 콘텐츠 식별자는 영상 후보를 나타내므로 서로 구분한다.

  
#### [3] AppBlockService

| 주요 처리 | 입력 | 처리 | 출력 |
| :--- | :--- | :--- | :--- |
| onAccessibilityEvent | 접근성 이벤트 | 전경 앱·화면 상태 갱신과 플랫폼별 처리 연결 | 후속 비동기 작업 |
| updateShortformTracking | AppManagerInterface | 진입·식별·화면 가림에 따른 추적 상태 조정 | 세션·인식 상태 |
| 플랫폼별 CV 처리 | 현재 영상·캡처 작업 토큰 | 캡처·분류를 비동기로 수행하고 현재 세션에만 적용 | 콘텐츠 유형·신뢰도 |
| enforceManagedGoals | 집계 상태, 영상 수 집계 전 여부 | 활성 목표의 범위·진행률·개입 여부 판단 | 개입 처리 여부 |
| captureAndQueueClassification | 시청 자격·세션 소유 정보 | 일반 숏폼의 OCR 수집과 배치 연결 | 분석 대기 항목 |
| dispatchClassificationBatch | ContentClassificationBatch | 서버 분석 요청·응답의 영상별 연결 | 결과 반영 작업 |

서비스는 연결과 상태 전환을 조정하고, 플랫폼별 UI 규칙·세션 집계·OCR 누적·서버 호출·저장은 각 구성요소에 분리한다. 댓글·공유창에서는 새 캡처·CV·OCR을 정지하되 기존 식별자·세션·누적 텍스트를 유지하고, 이미 유형이 확정된 영상의 시간은 계속 누적하는 것을 공통 기준으로 한다.

  
### 3.1.2 텍스트 추출 및 정제 모듈

#### [1] OCRProcessor / TemporalOcrCollector / OcrTextAccumulator

| 클래스·함수 | 입력 | 처리 | 출력 |
| :--- | :--- | :--- | :--- |
| OCRProcessor.recognizeFrame | InputImage | ML Kit로 화면 텍스트와 줄별 위치 인식 | Result＜OcrFrame＞ |
| TemporalOcrCollector.collect | 세션 유효성·캡처·상태 확인 콜백 등 | 일반 숏폼에서 기본 1초 간격 수집, 전환·정지 상태 확인 | 누적 텍스트 |
| OcrTextAccumulator.add | 인식 문자열 | 정규화·반복 인식 정리·최대 길이 제한 | 누적 상태 갱신 |

캡처 영역은 플랫폼별 콘텐츠 경계와 UI 단서를 사용하고, YouTube는 플레이어·작성자 위치를 캡처 좌표로 변환해 콘텐츠 텍스트 영역을 정한다. 기본 1초는 목표 수집 간격이며 작업 시간이 길면 실제 간격은 늘어난다. 영상별 최대 500자에 도달하면 추가 OCR을 중지하고, 전환·이탈 시 텍스트를 확정한다.

### 3.1.3 카테고리 분류 모듈

#### [1] CallableBatchContentClassifier / ClassificationBatchQueue

| 구성요소 | 입력 | 처리 | 출력 |
| :--- | :--- | :--- | :--- |
| ClassificationBatchQueue | 세션별 OCR 텍스트 | 최대 5개로 묶고, 기본 15초 대기 기한이 지나면 부분 배치 전송 | ContentClassificationBatch |
| CallableBatchContentClassifier | 분석 배치 | Firebase callable 함수 classifyShortformBatch 호출·응답 검증 | 영상별 분류 결과 |
| 서버 분류 처리 | 인증된 요청·OCR 항목 | 입력·사용량 한도 검증, 분류 지침·기준 결합, Jev 요청 및 조건부 Gemini 대체 | 카테고리·처리 상태 |
| 세션별 결과 반영 | 세션 ID, 분류 결과 | 해당 시청 기록의 카테고리 변경·미확인 처리 | 로컬 기록·통계 갱신 |

카테고리는 게임·유머·교육·사회·음식·스포츠·음악·엔터테인먼트·기타의 9종이며, 미확인은 분류에 필요한 정보가 부족하거나 처리에 실패한 상태이다.

외부 AI의 API 키는 서버 Secret에서 관리한다. JSON은 요청 본문 형식이며, 앱이 프롬프트 파일이나 화면 이미지 파일을 외부 AI에 직접 전송하는 구조가 아니다.

### 3.1.4 시스템 데이터 동기화 및 관리

#### [1] DataStoreManager / SyncedGoalStore / UserRepository / FirebaseDataSource

| 주요 처리 | 입력 | 처리 | 출력 |
| :--- | :--- | :--- | :--- |
| accumulate·checkpointShortform | 사용 이벤트·세션 체크포인트 | 로컬 사용량 누적, 중복 체크포인트 차분 반영, 변경 버전 증가 | UsageSnapshot |
| resolveShortformCategory | 세션 ID·분류 결과 | 기존 기록에 결과를 연결하며 집계 중복 방지 | 갱신된 스냅샷 |
| saveGoals·setGoalStatus | 목표·기대 revision | 목표 저장·수정·중지·복원, 로컬 변경 보관 | 갱신 목표 |
| 목표 sync | 사용자·로컬/원격 버전 | 원격 version 확인·충돌 처리 후 저장 또는 가져오기 | 동기화 상태 |
| syncUsageSnapshots | 미전송 일일 스냅샷 | syncUsageBatch 함수로 검증·중복 방지·버전 확인 | 서버 확인된 항목 |
| getStatisticsRange | 사용자·날짜 범위 | 로컬 우선 표시, 원격 월별 기록 조회·버전/미전송 상태에 따른 조정 | 날짜별 통계 |

사용량 업로드는 기본 24시간 주기이며 실패·앱 종료 이후 미전송 기록의 재시도를 예약한다. 목표 설정 저장은 사용량 업로드 주기와 독립적이다. 로컬 데이터가 항상 더 최신이라고 가정하지 않으며, 미전송 변경·기준 원격 버전·충돌 여부를 확인하고 단순 합산이나 무조건 덮어쓰기를 피한다. 계정 변경·삭제와 진행 중 업로드의 경합도 분리하여 처리한다.

### 3.1.5 목표·통계 및 알림 인터페이스

* **인터페이스 개요:** 목표/설정 UI ↔ 목표·알림 저장소 ↔ 로컬 집계·개입 / 통계 UI ↔ 통계 저장소
* **주요 인터페이스 및 데이터 흐름:**
  1. **목표 관리:** 숏폼 시청 시간·영상 수·앱 사용시간을 복수로 저장·수정·중지·복원한다.
  2. **일정·적용 범위:** 매일/요일별 목표, 공휴일 제외, 세 앱 합산/앱별 개별 설정을 날짜별 적용 목표로 해석한다. 제외된 날짜는 목표값 0이 아니라 미적용이다.
  3. **날짜별 평가:** 당시 목표 이력과 측정 기록으로 달성·일부 달성·초과·기록 부족·목표 제외·오늘 상태를 제공한다.
  4. **시간대별 조회:** 실제 기록을 기간·앱·시간/영상 수 지표로 조회한다. 시간 기록은 시간 경계를 나누고, 없는 과거 기록을 임의 분포로 복원하지 않는다.
  5. **알림 설정:** 기기 내 기본 08시·12시·21시 사용 습관 알림과 별도의 목표·측정 상태 안내를 관리한다.

---

---

## 3.2 서버 (Server Block)

### 3.2.1 데이터베이스 구조 및 관계 정의

현재 Firestore는 사용자 문서, 목표 설정 문서, 월별 일일 집계를 중심으로 구성한다. 시청 세션 상세·재전송 상태는 로컬에 보관한다.

* USER - GOAL_SETTINGS: 사용자별 목표 설정 문서 (1:0..1).
* USER - MONTH_STATISTICS: 사용자별 월 통계 문서 (1:N).
* MONTH_STATISTICS - DAILY_AGGREGATE: 월 문서의 days 맵 안에 날짜별 집계 보관 (1:N, 독립 컬렉션 아님).
* LOCAL_DAILY - LOCAL_SESSION: 기기 내 일일 스냅샷과 시청 세션 기록 (1:N, 서버 관계와 구분).
* USER - GOAL_HISTORY: 목표 변경 이력과 유효 기간 (1:N, 확장 설계).

---

### 3.2.2 각 컬렉션의 구조

#### 1) 유저 정보 컬렉션 (users)

* **경로:** users/{uid}
* **설명:** 인증 UID에 연결된 프로필과 사용자 문서 스키마를 관리한다. 이메일·비밀번호 인증은 Firebase Authentication에서 처리한다.

| 필드명 (Field) | 유형 (Type) | 제약조건 (Constraint) | 설명 (Description) |
| :--- | :--- | :--- | :--- |
| schema_version | int | 현행 사용자 스키마 2 | 문서 형식 버전 |
| profile.nickname | string | 닉네임 입력 검증 | 사용자 표시 이름 |
| created_at | timestamp | 생성 시 지정 | 가입 시각 |
| updated_at | timestamp | 서버 시각 | 문서 갱신 시각 |
| goal | map | 기존 사용자 스키마에 존재 | app_time_limit_min·shortform_limit_count·restricted_categories 필드. 복수 목표의 기준 문서와 구분 |

---

#### 2) 목표 설정 문서 (settings/goals)

* **경로:** users/{uid}/settings/goals
* **설명:** 현재 복수 목표와 원격 동기화 버전을 저장한다. 모임방·멤버 랭킹 대신 사용자별 목표를 관리한다.

| 필드명 (Field) | 유형 (Type) | 제약조건 (Constraint) | 설명 (Description) |
| :--- | :--- | :--- | :--- |
| schema_version | int | 현행 목표 스키마 1 | 목표 문서 형식 |
| version | int | 트랜잭션 갱신·기대 버전 확인 | 원격 변경 충돌 감지 |
| goals | map | DAILY_TIME·DAILY_COUNT·APP_TIME | 목표 종류별 설정 |
| goals.{metric}.target | int | 시간은 분·영상 수는 개수 | 현재 공통 목표값 |
| goals.{metric}.platforms | array<string> | 지원 플랫폼 중 비어 있지 않은 집합 | 적용 앱 |
| goals.{metric}.intervention | string | 정의된 개입 방식 | 기록만·알림·확인·제한 |
| goals.{metric}.status | string | ACTIVE / PAUSED | 활성·중지 |
| goals.{metric}.revision | int | 변경 시 증가 | 목표별 로컬 변경 버전 |
| updated_at | timestamp | 서버 시각 | 저장 시각 |

**확장 설계:** 위 현행 스키마에는 요일별 목표·공휴일 제외·합산/개별 목표를 완전하게 표현하는 필드가 아직 없다. 다음 정보를 추가하고 문서 버전·검증을 함께 갱신한다.

| 확장 정보 | 유형 | 설명 |
| :--- | :--- | :--- |
| 적용 방식 | enum | 합산 / 개별 |
| 목표값 묶음 | map | 합산 공통값 또는 앱별 값 |
| 일정 | enum + map | 매일 동일 / 요일별 활성 여부와 목표값 |
| 공휴일 제외 | boolean | 공휴일이면 목표 미적용 |
| 적용 기준 | date/timezone | 적용 날짜와 시간대 기준 |

---

#### 3) 로컬 시청 세션 기록 (shortformSessions)

* **저장 위치:** DataStore의 일일 UsageSnapshot 내부. 서버의 DOPAMINE_LOG 컬렉션이 아니다.
* **설명:** 시간·횟수 체크포인트와 비동기 카테고리 결과를 같은 세션에 연결한다. OCR 원문 기본 저장소로 사용하지 않는다.

| 항목 | 유형 | 제약조건 | 설명 |
| :--- | :--- | :--- | :--- |
| viewSessionId | string | 세션별 고유 키 | 해당 시청 구간 식별 |
| platform | enum | 지원 플랫폼 | 기록이 발생한 앱 |
| durationSec | int64 | 0 이상·체크포인트 중복 방지 | 누적 시청 시간 |
| count | int64 | 집계 정책에 따른 값 | 실제 반영한 영상 수 |
| category·분류 상태 | enum 등 | 미확인과 처리 중 상태 구분 | AI 결과의 세션별 반영 |
| 사용량 revision·dirty | int64 / boolean | 일일 스냅샷에 관리 | 수정 버전·미전송 여부 |

최근 3개 콘텐츠 식별자 중복 검사 이력은 이 세션 저장과 별도의 실행 중 메모리 상태이다. 원문 OCR 배치는 기본 메모리에서 처리한다.

---

#### 4) 목표 변경 이력 및 날짜별 평가
* **저장 방식:** 구현 시 물리 경로 확정. 단순히 현재 목표 문서를 덮어쓰는 것만으로 과거 평가를 재현하지 않는다.

| 항목 | 유형 | 제약조건 | 설명 |
| :--- | :--- | :--- | :--- |
| 목표 버전 | int/string | 이력별 구분 | 적용된 설정 버전 |
| 유효 시작·종료 | date/time | 기간이 겹칠 때 적용 규칙 필요 | 당시 목표 결정 |
| 당시 목표 설정 | map | 종류·범위·값·요일·공휴일·상태 보존 | 과거 평가 기준 |
| 기록 충분성 | 측정 상태·기간 정보 | 기록 없음과 0 사용 구분 | 기록 부족 판단 근거 |
| 날짜별 평가 | enum / 조회 계산 | 당시 목표와 실제 기록으로 결정 | 달성·일부 달성·초과·기록 부족·목표 제외·오늘 |

달력 상태는 이력과 사용 기록으로 산출한다. 표시 성능을 위해 결과를 캐시하더라도 계산 기준 버전과 갱신 조건을 보존한다.

---

#### 5) 일일 누적 통계 (months 문서 내 days 맵)
* **경로:** users/{uid}/months/{YYYYMM}
* **날짜 키:** days.{YYYYMMDD}
* **설명:** 날짜별 절대값 집계를 월별 문서에 보관한다. 별도의 {uid}_{YYYYMMDD} 문서나 앱 종료 때마다 서버 increment를 호출하는 구조가 아니다.

| 필드명 (Field) | 유형 (Type) | 설명 (Description) |
| :--- | :--- | :--- |
| days.{date}.app_usage | map | 앱별 전체 시간·숏폼 시간·영상 수 |
| days.{date}.category_usage | map | 카테고리별 count·duration_sec |
| days.{date}.hourly_shortform_count | map | 시간 키 00~23의 영상 수 |
| days.{date}.revision | int64 | 서버에서 확인한 일일 버전 |
| days.{date}.sync_id | string | 동일 요청 재전송 식별 |
| days.{date}.deducted_score | int64 | 기존 데이터 형식에 남은 필드. 신규 벌점 기능으로 사용하지 않음 |

**app_usage 내부 구조**
* youtube / instagram / kakaotalk 키를 사용한다.
* app_time_sec: 앱 전체 사용시간(초).
* shortform_time_sec: 일반 숏폼 및 지원되는 시간 전용 콘텐츠의 시청 시간(초).
* shortform_count: 집계 조건을 충족하고 시청이 허용된 일반 숏폼의 영상 수.

**업로드 및 충돌 처리**
* 로컬 revision·dirty·baseRemoteRevision·baseRemoteDigest를 관리하며 서버 요청에는 baseRevision·revision·syncId·baseDigest·집계 데이터를 전달한다.
* syncUsageBatch가 인증·입력 범위·버전과 데이터 일치 여부를 검증한 뒤 트랜잭션으로 저장한다.
* 동일 요청 재전송은 중복 누적하지 않고, 다른 기기에서 갱신한 기록은 충돌로 구분한다. 기본 24시간 업로드 외에 실패 재시도·복구가 있을 수 있다.
* 통계 조회는 로컬 미전송 변경과 서버 버전을 비교하여 조정한다.

---

## 3.3 사용자 통계 제공
로컬 사용량과 서버 동기화 기록으로 시간·영상 수·카테고리 통계를 제공한다. 또한 시간과 횟수의 단위를 구분한다. 제공할 통계 및 시각화 지표는 다음과 같다.
  
### 3.3.1 기본 제공 통계
로그인 후 메인 화면에서 확인하는 요약이다.

| 통계명 | 활용 데이터 | 시각화 방식 | 설명 |
| :--- | :--- | :--- | :--- |
| 오늘의 숏폼 카테고리 | category_usage | 카테고리별 차트·비율 | 분석 결과와 미확인을 구분하고 시청 시간 또는 영상 수 기준을 명확히 표시 |
| 오늘의 플랫폼별 사용량 | app_usage | 막대 그래프·수치 | 앱 전체 시간과 숏폼 시간·영상 수를 구분하며, 시간/횟수 전환 지원 |
| 오늘 목표 진행 | 목표 설정·적용 일정·당일 사용량 | 목표별 진행 카드 | 활성 목표의 남은 양·진행 상태와 권한 필요 상태 표시 |

### 3.3.2 추가 제공 통계
통계 탭에서 기간·앱·지표별로 확인한다. 달력과 상세 시간대 조회는 확장 설계를 포함한다.

| 통계명 | 활용 데이터 | 시각화 방식 | 설명 |
| :--- | :--- | :--- | :--- |
| 기간별 숏폼 시청 추이 | 날짜별 app_usage | 막대·추이 그래프 | 선택 기간의 시간 또는 영상 수 변화를 표시 |
| 시간대별 사용 분석 | 실제 날짜×앱×시간 기록 | 시간대별 막대 그래프·상세 수치 | 기간·앱·시간/영상 수 선택 |
| 앱 내 숏폼 사용 비율 | app_time_sec·shortform_time_sec | 비율 차트 | 앱 전체 시간 대비 숏폼 시간의 비중 |
| 목표 달성 달력 | 당시 목표 이력·일일 사용량·측정 충분성 | 월간 달력·날짜 상세 | 달성·일부 달성·초과·기록 부족·목표 제외·오늘을 구분 |
  
  
