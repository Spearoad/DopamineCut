# 카카오톡 숏폼 화면 분류 (담당 B — 모델·학습)

인스타그램 B와 같은 구조(MobileNetV3-Small + `whole_ui_v1` 전처리 + ONNX)로 카카오톡 화면을 4개 클래스로 분류한다.
펑(24시간 스토리)은 수집 데이터가 부족해 제외했다(펑 화면은 신뢰도 미달 → UNKNOWN 처리를 기대).

## 클래스

| 라벨 | 화면 | 앱 처리 |
|---|---|---|
| `SHORTFORM_NORMAL` | 지금 탭 숏폼 일반 영상 | 시청 시간·횟수 집계 |
| `SHORTFORM_AD` | 숏폼 사이 광고 | 집계 제외(광고) |
| `NOW_OPENCHAT` | 지금 탭의 숏폼 외 화면(오픈채팅 목록·추천 등) | 집계 제외 |
| `CHAT_FRIENDS` | 친구 탭·채팅 목록·채팅방 | 집계 제외 |

신뢰도가 클래스별 임계값보다 낮으면 `UNKNOWN`(집계 제외)이며, 앱은 같은 화면에서 최대 3번까지 다시 판정한다.
라벨 순서는 `common.py`의 `LABELS`와 앱의 `KakaotalkScreenDecision.MODEL_CLASSES`가 같아야 한다.

## 1. 환경

```bash
python -m venv C:\kkv
C:\kkv\Scripts\python -m pip install -r requirements.txt
```

Windows 경로 길이 제한 때문에 가상환경은 `C:\kkv`처럼 짧은 경로에 만든다.

## 2. 수집 (안드로이드 폰 USB 디버깅 연결)

> 앱이 안드로이드 전용(접근성 서비스)이므로 학습 데이터도 **안드로이드 카카오톡 화면**이어야 한다.
> 아이폰 캡처는 해상도·상태바·화면 구성이 달라 쓰지 않는다. 카카오톡은 계정 1개당 휴대폰 1대만 로그인되므로
> 아이폰 사용자는 수집 대신 라벨 검수(3단계)와 실험·분석(4~5단계)을 맡는 식으로 나눈다.

```bash
python collect.py --label SHORTFORM_NORMAL --count 150 --interval 2 --collector 김동욱
python collect.py --label SHORTFORM_AD --interval 0 --collector 김동욱
```

- 자동 모드는 2초마다 캡처하므로 그 사이에 다음 영상으로 넘긴다. 광고처럼 드문 화면은 `--interval 0`(Enter마다 캡처).
- 카카오톡이 앞 화면이 아닐 때(알림창, 다른 앱)는 저장하지 않는다. 같은 화면이 연속이면 건너뛴다.
- 클래스당 최소 100장, 서로 다른 영상·채팅방을 섞는다. 다크/라이트 모드도 섞으면 좋다.
- `data/<LABEL>/`에 저장되고 수집자·기기·안드로이드·카카오톡 버전·다크 모드·해상도는
  `data/manifests/<세션>.csv`에 기록된다(제출용 수집 기기 정보).
- **여러 사람 데이터 합치기:** 각자의 `data/` 폴더를 한 `data/`에 그대로 복사하면 된다. 파일 이름에 수집 시각·기기명이
  들어가서 겹치지 않는다.
- `data/`에는 친구 이름·채팅 내용이 들어 있으므로 저장소에 올리지 않는다(`.gitignore` 처리됨). 팀원과는 압축 파일로 직접 주고받는다.

## 3. 라벨 검수

```bash
python review.py
```

`artifacts/review/<LABEL>.jpg` 썸네일을 보고 잘못 찍힌 파일은 맞는 폴더나 `data/_unsorted/`로 옮긴다.

## 한 번에 실행 (4~5단계 전체)

```bash
python run_all.py                 # 검수 요약 → 학습 → 모델 비교 → 그래프 → ONNX 변환·앱 설치
python run_all.py --skip-compare  # 모델 비교 실험 생략(빠름)
```

아래는 각 단계를 따로 실행하는 방법이다.

## 4. 학습·하이퍼파라미터 비교

```bash
python train.py
```

- 학습 70% / 검증 15% / 테스트 15%. 연속 5프레임을 한 그룹으로 묶어 같은 영상이 학습·평가에 섞이지 않게 나눈다.
- 학습률·라벨 스무딩·백본 고정 4개 설정을 비교해 검증 Macro F1이 가장 높은 모델을 고른다.
- 결과: `artifacts/hparam_results.csv`(설정 비교표), `confusion_matrix.csv`, `classification_report.txt`, `report.json`.
- 최종 성능은 선택에 쓰지 않은 **테스트셋** 기준으로 보고한다.

### 모델·전처리 선정 근거 실험 (선택)

```bash
python compare.py
```

같은 분할·하이퍼파라미터로 MobileNetV3-Small(UI 타일 / 전체화면 축소), MobileNetV3-Large, ResNet-18을 학습해
테스트 Macro F1, 모델 크기, CPU 추론 시간을 비교한다(`artifacts/compare_results.csv`).

### 보고서용 그래프·지표

```bash
python plots.py
```

| 파일 | 내용 |
|---|---|
| `figures/01_learning_curves.png` | 손실 곡선 + 정확도·Macro F1 곡선(선택 epoch 표시) |
| `figures/02_confusion_matrix.png` | 테스트셋 혼동행렬(개수·재현율) |
| `figures/03_per_class_metrics.png` | 클래스별 정밀도·재현율·F1 |
| `figures/04_hparam_comparison.png` | 하이퍼파라미터 설정별 검증 Macro F1 |
| `figures/05_model_preprocess.png` | 모델·전처리 후보 비교(`compare.py` 실행 시) |
| `metrics_summary.md` | 정확도(정답 수/전체)·Macro F1 계산 과정 표 |

## 5. ONNX 변환·앱 설치

```bash
python export.py --install
```

PyTorch와 ONNX Runtime 출력이 일치하고 테스트 정확도가 같을 때만 `app/src/main/assets/models/kakaotalk_shortform_state.{onnx,json}`에 복사한다.
JSON에는 SHA-256, 라벨, 전처리 규격, 클래스별 임계값, 수집 기기 정보가 들어가며 앱이 로드할 때 모두 검증한다.

## 6. 전처리 일치 검증

```bash
python parity.py
```

출력 CRC32가 `app/src/test/.../KakaotalkPreprocessingParityTest.kt`의 기대값과 같다(Python `ui_tiles_v1` ↔ Kotlin `WholeUiTiles` 픽셀 일치).

## 7. 실기기 테스트

1. `export.py --install` 후 앱 빌드·설치, 접근성 서비스 켜기.
2. Logcat 필터 `tag:AppBlockService`.
3. 시작 시 `✅ KakaoTalk CV 모델 메모리 로드 및 무결성 검증 성공!` 확인.
4. 카카오톡 숏폼을 넘기면 `KakaoTalk 콘텐츠 판정: uiClass=..., conf=..., latencyMs=...`가 찍힌다.
   숏폼이 아닌 화면은 `KakaoTalk 비숏폼/미확인 판정`으로 집계에서 빠진다.

모델 파일이 없으면 앱은 `❌ KakaoTalk CV 모델 초기화 실패` 로그를 남기고 기존 접근성 텍스트 판정으로 동작한다.
