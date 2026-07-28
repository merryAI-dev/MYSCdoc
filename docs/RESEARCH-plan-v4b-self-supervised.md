# V4b 계획 — 구조적 골드로 학습하는 self-supervised abstention

작성: 2026-07-28

## 결론부터

사람 간 일치도는 측정하지 않는다. 대신 자연어 질문의 `answerable` 자동 라벨을 정답으로
간주하지 않고, 동일 질문에서 결정적 그래프 사실을 넣고 뺀 counterfactual pair만 사용한다.
논문에서 주장할 범위도 `사람이 판단한 실제 답변 가능성`이 아니라 `구조적으로 생성한
근거 경계에서의 abstention`으로 제한한다.

두 GPU는 같은 `v4-control/step171` 초기값, 같은 96개 학습 경계쌍, 같은 고정 업데이트
예산을 사용한다.

- **H2-A, entropy-SFT:** 96개 음성 completion을 모두 질문별 고유 문장으로 만든다.
- **H2-B, structural-GRPO:** 교사 completion 없이 그래프 구조 보상만으로 답변/거절을 학습한다.

기존 H1처럼 6개 checkpoint 중 최고를 고르지 않는다. 단일 고정 endpoint만 평가하여
다중 선택과 표본력 손실을 없앤다.

## 1. V4에서 확인한 문제

H1 step57의 자동 FRR은 25.7%였지만, 43개 후보를 전수 육안 검사하자 정당한 거절 6건과
모호 5건이 섞여 있었다. 민감도 분석에서 FRR은 20.5~23.0%로 내려갔지만 control의
3.8~4.3%보다 여전히 높았다.

학습 데이터의 구조적 비대칭도 확인했다.

- H1의 추가 음성 completion 32개가 정확히 같은 짧은 거절문이었다.
- 이 문장은 6 epochs 동안 반복 노출됐다.
- H1 과잉거절 43건 중 같은 고정문이 13번 출력됐고, 그중 10건은 그래프에 직접 답이 있었다.

따라서 다음 루프는 `더 많은 거절`이 아니라 `반복 거절문 제거`와 `생성 loss에서 거절
결정을 분리한 구조 보상`을 비교한다.

## 2. 사람 없이 만드는 구조적 골드

원천은 현재 학습에 쓰지 않은 `v4_unused_pairs_166.jsonl`의 166개 경계쌍이다.

1. 고정 seed로 96쌍을 train, 70쌍을 selection으로 분리한다.
2. positive에는 질문의 결정적 `값` 사실과 주변 사실을 둔다.
3. negative에서는 같은 decision node의 모든 alias 사실을 제거한다.
4. 질문, 주변 사실, 순서는 positive/negative에서 동일하게 유지한다.
5. train, selection, 기존 control train/dev, 봉인 150쌍 간 정규화 질문 hash 겹침은 0이어야 한다.

자연어 질문과 결정적 사실의 연결 오류를 줄이기 위해 다음 세 검문을 모두 통과한 pair만 쓴다.

- 질문으로 검색했을 때 target decision이 top-1이다.
- 로컬 32B가 사실 순서를 두 번 섞어도 positive에서 같은 target value를 선택한다.
- 로컬 32B가 label을 보지 않고 negative를 두 번 모두 `근거 불충분`으로 판정한다.

32B는 pair 입장 필터일 뿐 최종 평가자가 아니다. 실패하거나 두 순서에서 판정이 달라진
pair는 수정하지 않고 제외한다. 부족한 수는 미사용 원천 질문에서 같은 절차로 보충한다.

## 3. 기계 판독 가능한 행동 계약

응답 첫 줄을 다음 둘 중 하나로 고정하고 serving에서 제거한다.

```text
<answer>
<abstain>
```

positive 성공 조건:

- `<answer>`로 시작한다.
- 정규화한 target value가 답변에 포함된다.
- target fact의 확정도를 어조에 반영한다.

negative 성공 조건:

- `<abstain>`으로 시작한다.
- 제거된 target value를 출력하지 않는다.
- 인접 사실을 말하면 실제 negative context에 있는 문자열만 사용한다.

이 계약으로 답변/거절의 주 지표는 32B judge 없이 결정적으로 계산한다. 32B의 자연어 품질
판정은 보조 분석으로만 남기며 성공/실패 게이트에는 쓰지 않는다.

## 4. 두 가설

### H2-A — 반복 문장만 제거하면 FRR이 회복된다

- initializer: `v4-control/step171`
- data: train 96쌍의 positive/negative 192행
- positive completion: target value와 확정도를 포함한 질문별 답변
- negative completion: 부족한 근거를 구체적으로 지목한 질문별 고유 거절
- exact duplicate completion: 0
- objective: completion-only SFT

H1과 달라지는 핵심 변수는 음성 completion entropy다. 별도 classifier, custom loss,
새 dependency는 넣지 않는다.

### H2-B — 구조 보상이 answer/refuse 경계를 더 잘 분리한다

- initializer와 prompt: H2-A와 동일
- prompt당 rollout: 4개
- objective: TRL GRPO, 현재 서버의 vLLM 경로 재사용
- reward는 그래프와 출력 prefix만으로 계산한다.

```text
positive:  +2 <answer>와 target value 모두 충족
           -3 <abstain>
           -2 context에 없는 이름·숫자 생성

negative:  +2 <abstain>이며 target value를 누설하지 않음
           -3 <answer> 또는 target value 누설
           -2 context에 없는 이름·숫자 생성

pair bonus: +1 positive와 negative를 모두 맞힌 경우에만
```

과잉거절 비용을 가장 크게 두고, pair의 한쪽만 맞히는 정책은 bonus를 받지 못한다. 보상
가중치는 이번 루프에서 고정하며 결과를 본 뒤 조정하지 않는다.

## 5. 공정한 비교

- 두 arm 모두 같은 merged initializer와 LoRA rank를 사용한다.
- train pair, prompt 순서, seed 42, 최대 token 수를 고정한다.
- optimizer update 수는 동일하게 맞춘다. rollout 4배 계산량은 update 수에 포함하지 않는다.
- endpoint는 arm당 하나만 저장한다.
- 학습 중 selection 생성이나 reward weight 변경을 금지한다.
- 기존 `v4-control/step171`은 기존 32B judge 지표를 참고값으로만 함께 보고한다. 행동
  prefix를 학습하지 않은 모델이므로 deterministic scorer의 직접 우열 판정에는 넣지 않는다.

seed42에서 게이트를 통과한 arm만 seed43/44를 반복한다. 둘 다 실패하면 추가 seed를 쓰지
않고 V4b를 기각한다.

## 6. 선택과 봉인

selection 70쌍에서 다음을 모두 만족해야 한다.

- positive FRR의 one-sided 95% upper bound ≤ 10%
- negative FAR의 one-sided 95% upper bound ≤ 20%
- pair accuracy ≥ 70%
- target value leakage 0건
- 생성 실패 0건

두 arm이 모두 통과하면 `max(FRR, FAR)`가 낮은 arm을 선택한다. 동률이면 pair accuracy가
높은 arm을 선택한다. 이 규칙은 실행 전에 고정한다.

선택된 단 하나의 arm만 기존 봉인 150쌍을 한 번 연다. 봉인 성공 기준은 selection과 같고,
seed42/43/44의 원자료와 matched 차이를 모두 보고한다. 봉인 실패 뒤 같은 봉인셋을 보며
reward나 데이터를 수정하지 않는다.

## 7. 구현 작업

### Task 1 — pair manifest와 split 고정

Acceptance criteria:

- 96 train / 70 selection pair가 고정 seed와 SHA-256으로 재생성된다.
- 네 데이터 영역의 정규화 질문 겹침이 0이다.
- 입장 검문 탈락 이유와 수가 manifest에 기록된다.

### Task 2 — deterministic scorer

Acceptance criteria:

- `<answer>/<abstain>`, target value, leakage, context 밖 이름·숫자를 한 함수에서 판정한다.
- 최소 self-check가 positive 성공, false refusal, target leakage, unsupported number를 잡는다.
- 32B 출력 없이 주 지표를 재현한다.

### Task 3 — H2-A corpus

Acceptance criteria:

- 192행, 96 pair, positive/negative 1:1이다.
- negative completion exact duplicate가 0이다.
- 모든 completion이 행동 prefix와 질문별 근거를 포함한다.

### Task 4 — H2-B GRPO runner

Acceptance criteria:

- 현재 설치된 TRL/vLLM만 사용하고 dependency를 추가하지 않는다.
- H2-A와 initializer, prompt, seed, update 수가 manifest상 일치한다.
- 중단 후 resume해도 pair 순서와 reward가 같다.

### Task 5 — seed42 gate

Acceptance criteria:

- H2-A와 H2-B를 동일 selection 70쌍에서 한 번 평가하고, frozen control은 기존 judge로만
  참고 보고한다.
- 사전 선택 규칙이 자동 적용되고 통과 arm이 없으면 봉인셋을 열지 않는다.
- 결과 JSON에는 raw counts, confidence bounds, pair별 판정이 남는다.

## 8. 주장하지 않을 것

- 사람과 같은 답변 가능성 판단을 달성했다고 주장하지 않는다.
- 구조적 benchmark 성능을 실제 사내 질문의 유병률로 환산하지 않는다.
- 로컬 32B 입장 필터를 독립적인 인간 골드라고 부르지 않는다.
- seed42 smoke만으로 일반화나 통계적 우월성을 주장하지 않는다.
