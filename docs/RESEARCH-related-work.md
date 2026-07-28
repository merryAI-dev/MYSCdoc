# 관련 연구 서베이 — EXAONE vs Gemini 한국어 회의록 KG 추출

> 2026-07-27 조사. 인용 수는 Semantic Scholar/Google Scholar 근사치.
> 본 실험 설계는 [RESEARCH-exaone-vs-gemini.md](RESEARCH-exaone-vs-gemini.md) 참조.

## 포지셔닝 요약

우리 조합 — (a) 원문에 형태소 modality 게이트를 LLM 추출 **앞단**에 적용, (b) 형태소 기반
라벨 불필요 충실성 지표(syntax_grounding), (c) 사유 모델→오픈 소형 모델 증류를 한국어 회의록
KG 구축에 적용 — 을 그대로 다룬 선행 연구는 없다. 가장 가까운 이웃:

- **Hsueh & Moore 2007** — 회의 결정 검출의 시조. 단 lexical cue를 영어에서 학습했고,
  우리는 한국어 어미 형태소(-기로 하다/-도록/-겠-)를 명시적·결정적으로 쓴다.
- **Geng et al. 2023** (문법 제약 디코딩) — 출력을 문법으로 제약; 우리는 입력을 문법으로 게이트.
- **UniversalNER** (ICLR 2024) — 과업 특화 증류의 대표 결과(학생이 교사를 7-9 F1 상회).
  우리 Gemini→EXAONE 1.2B 증류의 방법론적 선례.

## 1. LLM 기반 KG 구축 / 트리플 추출

| 논문 | 연도·출처 | 인용 | 관계 |
|---|---|---|---|
| GraphRAG (From Local to Global) — Edge et al., MSR | 2024, arXiv:2404.16130 | ~1,500+ | LLM이 원문에서 KG를 만드는 표준 파이프라인. 순수 프롬프트 추출 vs 우리의 게이트 추출 대비 기준 |
| Unifying LLMs and KGs: A Roadmap — Pan et al. | 2024, IEEE TKDE | 1,000+ | 표준 프레이밍 서베이. LLM 생성 트리플의 환각 문제 → syntax_grounding 동기 |
| Revisiting RE in the era of LLMs — Wadhwa et al. | ACL 2023 | ~171+ | few-shot LLM이 지도학습 RE에 필적 + 소형 모델 증류 선례 |
| AutoSchemaKG — Bai et al. | 2025, arXiv:2505.23628 | 신규 | **사건을 1급 노드로** 다루는 KG 구축 — 우리 결정 사건 노드 reification의 최근접 유사물 |

## 2. 사유 LLM → 오픈 소형 모델 증류

| 논문 | 연도·출처 | 인용 | 관계 |
|---|---|---|---|
| UniversalNER — Zhou et al. | ICLR 2024, arXiv:2308.03279 | ~400+ | mission-focused distillation 대표작. 우리의 가장 강한 방법론 선례 |
| Distilling Step-by-Step — Hsieh et al. | Findings ACL 2023 | ~713+ | 라벨만이 아니라 근거(rationale)도 증류 — 우리 rationale 엣지 증류의 프레이밍 |
| KD of LLMs Survey — Xu et al. | 2024, arXiv:2402.13116 | ~214+ | 용어·분류 및 로컬 서빙 동기 인용 |
| GoLLIE — Sainz et al. | ICLR 2024 | ~150+ | 스키마-as-프롬프트 제약 IE. 대비점: 우리는 원문 형태소로 제약 |

## 3. 회의 요약 / 결정 추출

| 논문 | 연도·출처 | 인용 | 관계 |
|---|---|---|---|
| AMI Meeting Corpus — Carletta et al. | MLMI 2005 | ~1,500+ | 회의 데이터 기반. 한국어에 상응 코퍼스 부재 = 우리의 갭 |
| Automatic Decision Detection — Hsueh & Moore | MLMI 2007 | ~60-80 | 결정 검출 서브태스크 창시 논문. 직접 선조 |
| Modelling Decisions in Dialogue — Fernández et al. | SIGdial 2008 | ~100 | 결정을 하위 행위(issue/proposal/agreement)로 분해 — 우리 reified 스키마가 KG 차원에서 반복 |
| DA Modeling — Stolcke et al. | CL 2000 | ~1,800+ | 발화 행위 검출의 고전. 우리는 이를 한국어 결정적 표층 검사로 환원 |
| QMSum — Zhong et al. | NAACL 2021 | ~500+ | 신경망 시대 회의 벤치마크. 자유 텍스트 vs 우리의 검증 가능한 그래프 구조 |
| MeetingBank — Hu et al. | ACL 2023 | ~100+ | 실제 회의 텍스트가 올바른 테스트베드라는 근거 + 영어 한정 = 한국어 기여 부각 |

## 4. 한국어 NLP · EXAONE

| 논문 | 연도·출처 | 인용 | 관계 |
|---|---|---|---|
| EXAONE 4.0 — LG AI Research | 2025, arXiv:2507.11407 | 산업 리포트 | 우리 32B(평가)·1.2B(로컬 서빙) 모델의 1차 인용 |
| EXAONE 3.0 / 3.5 — LG AI Research | 2024 | 각 ~50-150 | 이중언어 학습 레시피 계보 |
| KMMLU — Son et al. | NAACL 2025 | ~200+ | 한국어 네이티브 평가의 당위성 — 영어 결과가 이전 안 됨 |
| KLUE — Park et al. | NeurIPS 2021 D&B | ~600+ | 한국어 NER/RE 골드 벤치마크. 회의·결정 추출 태스크 부재 확인 |
| KoNLPy — Park & Cho | HCLT 2014 | ~1,000+ | 형태소 분석 도구 표준 인용 (Nori의 mecab-ko-dic 계보) |
| Korean Speech Act Classification Review — Kim et al. | — | 낮음 | 한국어 화행 분류가 오래전부터 종결어미를 지배적 자질로 썼다는 독립 증거 (SVM ~93%) |

## 5. 라벨 불필요 충실성/환각 지표

| 논문 | 연도·출처 | 인용 | 관계 |
|---|---|---|---|
| FActScore — Min et al. | EMNLP 2023 | ~1,000+ | 원자 사실 분해→검증 템플릿. 우리는 LLM 검증자 대신 형태소 검사로 대체 |
| RAGAS — Es et al. | EACL 2024 | ~800+ | reference-free 충실성. 단 LLM 판정 필요 vs 우리는 결정적 검사 |
| SummaC — Laban et al. | TACL 2022 | ~600+ | NLI 기반 비-LLM 베이스라인 — syntax_grounding과 비교 실험 후보 |
| Hallucination Survey — Ji et al. | ACM CSUR 2023 | ~4,000+ | intrinsic/extrinsic 분류 체계 — 우리 지표의 이론적 위치 |

## 6. 제약 디코딩 / 문법 게이트

| 논문 | 연도·출처 | 인용 | 관계 |
|---|---|---|---|
| PICARD — Scholak et al. | EMNLP 2021 | ~600+ | 디코딩 시점 제약 패러다임 — 우리는 입력 시점으로 뒤집음 (상보적) |
| Grammar-Constrained Decoding — Geng et al. | EMNLP 2023 | ~150-200 | 개념적 최근접: 입력 의존 문법으로 출력 제약 vs 문법으로 입력 게이트 |
| Outlines — Willard & Louf | 2023, arXiv:2307.09702 | ~300+ | 1.2B 로컬 서빙 시 JSON 스키마 강제에 쓸 실용 도구 |
