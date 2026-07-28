# V4 seed 42 병렬 smoke 설계

날짜: 2026-07-28

## 목적

Kanana 검토는 보류하고, 이미 vLLM 학습·평가 경로가 검증된 EXAONE 4.0 1.2B에서 V4의 첫 인과 대조를 실행한다. 이번 실행은 데이터와 코드가 끝까지 작동하는지 확인하는 exploratory smoke이며 논문 성과 확증으로 사용하지 않는다.

## 비교군

- `v4-control`: 역사적 v2의 449 train / 79 dev prompt·completion과 순서를 유지하고 재감사 메타데이터만 붙인 코퍼스
- `v4-h1-boundary`: control train의 answerable 32행과 unanswerable 32행을, 결정적 `값` 사실 하나만 추가·제거한 경계쌍 32개(64행)로 같은 위치에서 교체한 코퍼스
- 두 군은 model, seed 42, recipe, dependency, system prompt, dev 파일이 같아야 한다.

## 데이터 예약 수정

현재 원천 905개 중 기존 train/dev·audit·selection·test와 겹치지 않는 질문은 166개다. 현재 봉인 후보가 150개를 먼저 사용해 학습용 질문이 16개만 남으므로 그대로 학습하지 않는다.

1. 166개 중 32개를 V4 H1 학습 경계쌍으로 먼저 고정한다.
2. 나머지 134개를 봉인 후보에 유지한다.
3. RL 질문에 없던 결정 노드에서 16개 질문을 결정론적 템플릿으로 추가 생성한다.
4. 새 봉인 후보 150쌍을 다시 만들고 해시·검증 페이지를 갱신한다.
5. train/dev, audit, selection, 기존 test, H1, 새 봉인 후보 간 정규화 질문 겹침이 모두 0이어야 한다.

현재 봉인 후보 SHA-256은 이 재분할로 폐기하며, 사람 검증 전이므로 발표나 모델 선택에 사용하지 않는다.

## 실행

- GPU0: `v4-control`, seed 42
- GPU1: `v4-h1-boundary`, seed 42
- completion-only LoRA, 6 epochs, max length 8192, r=16, dropout=0.1, learning rate 2e-4
- 모든 epoch checkpoint를 보존한다.
- 학습 전에 `--check-only`가 pair 구조, -64/+64 교체, dev byte equivalence, 질문 누수, token 절단 0을 통과해야 한다.
- 한 군이라도 preflight 또는 학습에 실패하면 다른 군 결과도 비교에 사용하지 않는다.

## 학습 후

- 봉인셋은 열지 않는다.
- selection 199행에서 두 군의 여섯 epoch를 생성·채점한다.
- 12개 arm×epoch 후보를 고려해 다중 선택을 보정하고, 과잉거절률의 question-clustered 단측 95% 상한이 10% 이하인 후보만 인증한다.
- smoke가 정상이고 방향성이 있으면 사람 100건 판정기 보정 후 seed 43·44를 추가한다.
- H2 오라벨 정리와 Kanana backbone 재현은 별도 후속 실험으로 둔다.

## 성공 조건

- 두 학습 모두 종료, global step 342, checkpoint 6개
- 데이터·코드·환경 manifest 저장
- 절단 0, 빈 completion 0, 비정상 loss/NaN 0
- 학습 및 평가 프로세스 종료 후 고아 GPU 프로세스 0

## 실행 전 검문에서 발견한 평가 누수와 수정

독립 질문 집합 검문에서 과거 audit 232개 중 108개, selection 167개 중 42개가
control train/dev 질문과 겹쳤다. 두 파일은 V4에 재사용하지 않는다. 학습 코퍼스와
봉인셋을 고정한 뒤 남은 RL 질문 356개에서 selection 167개(199행, 경계쌍 32)와
사람 판정기 보정용 audit 80개(100행, 경계쌍 20)를 서로 겹치지 않게 새로 만든다.
audit 100행은 사전 계획의 블라인드 사람 라벨링 규모와 일치한다.

## 비목표

- seed 42 한 번으로 성능 향상을 확증하지 않는다.
- 봉인셋 결과를 보며 데이터·프롬프트·epoch를 수정하지 않는다.
- Kanana를 이번 smoke에 섞지 않는다.
