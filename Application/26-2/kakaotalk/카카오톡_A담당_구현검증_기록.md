# 카카오톡 숏폼 인식 개선 — 담당 A 구현 결과

## 구현 범위

화면 진입·이탈과 영상 전환을 탐지하고, 확정된 영상의 시청 시간·횟수를 공통 세션 추적기에 연결했습니다.

## 동작 방식

| 기능 | 구현 내용 |
|---|---|
| 진입 판별 | `숏폼`, `쇼츠`, `shorts`, `shortform` 표식과 좋아요·댓글·공유 등 동작 문구를 함께 확인 |
| 펑 오탐 제외 | `펑`만 표시된 화면은 숏폼 후보로 분류하지 않음 |
| 영상 식별 | 버튼·카운터·재생시간 등 변동 텍스트를 제외하고 남은 문구를 정규화해 해시 생성 |
| 진입·전환 안정화 | 동일 영상 키가 300ms 이어질 때 세션 확정; 다른 키가 잠시 나타나면 기존 세션을 멈추고 새 영상 확정 대기 |
| 오버레이 | 댓글·공유 시트에서 동일 영상 세션을 유지하고 시청 시간은 일시정지 |
| 짧은 인식 공백 | 화면 근거가 사라진 뒤 최대 1.5초 동안 세션을 멈춘 상태로 유지 |
| 광고·앱 전환 | 광고는 일반 숏폼 통계에서 제외; 카카오톡을 벗어나면 추적 상태 초기화 |
| 모델 연결 | 담당 B 분류 결과가 일반 숏폼이면 임시 세션을 확정하고, 광고·비숏폼이면 집계하지 않음 |

## 최종 점검에서 반영한 수정

초기 진입 후 300ms 안정화가 끝나기 전 여러 접근성 이벤트가 들어오면 후보 키가 사라져 임시 세션이 종료될 수 있었습니다. 대기 응답에도 같은 후보 키를 전달하도록 고쳤습니다. 중간 이벤트 뒤에도 임시 세션 ID와 시작 시각이 유지되는 회귀 테스트를 추가했습니다.

## 자동 테스트 구성

`KakaotalkTrackingTest`는 진입 안정화, 동일 영상 유지, 빠른 스크롤, 안정된 영상 전환, 앱 복귀, 댓글 오버레이, 광고 제외, 시청시간·횟수 집계를 다룹니다. 이번 수정에 초기 안정화 중 임시 세션 유지 테스트를 추가했습니다. `KakaotalkScreenDecisionTest`와 `KakaotalkPreprocessingParityTest`는 화면 분류 결정 및 모델 전처리 일치를 다룹니다.

## 수집 이미지 요약

| 클래스 | 행 수 |
|---|---:|
| 일반 숏폼 (`NORMAL_SHORTFORM`) | 283 |
| 광고 (`AD`) | 23 |
| 사진 게시물 | 0 |
| 기타 | 0 |
| 합계 | 306 |

306행은 ZIP 파일 경로 기준 수량입니다. 두 번째 ZIP 내부에 SHA-256이 같은 반복 파일 18개가 있어 `duplicate_group`으로 표시했습니다. 클래스 분포 그래프와 이미지 경로는 [데이터 수집·라벨링표](./카카오톡_데이터수집_라벨링표.xlsx)에 있습니다. 담당 B의 모델 학습 수치와 그래프는 [모델 학습 결과](./담당B_모델_학습_결과.md)를 참조합니다.

## 주요 코드

- `app/src/main/java/com/example/dopaminecut2/logic/shortform/KakaotalkTracking.kt`
- `app/src/main/java/com/example/dopaminecut2/logic/manager/KakaotalkManager.kt`
- `app/src/main/java/com/example/dopaminecut2/service/AppBlockService.kt`
- `app/src/test/java/com/example/dopaminecut2/logic/shortform/KakaotalkTrackingTest.kt`
