# mydoc 연구 코드 — 한국어 회의록 지식그래프의 로컬 모델 파이프라인

사내 회의록에서 결정을 추출해 지식그래프로 만들고, 그 그래프를 검색·답변하는 파이프라인을
**로컬 소형 모델(EXAONE 4.0-1.2B)로 옮기는** 과정의 코드와 실측 기록입니다.

이 저장소는 논문 부속 코드입니다. **재현보다 정직한 기록을 우선**했습니다 — 실패한 시도와
틀린 판단도 남겨 두었고, 어떤 수치가 어디서 나왔는지 추적할 수 있게 했습니다.

---

## 0. 한 줄 요약과 경고

| | |
|---|---|
| 무엇 | Gemini 의존 파이프라인 → 로컬 1.2B로 이전 (추출 · 검색 재작성 · 답변) |
| 데이터 | 사내 회의록 180문서 + Slack 3채널 1,369메시지 (비공개) |
| 하드웨어 | 학습·판정 2×H100(대여, 기간 한정), 서빙 Apple M5 |
| 라이선스 | **EXAONE 4.0은 비상업(NC) 라이선스.** 학습한 어댑터도 그 제약을 상속합니다 |

**⚠️ 데이터는 공개하지 않습니다.** 사내 회의록에 투자심의 결정·고객사 딜·참석자 실명이
들어 있습니다. 코드와 집계 수치만 공개합니다.

---

## 1. 핵심 결과 (전부 우리 실측)

### 1-1. 추출 — 로컬이 형식 안정성에서 앞섬

Slack 217스레드, 동일 청킹·동일 프롬프트·동일 스키마 강제:

| | 청크 파싱 | 결정 추출 | 소요 | 비용 |
|---|---|---|---|---|
| 로컬 1.2B (증류) | **524/524 (100%)** | 165 | 87초 | 0 |
| Gemini 2.5 Flash | 521/524 (99.4%) | 228 | 220초 | API |

형식은 로컬이 이기고 **재현율은 Gemini가 38% 높습니다.** 어느 쪽이 옳은지는 사람 판단이
없어 미결입니다(§4 한계).

### 1-2. 사람 판단 정확도 — 유의차 없음

회의록 val 세트, 블라인드 라벨 50건:

| | 정확도 | 95% CI |
|---|---|---|
| Gemini 2.5 Flash | 24/25 = 96% | [80%, 99%] |
| 로컬 1.2B | 23/25 = 92% | [75%, 98%] |

차이 4pt, p=0.55. **"같다"가 아니라 "이 표본으로는 차이를 못 잡는다"**가 정확한 표현입니다.
4pt를 유의하게 잡으려면 팔당 550건이 필요합니다.

### 1-3. 검색 — RL 쿼리 재작성이 실제로 개선

held-out 157문항(32B가 합성한 어휘 간극 질문), 로컬 BM25:

| 조건 | hit@1 | hit@10 |
|---|---|---|
| 질문 그대로 (현행) | 17.8% | 37.6% |
| 무학습 1.2B 재작성 | 17.2% | **31.8%** ← 오히려 악화 |
| **RL 학습 1.2B (step 375)** | **26.8%** | **48.4%** |

**무학습 재작성이 검색을 해친다**는 점이 중요합니다. 이 조건을 안 쟀으면 "1.2B에 재작성
시키면 된다"는 잘못된 결론을 냈을 것입니다.

제품 경로(Java BM25) 소규모 확인: 원 질문 8/15 → RL 재작성 10/15, 하락 사례 0.

### 1-4. 출처 — 베껴쓰기 대신 문장 번호

| 방식 | 위치 확정 | 출처 없는 결정 | 파싱 실패 |
|---|---|---|---|
| 원문 베껴쓰기 | 94% | 25% | 13.3% |
| 베껴쓰기 강제 | 94% | 0% (강제) | 0.6% |
| **문장 번호 고르기** | **100%** | 6.0% (모델이 실토) | **0%** |

번호를 고르게 하면 베낄 일이 없어 위치 오류가 원리적으로 사라집니다. 마지막 열의 6.0%는
**모델이 스스로 "근거 없음"을 고른 비율**로, 강제 방식에서는 보이지 않던 값입니다.

### 1-5. 부정적 결과 (기록)

- **CMS 어댑터(빠른/느린 가중치 분리)**: 망각 없음, 이득도 없음(최근 손실 0.6% 개선).
  1개월치 데이터엔 적응할 드리프트 자체가 없었습니다.
- **곱셈식 가중치 랭킹**: known-item hit@1 95.3% → 91.9%로 정답을 눌렀습니다. 폐기하고
  덧셈식(λ=0.1)으로 교체했습니다.
- **형태소 기반 충실성 프록시(syntax_grounding)**: 사람 판단과 무상관(φ=0.034).
  사람이 맞다고 한 47건 중 28건(60%)을 탈락시켰습니다. **정확도 지표로 쓰면 안 됩니다.**

---

## 2. 우리가 겪은 실패 — 같은 실수를 피하려면

공개 코드에서 보통 지우는 부분이지만, 이 목록이 가장 실용적인 내용이라고 생각해 남깁니다.

| 실패 | 증상 | 원인 | 교훈 |
|---|---|---|---|
| 학습 시퀀스 절단 | 학생이 생성을 못 멈춤 | `max_seq_len=8192`에 12/160건이 잘려 EOS 없는 타깃 학습 | **학습 전 토큰 길이 분포와 절단 수를 반드시 찍을 것** |
| 분포 불일치 | 평가에서 번호 지목 0건 | 학습은 해석된 텍스트로, 서빙은 번호로 | 학습 입력과 서빙 입력이 같은지 확인 |
| 프록시 맹신 | "게이트가 충실성을 높인다" 결론 | 사람 라벨 없이 프록시로 판정 | **지표를 만들면 사람 라벨로 검증부터** |
| 탐지 불가 지표로 안전 선언 | "강제해도 지어내지 않는다" | 원문 대조는 '사후 정당화'를 못 잡음 | 그 지표가 그 실패를 탐지할 수 있는지 먼저 물을 것 |
| 통제 실패 | "모델만 교체" 비교 | vLLM만 스키마 강제, Gemini는 프롬프트만 | 백엔드별 강제 방식을 맞출 것 |
| 포화된 baseline | naive hit@1이 이미 92.9% | 노드 라벨 어휘로 질문을 만듦 | 학습 여지가 있는지 먼저 측정 |
| GPU 고아 프로세스 | 70GB 점유로 재시작 실패 | `pkill`이 vLLM 자식을 못 죽임 | 시작 전 `nvidia-smi`로 여유 확인 |
| Gemini 스키마 폭주 | title 무한 반복, 파싱 7/17 | `propertyOrdering`·`required` 미지정 | OpenAPI 방언은 순서를 보장하지 않음 |
| **등급 역산** | 학습 데이터 27%가 오라벨, `[조건부]` 229건 전부 오탐 | `weight = 확정도 × 위상`인데 그 **곱을 구간으로 잘라 등급을 되돌림** | **파생값에서 원본을 복원하지 말 것.** 원본 라벨을 따로 보관 |
| **키워드 거절 탐지** | 정답 47건 중 28건(60%)을 거절로 오탐, 지어낸 답 2건은 거절 성공으로 집계 | "없어요" 부분일치. 교사 문체가 유보를 앞세우고 답하는 형태 | **지표를 골드에 먼저 걸어볼 것.** 교사 자신이 0.70이면 그 지표는 못 쓴다 |
| **선택셋 = 발표셋** | 최종 수치가 체크포인트 6개에 대한 선택 최적값 | 같은 66건으로 학습 중 평가·체크포인트 선택·논문 수치를 다 냄 | 고르는 셋과 발표하는 셋을 나눌 것 |
| **런처가 다른 스크립트 호출** | Honesty 선택 코드를 짜 놓고 loss 선택으로 학습됨 | `night_chat_sft.sh`가 `train_exaone_sft.py`(`load_best_model_at_end=True`)를 부름 | **실행 스크립트도 코드다.** 감사 범위에 넣을 것 |

---

## 3. 코드 지도

### 3-1. 추출

| 파일 | 역할 |
|---|---|
| [`chunking.py`](chunking.py) | 문단 경계 청킹·병합·JSON 파싱. **조건 간 공용** — 갈라지면 비교가 무효 |
| [`korean_syntax.py`](korean_syntax.py) | Kiwi 기반 결정 어미 판정. Java `KoreanSyntax`와 self-test로 동치 확인 |
| [`extract_sentid_any.py`](extract_sentid_any.py) | 문장 번호 방식 추출. `--backend vllm|gemini`로 모델만 교체 |
| [`exaone_teacher_sentid.py`](exaone_teacher_sentid.py) | 32B 교사 데이터 생성 (번호 방식) |
| [`extraction_schema.py`](extraction_schema.py) | 디코딩 강제용 JSON 스키마 |

### 3-2. 등급 판정 (노드 가중치)

| 파일 | 역할 |
|---|---|
| [`judge_weights.py`](judge_weights.py) | v1 — 확정/조건부/예정/논의 단일 축 |
| [`judge_weights_v2.py`](judge_weights_v2.py) | **v2 — 조건부를 별도 축으로 분리** (근거: [문서](../docs/RESEARCH-grade-conditioning.md) §3) |
| [`analyze_weight_semantics.py`](analyze_weight_semantics.py) | 표면 어미 규칙 vs LLM 판정 교차표·괴리 사례 |
| [`backfill_weights.py`](backfill_weights.py) | 판정 → DB weight 컬럼 |

### 3-3. 검색 RL

| 파일 | 역할 |
|---|---|
| [`build_rl_dataset.py`](build_rl_dataset.py) | **Phase 0 감사** — 검색 불가·답 유출 질문 제외, headroom 측정 |
| [`gen_hard_questions.py`](gen_hard_questions.py) | 어휘 간극 질문 합성 (노드 어휘 금지) |
| [`grpo_train_search.py`](grpo_train_search.py) | GRPO 단일턴 쿼리 재작성 |
| [`eval_rl_checkpoints.py`](eval_rl_checkpoints.py) | 3조건 비교 (원 질문 / 무학습 / RL) |

### 3-4. 챗 답변 SFT

| 파일 | 역할 |
|---|---|
| [`build_chat_sft_jobs.py`](build_chat_sft_jobs.py) | 등급 붙인 사실 목록 + 거절 질문 |
| [`build_hard_abstention.py`](build_hard_abstention.py) | **어려운 거절** — 골드만 제거해 "주제는 겹치는데 답은 없음" |
| [`gen_chat_teacher_v2.py`](gen_chat_teacher_v2.py) | 32B 교사 — 등급 근거 설명 포함 |
| [`build_chat_eval_split.py`](build_chat_eval_split.py) | 선택셋/테스트셋 분할. 학습 질문 제외, 짝은 한쪽에 통째로 |
| [`judge_answers.py`](judge_answers.py) | **32B 채점** — 거절/부분답변/답변 3분류 + 지어냄. 키워드 탐지기를 대체 |
| [`../scripts/train_chat_sft.py`](../scripts/train_chat_sft.py) | 학습. 데이터 검문 → 설정 덤프 → 에폭별 저장 |
| [`../scripts/select_chat_checkpoint_vllm.py`](../scripts/select_chat_checkpoint_vllm.py) | 체크포인트별 답변 생성 (LoRA 핫스왑). 채점은 위 파일이 |
| [`../scripts/select_chat_checkpoint.py`](../scripts/select_chat_checkpoint.py) | HF 판. transformers만으로 재현하려는 경우 |
| [`night_chat_sft.sh`](night_chat_sft.sh) | 전체 실행 — v1/v2를 GPU 한 장씩 잡아 병렬 학습 |

### 3-5. 평가·기타

| 파일 | 역할 |
|---|---|
| [`build_gold_labeling.py`](build_gold_labeling.py) | 블라인드 사람 라벨링 도구 (로컬 HTML) |
| [`build_salience_labeling.py`](build_salience_labeling.py) | 중요도 + **누락 보고** 수집 |
| [`eval_ranking.py`](eval_ranking.py) | known-item 검색 평가 + 가드레일 |
| [`measure_syntax_grounding.py`](measure_syntax_grounding.py) | (실패한) 프록시 지표 — 반례로 보존 |
| [`fix_config_for_mlx.py`](fix_config_for_mlx.py) | transformers 5.x → MLX config 변환 |

### 3-6. 대체된 것 (참고용 보존)

`exaone_extract.py`(순차 HF, vLLM판으로 대체) · `gen_chat_teacher.py`(v1, ablation 대조군) ·
`judge_weights.py`(v1, 동일) · `build_cms_data.py`(효과 없음으로 결론)

---

## 4. 한계 — 논문에 그대로 실을 것

1. **단일 조직·단일 도메인.** 임팩트 투자사 회의록. 일반화 주장 불가.
2. **사람 라벨이 1인 판단.** 다중 어노테이터 일치도(κ) 미측정.
3. **표본이 작음.** 정확도 비교 팔당 25건, 중요도 13건. 4pt 차이를 잡으려면 팔당 550건 필요.
4. **교사-학생이 독립이 아님.** 학생은 교사 출력으로 증류됐습니다. 교사에서 파생된 어떤
   지표로도 둘을 비교할 수 없습니다 — 사람 라벨만 독립적입니다.
5. **Gemini는 폐쇄 모델.** 버전이 바뀌면 기준선 재현이 불가능합니다(측정 2026-07 기준).
6. **로컬 서빙 처리량 미측정.** MLX 변환은 검증했으나, 측정 시점 머신이 스왑 9.2/10GB
   상태여서 속도 수치가 무효입니다.
7. **추출 재현율 격차 미해명.** 로컬이 Gemini 대비 73%만 뽑는 것이 누락인지 Gemini의
   과잉인지 판정하지 못했습니다.

---

## 5. 참고문헌 — 인용수·스타수 검증본

**2026-07-28 확인.** Semantic Scholar Graph API(인용수), GitHub REST API(스타·최종 커밋).
스타 수는 변동하므로 확인 시점 기준입니다.

### 강한 근거 (인용수 300+)

| 논문 | 인용 | 저장소(스타) | 우리 설계에 준 것 |
|---|---|---|---|
| [Self-RAG](https://arxiv.org/abs/2310.11511) (ICLR 2024) | 2,250 | [self-rag](https://github.com/AkariAsai/self-rag) (2,412) | 근거 등급을 토큰으로 표현하는 설계 |
| [Search-R1](https://arxiv.org/abs/2503.09516) (COLM 2025) | 1,248 | [Search-R1](https://github.com/PeterGriffinJin/Search-R1) (5,163) | 검색 RL 보상 설계·마스킹 |
| [LIMA](https://arxiv.org/abs/2305.11206) (NeurIPS 2023) | 1,371 | 없음 | **"perplexity는 생성 품질과 무관"** — 체크포인트 선택 근거 |
| [Teaching Models to Express Uncertainty](https://arxiv.org/abs/2205.14334) (TMLR 2022) | 786 | [CalibratedMath](https://github.com/sylinrl/CalibratedMath) (39) | 언어화된 확신도 |
| [ALCE](https://arxiv.org/abs/2305.14627) (EMNLP 2023) | 696 | [ALCE](https://github.com/princeton-nlp/ALCE) (523) | 인용 정밀도/재현율 평가 |
| [XSTest](https://arxiv.org/abs/2308.01263) (NAACL 2024) | 457 | [xstest](https://github.com/paul-rottger/xstest) (141) | 과잉 거절 3분류(완전/부분/거부) |
| [LoRA Learns Less and Forgets Less](https://arxiv.org/abs/2405.09673) (TMLR 2024) | 358 | [lora-tradeoffs](https://github.com/danbider/lora-tradeoffs) (22) | 저랭크가 행동 보존에 유리 |
| [SelfAware](https://arxiv.org/abs/2305.18153) (ACL Findings 2023) | 287 | [SelfAware](https://github.com/yinzhangyue/SelfAware) (103) | 답할 수 없는 질문 평가 |

### 약한 근거 — 조심해서 인용할 것

| 논문 | 인용 | 상태 | 주의 |
|---|---|---|---|
| [CAG](https://arxiv.org/abs/2404.06809) (EMNLP 2024) | **22** | [CAG](https://github.com/panruotong/CAG) (22★) | 등급 조건화 설계의 주 근거인데 인용이 적음. 다만 Table 2 수치(7B에서 라벨 추가 시 0.312→0.176)는 직접 확인 가능 |
| [Alignment for Honesty](https://arxiv.org/abs/2312.07000) (NeurIPS 2024) | **72** | [repo](https://github.com/GAIR-NLP/alignment-for-honesty) (78★, 2023-12 이후 정지) | Honesty Score 정의 출처. 정의 자체는 단순해 인용 부담 적음 |
| [OCC-RAG](https://arxiv.org/abs/2606.00683) | **0** | [repo](https://github.com/optimal-cognitive-core/OCC-RAG) (43★) | **arXiv 프리프린트, 학습 코드 미공개(추론 예제만), 인용 0.** "0.6B에서 거절 6.3%→86.9%"는 흥미롭지만 **강한 선례로 쓰면 안 됨** |
| [AbstentionBench](https://arxiv.org/abs/2506.09038) (NeurIPS 2025) | 99 | [repo](https://github.com/facebookresearch/AbstentionBench) (87★) | 거절 F1 정의, 평가 3단 분리 구조 |
| [Know Your Limits](https://aclanthology.org/2025.tacl-1.26/) (TACL 2025) | 115 | [repo](https://github.com/chenjux/abstention) (9★) | 거절 지표 카탈로그 |
| [Hallucination Tax of RFT](https://arxiv.org/abs/2505.13988) (Findings EMNLP 2025) | 34 | 없음(데이터만) | 7B 순정 거절률 0.30. ~~우리 1.2B의 0.33이 소형 탓이 아님~~ — **이 비교는 철회한다.** 우리 0.33은 2/6이고 95% 신뢰구간이 [0.04, 0.78]이라 0.30과 0도 함께 포함한다. n을 늘리기 전에는 아무 정보가 없다 |

### 언어학·주석 체계

| 출처 | 우리 설계에 준 것 |
|---|---|
| 임동훈(2008) 「한국어의 서법과 양태 체계」 『한국어 의미학』 26 | 양태 = 사실성 × 실현성 두 축 — 조건부 분리의 근거 |
| [FactBank](https://catalog.ldc.upenn.edu/docs/LDC2009T23/annotationGuidelines.pdf) (Saurí & Pustejovsky 2009) | 확실성 격자. **조건문을 정규화해 삭제**(§3.3.1) — 한계도 함께 인용 |
| [Szarvas et al.](https://aclanthology.org/J12-2004/) (CL 2012) | `condition`을 독립 범주로 두는 불확실성 분류 |
| [ModaFact](https://aclanthology.org/2025.coling-main.425/) (COLING 2025) | factuality × modality 동시 주석 |
| [Decision Dialogue Acts](https://aclanthology.org/W08-0125/) (SIGdial 2008) | 회의 결정의 issue→proposal→agreement 구조 |
| ISO 24617-2 (DiAML) | `conditionality`를 별개 qualifier로 |

### 도구

| 저장소 | 스타 | 용도 |
|---|---|---|
| [huggingface/trl](https://github.com/huggingface/trl) | 18,947 | SFT·GRPO |
| [ml-explore/mlx-lm](https://github.com/ml-explore/mlx-lm) | 6,436 | 로컬 서빙(Apple) |
| [alignment-handbook](https://github.com/huggingface/alignment-handbook) | 5,651 | 하이퍼파라미터 관행 참조 |
| [allenai/open-instruct](https://github.com/allenai/open-instruct) | 3,810 | 저장소 구조 참조 |
| [bab2min/kiwipiepy](https://github.com/bab2min/kiwipiepy) | 397 | 한국어 형태소 분석 |
| [rapidfuzz/RapidFuzz](https://github.com/rapidfuzz/RapidFuzz) | 4,035 | 문자열 정렬(검토만) |

> [pytorch/torchtune](https://github.com/meta-pytorch/torchtune)(5,791★)은 하이퍼파라미터
> 참조로만 봤습니다. 2025년에 유지보수가 종료돼 `meta-pytorch/torchtune`으로 이전됐습니다.

---

## 5-1. 연산 비용 요약

전체 실측은 [연산 기록](../docs/RESEARCH-compute-log.md)에 있습니다. 요점만:

| | |
|---|---|
| 1.2B LoRA 학습 | 6~12분 (H100 1장) — **병목 아님** |
| 32B 판정 924건 | 74초 (tp=2) |
| 학생 추출 524청크 | 87초 · 같은 입력에 Gemini는 220초 |
| GRPO 검색 RL 500스텝 | 19분 |
| **체크포인트 평가** | **RL 20개에 약 1시간 — 여기가 병목** |

평가가 느린 이유는 체크포인트마다 모델을 다시 올리고 케이스를 한 건씩 생성하기 때문입니다.
vLLM LoRA 핫스왑 판([`select_chat_checkpoint_vllm.py`](../scripts/select_chat_checkpoint_vllm.py))이
베이스를 한 번만 올리고 배치로 처리합니다. transformers만으로 검증하려는 분을 위해
HF 판도 함께 둡니다.

**세션 전체에서 GPU 실사용은 1~2시간**이고 나머지는 코드 작성과 재작업이었습니다.
재작업 원인은 §2 실패 목록과 연산 기록 §5에 있습니다 — 이 규모에서는 GPU 병렬화보다
실행 전 검증이 총 소요를 더 줄입니다.

## 6. 관련 문서

- [연산 기록](../docs/RESEARCH-compute-log.md) — 실측 시간·비용·시행착오
- [연구 설계 v2](../docs/RESEARCH-plan-v2.md) — 가설·비열등성 검정 설계·검정력 계산
- [검색 RL 가설 20](../docs/RESEARCH-search-rl.md) — 문헌 근거별 가설과 검증 방법
- [등급 조건화 배경](../docs/RESEARCH-grade-conditioning.md) — SFT 필요성·축 분리 논거
- [관련 연구 서베이](../docs/RESEARCH-related-work.md) — 6개 영역 선행 연구

## 7. 라이선스

코드는 MIT. **모델 어댑터는 EXAONE AI Model License 1.2 - NC를 상속하므로 비상업 용도로만
사용할 수 있습니다.** 데이터는 공개하지 않습니다.
