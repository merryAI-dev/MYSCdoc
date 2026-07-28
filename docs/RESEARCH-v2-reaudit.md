# Chat SFT v2 재감사 및 V4 봉인셋

기준일: 2026-07-28

## 결론

- 서버 교사 원본 528행은 실제 v2 `train=449 + dev=79`와 prompt/completion 해시 멀티셋이 정확히 같다. 학습 데이터의 출처와 순서는 복원됐다.
- 기존 `unanswerable_hard`는 신뢰할 골드가 아니다. 정답 노드를 제거해도 주변 사실이 질문에 답하는 사례가 남았다.
- 32B 재판정기는 528행을 모두 판정했지만 사람과 아직 보정되지 않았다. 특히 “거절 후 인접 정보 제공”을 `답변`으로 세는 오류가 육안 표본에서 확인돼, 아래 수치는 잠정치이며 논문 성과 수치로 쓰지 않는다.
- 학습·audit·selection·기존 test와 질문이 겹치지 않는 봉인 후보 경계쌍 150개(300행)를 만들고 SHA-256을 기록했다. 사람 검증 export 전 상태는 `pending_human_verification`이다.

## 1. 원본 복원

| 항목 | 값 |
|---|---:|
| 교사 원본 | 528 |
| 실제 train | 449 |
| 실제 dev | 79 |
| 코퍼스에서 누락된 교사 예시 | 0 |
| 교사에 없는 코퍼스 예시 | 0 |
| 교사 파일 SHA-256 | `47fc95e13c2d4a1c94bbb1365443363bf38ebb936dc0ed2b972b2aa80f10cb12` |

## 2. 32B 의미 기반 재판정

판정 실패는 0/528이다. 판정 결과 파일 SHA-256은 `d6150e6ae59622d2ab584fd513b410fed0a34cb0e96745f708c9e55c8b880961`이다.

| 원래 생성 라벨 | 답변 | 부분답변 | 거절 | 합계 |
|---|---:|---:|---:|---:|
| answerable | 177 | 66 | 17 | 260 |
| unanswerable | 11 | 20 | 30 | 61 |
| unanswerable_hard | 68 | 99 | 40 | 207 |
| 합계 | 256 | 185 | 87 | 528 |

원래 라벨을 정답이라고 가정했을 때 32B가 계산한 잠정 지표는 Honesty 0.820, Prudence 70.5%, 과잉거절 6.5%, answerable 확정도 반영 81.2%다. `fabricated=false`는 528/528이었지만 이것도 판정기 보정 전이라 사실로 확정하지 않는다.

답이 전혀 없어야 하는 음성 예시를 엄격히 `완전 거절`만 채택하면 70/268(26.1%)뿐이다. 이 값은 교사 품질만 뜻하지 않는다. 원래 hard negative 자체에 답 가능한 주변 사실이 남은 데이터 생성 오류가 섞여 있다.

### 표적 육안 재검사

오류 가능성이 큰 불일치 15건을 직접 읽었다. 무작위 정확도 표본은 아니므로 비율 추정에는 쓰지 않는다.

- 음성인데 32B가 `답변`이라 한 10건: 내 판정은 답변 4, 부분답변 1, 거절 5였다. 32B가 시스템 루브릭상 거절인 “없음 + 인접 정보”를 답변으로 올린 사례가 반복됐다.
- answerable인데 32B가 `거절`이라 한 5건: 다섯 건 모두 실제 제공 사실이 질문 핵심을 담지 않아, 32B의 거절 판정이 타당했다. 이쪽은 기존 answerable 생성 라벨이 과대했다.

따라서 32B 결과를 `meta.audit=exaone-4.0.1-32b-judge-unvalidated`로 붙인 control corpus는 만들었지만, 사람 보정 전에는 학습·선택의 확정 골드로 취급하지 않는다.

## 3. 사람 보정용 블라인드 100건

`source_label × judge_response` 9개 층을 모두 포함한 100건을 고정 시드 `20260728`로 뽑았다. 블라인드 파일에는 기존 라벨과 32B 판정을 넣지 않았고, 키는 별도 파일로 분리했다.

- 블라인드: `research/runs/chat_teacher_v2_human_audit100.jsonl`
- SHA-256: `613b0936c74906d240401117fb9c47e558a1c7b01fb66c7778880e6d89a81790`
- 생성기: `research/sample_teacher_judge_audit.py`

이 100건에서 response 3분류 Cohen's κ와 fabricated precision/recall을 계산하기 전에는 32B 판정치를 논문 본문 수치로 승격하지 않는다.

## 4. V4 봉인 평가셋 후보

원천 RL 질문 905개에서 다음 질문을 모두 제외했다.

- v2 control train/dev
- 281 audit 사례
- 199 selection 행
- 기존 201 test 행

중복을 합치면 제외된 고유 질문은 739개다. 남은 질문에서 150개를 고정 시드 `20260728`로 선택했다. 각 질문은 다음 두 행으로 구성된다.

1. positive: 정답 노드의 `값` 사실 한 개 + 같은 노드가 아닌 BM25 상위 주변 사실 19개
2. negative: positive에서 그 `값` 사실 한 개만 제자리에서 제거한 19개 사실

같은 정답 노드의 다른 사실은 양쪽 모두에서 제외했다. 따라서 negative에 정답의 근거·대상·주체가 남는 기존 hard-negative 오류를 막았다.

| 검문 | 결과 |
|---|---:|
| 행 / 고유 질문 | 300 / 150 |
| positive/negative 쌍 | 150 |
| train/dev 질문 겹침 | 0 |
| audit 질문 겹침 | 0 |
| selection 질문 겹침 | 0 |
| 기존 test 질문 겹침 | 0 |
| `train_chat_sft.py --self-check` | 통과 |
| V4 pair validator | 통과 |
| 봉인 SHA-256 | `a4b7a1c710abe0ecdb788dcb95823b81fb3f13963cc39aa8f3c61923210b2e32` |

서버 배치 경로는 `/data/tta/mydoc-kg/scripts/chat_test_v4_sealed.jsonl`이며 로컬과 SHA-256이 같다. 검증 페이지 `research/runs/chat_sealed_gold_review.html`에서 300행을 확인해 `chat_sealed_gold_verified.json`을 export한 뒤 최종 봉인한다. 최종 봉인셋은 체크포인트 선택이나 프롬프트 조정에 사용하지 않고, 공통 epoch를 고른 뒤 한 번만 연다.

## 5. 감사 중 바로잡은 표본수

- audit: 281개 고유 질문이 아니라 **281개 사례 / 232개 고유 질문 / 양·음 쌍 49개**
- selection: 199개 고유 질문이 아니라 **199행 / 167개 고유 질문 / 양·음 쌍 32개**

V4 preflight가 이 실제 구조를 검사하도록 수정했다. 행 수를 고유 질문 수로 보고하던 이전 표현은 사용하지 않는다.

## 6. 실행환경 재확인

- 등록된 TTA SSH 호스트와 키 접속: 정상 (주소는 저장소에 기록하지 않음)
- GPU: H100 80GB 2장, 재감사 종료 후 고아 `judge_answers.py`/EngineCore 없음
- 32B 모델: `/data/tta/EXAONE/exaone-4.0.1-32B-local`, 실제 추론 성공
- vLLM 환경: `/data/tta/mydoc-kg/env`; 비로그인 SSH에서는 `PATH=/data/tta/mydoc-kg/env/bin:$PATH`가 필요
- DB 기반 데이터 빌더 환경: `psycopg2`, `rank_bm25`, `MYDOC_DB_PASSWORD` 주입이 현재 준비되지 않았다. 봉인셋 빌더는 이 불필요한 결합을 제거하고 기존 `rl_corpus.jsonl`만으로 재현되게 바꿨다.
