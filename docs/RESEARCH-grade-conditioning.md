# 등급 조건화 생성 — 논문 배경

> 2026-07-28. 소형 로컬 모델이 지식그래프 노드의 '확정도'에 따라 다르게 답하게 만드는 문제.
> 실험 설계는 [RESEARCH-plan-v2.md](RESEARCH-plan-v2.md), 검색 RL은 [RESEARCH-search-rl.md](RESEARCH-search-rl.md).

## 1. 왜 프롬프트 라벨만으로는 안 되는가 — SFT가 선택이 아닌 이유

지식그래프 노드에 확정도 등급을 붙여두고 프롬프트에 표시해 주면 모델이 알아서 반영할
것이라는 가정은 **소형 모델에서 성립하지 않는다.** CAG(EMNLP 2024) Table 2:

| 모델 | 신뢰도 라벨 없음 | 라벨을 프롬프트에 추가 |
|---|---|---|
| LLaMA-2-7B | 0.312 | **0.176** (44% 악화) |
| LLaMA-2-13B | 0.370 | 0.384 |
| LLaMA-2-70B | 0.390 | 0.402 |
| ChatGPT | 0.368 | 0.402 |
| **CAG-7B (SFT 적용)** | — | **0.578** |

7B에서는 라벨을 넣는 것이 **성능을 떨어뜨린다.** 무시하는 정도가 아니라 방해가 된다는
뜻이고, 규모가 커져야 미미하게 도움이 되기 시작한다. SFT를 거치면 비로소 신호가 된다.
AuthorityBench(arXiv:2603.25092)도 같은 방향을 보고한다 — 프롬프트의 권위 라벨을
반영하는 능력이 모델 규모에 의존한다.

**우리 실측이 이를 뒷받침한다.** 교사(32B)에게 등급 라벨만 준 v1에서 확정도 반영률이
66%에 그쳤고, "왜 그렇게 판단했는지 설명하라"로 바꾼 v2에서 99%가 됐다. 32B조차 라벨만
보고는 3분의 1을 흘렸다. 1.2B에 프롬프트만으로 넘겼다면 실패했을 설계다.

→ **결론: 등급 조건화는 프롬프트 기능이 아니라 학습 대상이다.** 교사가 등급의 근거를
   설명한 답변을 만들고, 그 설명이 담긴 답변을 학생의 학습 타깃으로 쓴다(CAG 처방).

## 2. 등급은 이산이어야 한다 — 점수화는 역효과

CAG Appendix A.3은 3단계 범주를 0~9 연속 점수로 바꾸는 실험을 했고 **거의 모든
모델·데이터셋 조합에서 성능이 떨어졌다**(ChatGPT −3.1/−4.2/−1.3 EM, LLaMA-2-7B는
HotpotQA에서 −9.1). 저자 설명: 세분화된 신뢰도는 분류 정확도와 모델의 변별 능력을
동시에 더 요구한다.

→ **결론: 4단계 이산 등급을 유지하고 수치 스코어로 바꾸지 않는다.** 다만 검색 랭킹에
   쓰는 내부 weight는 별개다(그건 정렬용이지 모델 입력이 아니다).

부수 발견: CAG는 SPLADE 기반 신뢰도를 **정답 신뢰도**로 바꿨을 때 +14.4% EM을 얻었다.
등급 라벨링 정확도 자체가 성능 상한이라는 뜻이므로, 32B 판정 품질을 따로 측정해야 한다.

## 3. 우리 등급 체계의 구조적 결함 — 축이 섞여 있다

현재 4등급(확정 / 조건부 / 예정 / 논의)을 한 줄에 세웠는데, 한국어 언어학과 영어
factuality 문헌이 **독립적으로 같은 지적**을 한다.

- 임동훈(2008) 「한국어의 서법과 양태 체계」: 양태는 명제의 **사실성**과 **실현성**에 대한
  화자 태도다 — 두 축이다.
- 조건부는 `-(으)면`, `-(으)ㄹ 경우` 같은 **조건 연결어미**로, 실현성을 유예하는 연산자다.
  등급 척도 위의 한 점이 아니다.
- FactBank(Saurí & Pustejovsky 2009) 주석 지침 §3.3.1은 조건문을 두 개의 독립 명제로
  쪼개고 조건 표지를 **삭제한다** — 구조적으로 조건부를 표현할 수 없다. 또한 확신에 찬
  미래 의도를 `CT+`로 분류해 예정을 확정에 합친다.
- ISO 24617-2(DiAML)는 `certainty`와 **`conditionality`를 별개 qualifier**로 둔다.
- Szarvas et al. (CL 2012)의 불확실성 분류는 `condition`을 독립 범주로 둔다.

### 개정 — 두 축 + 플래그

| 우리 등급 | 사실성 | 실현성 | 조건 플래그 |
|---|---|---|---|
| 확정 | 높음 | 실현됨/전제됨 | — |
| 예정 | 높음 | 미실현(계획) | — |
| 논의 | 낮음 | 미실현 | — |
| ~~조건부~~ | (상위 3개 중 하나) | | **conditional=true** |

즉 "조건부 확정"과 "조건부 예정"이 구분 가능해진다. 지금 체계는 이 둘을 한 칸에 뭉갠다.

### 외부 체계와의 대조표 (논문 게재용)

| 등급 | FactBank | ModaFact modality | ISO 24617-2 |
|---|---|---|---|
| 확정 | `CT+` | `DECISION` / `COMMITMENT` | Commissive · certain |
| 예정 | `CT+`/`PR+` (future) | `WILL` / `FINAL` | Commissive |
| 논의 | `Uu` / `PS+` | `POSSIBILITY` | Suggest · uncertain |
| (조건 플래그) | 표현 불가 | — | **`conditionality`** |

우리 라벨을 그대로 쓰되 FactBank 투영을 함께 보고하면 기존 연구와 비교 가능해진다.

### 관련 선행 — 회의 도메인

Fernández et al. (SIGdial 2008) Decision Dialogue Acts가 우리 논의/확정 구분과 정확히
대응한다: `issue → resolution-proposal → resolution-restatement → agreement`. 합의(`A`)
없는 제안은 논의, `RR`+`A`는 확정이다. Purver et al.(2007)은 action item을 "공개적
수행 약속"으로 정의한다 — 예정에 해당한다.

## 4. 등급 조건화를 실제로 하는지 검증하는 법

정답률만 봐서는 모델이 등급을 반영했는지 알 수 없다. 두 가지를 쓴다.

**반사실 등급 뒤집기.** 근거 텍스트를 고정하고 등급 라벨만 확정↔논의로 뒤집어, 답변의
단정/유보 어조가 바뀌는지 본다. 안 바뀌면 모델이 내용만 읽고 등급은 무시한 것이다.
Longpre et al.(2021)의 memorization ratio와 같은 형식이다. CAG는 이를 정성 사례(Figure 6)로만
보였고 수치화하지 않았다 — **수치화가 우리 기여가 될 수 있다.**

**등급 충실도.** 생성된 답변만 보고 어느 등급을 표현했는지 맞히는 외부 분류기를 두고,
입력 등급과 일치율을 잰다(GeDi 계열 controllable generation의 표준 프로토콜).

## 5. 이 조합의 선행 연구는 없다

문헌 조사 결과 **이산 등급 라벨 + 1~3B 학생 증류**를 한 사례를 찾지 못했다. 가장 작은
신뢰도 학습 모델이 CAG-7B다. 인접 연구는 CrEst(arXiv:2506.14912, 코드 없음),
RbFT(arXiv:2501.18365) 정도다.

반대편 경고도 기록해 둔다 — arXiv:2601.01896은 관련/무관 변별을 위한 단순 SFT가
트랜스포머 깊이 한계로 비효과적이라고 주장한다. 우리 결과가 이와 충돌하면 그 논거를
직접 다뤄야 한다.

## 참고문헌

- Pan et al., *Not All Contexts Are Equal: Teaching LLMs Credibility-aware Generation*,
  EMNLP 2024. [arXiv:2404.06809](https://arxiv.org/abs/2404.06809) · [code](https://github.com/panruotong/CAG)
- Saurí & Pustejovsky, *FactBank: A Corpus Annotated with Event Factuality*, LREC 2009.
  [주석 지침](https://catalog.ldc.upenn.edu/docs/LDC2009T23/annotationGuidelines.pdf)
- 임동훈, 「한국어의 서법과 양태 체계」, 『한국어 의미학』 26(2008): 211–248.
- Szarvas et al., *Cross-Genre and Cross-Domain Detection of Semantic Uncertainty*,
  Computational Linguistics 38(2), 2012. [J12-2004](https://aclanthology.org/J12-2004/)
- Rovera et al., *ModaFact*, COLING 2025. [2025.coling-main.425](https://aclanthology.org/2025.coling-main.425/)
- Fernández et al., *Modelling and Detecting Decisions in Multi-party Dialogue*,
  SIGdial 2008. [W08-0125](https://aclanthology.org/W08-0125/)
- Longpre et al., *Entity-Based Knowledge Conflicts in Question Answering*, EMNLP 2021.
- ISO 24617-2 (DiAML). [주석 지침](https://semantic-annotation.uvt.nl/ISO24617-2_Annotation_Guidelines.pdf)
- Asai et al., *Self-RAG*, ICLR 2024. [code](https://github.com/AkariAsai/self-rag)
