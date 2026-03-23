# 숏폼 역할 담당하신분들 읽어주세용

## 일정 안내
**기한**: ~ 2026. 04. 05 (수)
**목표**: 플랫폼별 숏폼 인식 로직 구현 완료

---

### 1. 플랫폼별 로직 작성하기
YoutubeManager.kt, InstagramManager.kt, TikTokManager.kt, KakaotalkManager.kt
각 매니저에서 구현해야하는 함수 목록
숏폼 화면 판별 (isShortformSection): 일반 피드나 홈 화면에서 오작동하지 않도록, 실제 숏폼 화면에 진입했을 때만 감지합니다.

영상 고유 식별자 추출 (getVideoIdentifier): 시청 횟수 중복 카운팅을 막기 위해 현재 영상만의 고유 ID를 생성해 냅니다.

광고 필터링 (isAdContent): 숏폼 영상 사이사이에 나오는 광고의 UI 텍스트를 감지하여 숏폼 시청 횟수 카운트에서 제외합니다.

### 2. 화면 구조 추출법
1. ShortformBlockService의 기존 내용 모두 주석처리하고, 하단에 이미 주석처리된 내용을 주석 해제해서 빌드하기

2. 안드로이드 스튜디오 애뮬레이터(Running Devices)에서 Settings -> Accessibility -> Dopaminecut2 에서
	Use Dopaminecut2 Allow하면 좌측 상단에 빨간색 LOG라고 적힌 버튼이 생깁니다.
	
3. Log버튼을 누르면 Logcat에서 현재 화면의 구조를 출력해줍니다.
