# 개발 재개 점검 — 2026-10-21

기준: main `039ccea`, 작업 브랜치 `codex/api-only-stabilization`.
사용자 결정: 로컬 모델을 폐기하고 API 기반으로 개발한다.

## 이번 변경

- 로컬 MLX 챗 클라이언트와 로컬 모델 설정을 제거했다. 과거 학습 실험 파일은 기록으로 보존한다.
- 선택 기능인 검색어 재작성을 기존 Gemini API 클라이언트로 전환했다. 실패·형식 오류·검색 결과 없음은 원 질문으로 폴백한다.
- Gemini 키가 없는 환경에서도 챗 서비스 의존성을 구성할 수 있도록 수정했다. 질문에는 설정 안내를 반환한다.
- Gemini의 빈 후보·빈 본문을 외부 서비스 오류로 처리한다. 여러 텍스트 파트를 합치고 thought 파트는 답변에서 제외한다.
- 화면의 RL 검색 표현을 AI 검색으로 바꾸고 추가 API 호출을 안내한다. 모델명은 환경변수로 설정할 수 있다.

## 기존 문서와 코드의 차이

ROADMAP/RISKS는 초기 상태를 포함하므로 모든 항목이 현재 미구현을 뜻하지 않는다.

- `ChunkingService`: DRAFT 제외, 동일 본문 재사용, 트랜잭션 밖 임베딩 계산이 이미 구현돼 있다.
- `DocumentChangedListener`: 30초 디바운스가 이미 구현돼 있다.
- `DocumentService`: 10분 리비전 스쿼시가 이미 구현돼 있다.

## 남은 작업

| 우선순위 | 작업 | 현재 근거 / 완료 조건 |
|---|---|---|
| P0 | API 실응답·DB 통합 검증 | 로컬 Docker가 없어 Testcontainers 전체 검증이 불가능하다. Docker 환경에서 clean build, 별도 개발용 API 키로 검색·교정·요약·임베딩 흐름을 확인해야 한다. |
| P1 | 멀티홉 검색 통합 | PR #1은 열려 있으며 이전 CI는 성공했다. 이번 브랜치에도 챗 서비스 변경이 있으므로 병합 전 API 전환과 함께 회귀 검증해야 한다. |
| P1 | 실제 로그인과 권한 | `HeaderAuthFilter`는 여전히 X-Member-Id를 사용한다. OAuth와 스페이스별 권한은 사내망 밖 배포 전에 필요하다. |
| P2 | 공동편집 UI 연결 | editing-plane은 존재하지만 index.html에 Hocuspocus provider 연결이 없다. 토큰 발급→연결→동시 수정→재접속 흐름 검증이 필요하다. |
| P2 | API 재작성 품질·지연 평가 | 로컬 RL 실험 수치는 API 모델 성능 근거로 사용할 수 없다. 기능은 기본 OFF로 유지하며 실제 질문으로 원문 검색과 비교해야 한다. |

## 검증

- 타깃 회귀 테스트 통과: 키 없는 컨텍스트, API 클라이언트 선택, 재작성 실패·형식 오류·검색 무결과 폴백, Gemini 빈 응답·여러 파트 처리.
- service/ai/ingest 테스트 38개 실행: 실패 0, 스킵 1(기존 테스트).
- 초기 점검 시 외부 API 호출을 수행하지 않았다. 아래 Claude 전환에서 실제 호출을 추가 검증했으며, 브라우저/DB end-to-end는 미검증이다.
- `clean build`: 컴파일·JAR 생성 성공, Docker 환경 부재로 acceptance 테스트 13개 초기화 실패(총 52개, 스킵 2). 전체 빌드 성공으로 간주하지 않는다.
- editing-plane 두 JavaScript 파일의 구문 검사와 `git diff --check` 통과.


## 2026-10-21 Claude API 전환 추가 검증

기본 생성 제공자를 Anthropic으로 전환했다. `ANTHROPIC_API_KEY`를 환경변수로 주입하며 `LLM_PROVIDER=anthropic`, `ANTHROPIC_CHAT_MODEL=claude-haiku-5-5`가 기본이다. 답변·교정·요약·구조화 추출·검색어 재작성에 동일 어댑터를 사용한다. Gemini 생성은 `LLM_PROVIDER=gemini`일 때만 활성화한다. 추출 trace 모델명도 제공자에 맞게 기록한다.

초기 전환 시에는 클립보드에서 실행 환경으로만 키를 전달했다. 이후 사용자 요청으로 로컬 `.env` 저장을 추가했다(아래 최신 현황 참조). Java 21 및 기존 DB 설정은 필요하다. 운영 환경 변경·배포는 수행하지 않았다.

검증: AnthropicChatClientTest, ApiOnlyChatTest, GoogleGenAiChatClientTest, KnowledgeChatServiceTest 총 13개 통과. 명시적으로 RUN_CLAUDE_LIVE_TEST=true를 설정하여 실제 텍스트/API_OK 및 강제 tool 기반 구조화 출력 2회도 통과했다. 기본 테스트는 비용 발생 라이브 테스트를 건너뛴다. max_tokens 중단·비어 있는 출력·잘못된 출력 종류를 성공으로 취급하지 않는다. 구조화 출력의 업무 의미·필드 검증은 기존 추출 계층이 계속 담당한다.

별도 해양수산 진단: 20 질문/20 후보를 Claude로 정렬하여 Hit@1 95%, MRR .975, NDCG@5 .943. API 20회 모두 유효하며 입력 39,504/출력 3,418토큰, 병렬 실행 약 14.8초. 초기 토큰 제한 실패 1회는 별도 보존했다. 독립 사람 라벨이 아니며 기억 갱신·그래프 압축의 효과를 입증한 실험은 아니다.

이 단계에서는 Gemini 임베딩 설정을 유지했으나, 이후 Claude만 사용하라는 요청에 따라 제거했다. 최신 검색 구성은 아래 Claude 전용 전환 내용을 따른다.

API 규격 참고: https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools 및 https://platform.claude.com/docs/en/api/errors (기본 Haiku 모델에서 thinking 비활성화 및 강제 tool 지원 확인).


## Claude 단독 사용 후속 변경

위의 “임베딩은 Gemini 설정을 유지” 방침을 폐기했다. GoogleGenAiEmbeddingAdapter와 실행 설정의 embedding-model/dimensions를 제거했다. 벡터 DB 열과 추상 인터페이스는 기존 자료 및 회귀 테스트 호환을 위해 보존하되 기본 런타임에서 EmbeddingPort 빈은 생성되지 않는다.

임베딩 없이도 텍스트 색인을 유지하고 PostgreSQL 키워드 후보 최대 20개를 Claude가 재정렬한다. API 미설정·실패·누락/중복/범위 밖 ID·JSON 오류는 원 순서로 복귀한다. 후보는 기존 space/status 필터를 거친 결과만 전달하며 모델은 새 문서를 추가할 수 없다. 원문이나 키를 로그에 쓰지 않고 outcome/candidates/elapsed_ms/error_type을 기록한다. 키워드 후보의 한국어 recall, 운영 DB 재색인 및 브라우저 end-to-end는 별도 검증 대상이다.


## 해양수산 원본 앱 체험 환경

별도 Python 실험 UI 대신 동일 Spring Boot 앱의 index.html/knowledge.html을 127.0.0.1:8086에서 실행한다. pgvector 컨테이너 myscdoc-marine-pg, 127.0.0.1:55436/myscdoc_marine을 별도로 사용한다. 기존 8080 앱과 DB는 건드리지 않는다.

marine-local 프로필에서 MarineLocalBootstrap이 corpus.json의 비어 있지 않은 64개 문서를 DocumentService/ChunkingService로 저장한다. source_id 및 해시를 marine_local_import에 기록하며 본문은 IMPORT 출처를 유지한다. 기존 DecisionExtractPort → KnowledgeTripleWriter를 호출해 그래프를 만들고, 기존 KnowledgeChatService로 질의한다. graph_status는 pending/done/empty/failed로 조회 가능하다. empty는 원문이 없어졌다는 뜻이 아니라 기존 추출기가 그래프 추출 결과를 반환하지 않았다는 뜻이다. 자동 추출은 정답 라벨이 아니다.

Java 21 환경에서 키를 복사한 뒤 python3 scripts/run_marine_local.py로 재실행한다. 컨테이너가 정지돼 있으면 docker start myscdoc-marine-pg로 먼저 시작한다. 2026-10-21 사용자 요청으로 키를 Git 제외된 로컬 `.env`(권한 0600)에 저장했다. 실행기는 환경 변수 → `.env` → 클립보드 순으로 읽는다. 로컬 테스트 회원 ID: 762538ab-40e5-47fc-a3a2-83ba0d729001.

이 환경은 기존 앱의 기준선이다. 문맥별 가중치 학습·성공실패 기억 갱신·독립 홀드아웃 검증은 아직 앱에 연결하지 않았다. 텍스트 임베딩은 사용하지 않고, 기존 그래프의 TransE 학습 기능도 이번 체험에서는 실행하지 않는다.

실행 확인: 문서 64개 ACTIVE, 블록 16,560개, 텍스트 청크 709개, 벡터 보유 청크 0개. 그래프 추출 64개 처리 완료(45개 결과 있음, 19개 empty, 실패 0), 관계 833개. 문서 검색 DoubleT 쿼리에서 Claude 재정렬 16후보 성공, HTTP 응답 약 3.1초. 기존 그래프 채팅 UI에서 실제 답변과 원문 문서 열기를 확인했다. BM25+1홉은 공통 키워드로 다른 기업의 근거가 혼입되는 현상이 관측되어 품질 개선이 필요하다.


## 문맥 분류 및 비교 실험 (2026-10-21)

기존 주체·대상·값·근거·조건·제약된다 등의 predicate와 원본 트리플은 보존한다. knowledge_context에 성장(학습·정량 인사이트), 성과(관계·파트너 진척), 성숙(기업 피드백) 다중 분류를 별도로 저장한다. 예정/명시/추론/불명 상태와 결정 상태를 분리하며 모든 자동 분류는 미검토로 표시한다. 관계를 분류명으로 치환하지 않는다.

CONTEXT는 기존 후보에서 Claude가 근거를 선택하고 CONTEXT_EXPANDED는 최대 60개 후보를 검토한다. 후보·선택 UUID, 성공/폴백 사유 및 시간을 응답에 기록한다. 이는 문맥 선택 실험이며 임베딩 생성, 거리 학습, 성공·실패 기억 학습을 구현한 것은 아니다. 같은 답변 프롬프트로 BM25와 비교하며 원본 decision 종류를 곧바로 확정 사실로 설명하지 않도록 수정했다. 변경 전 응답은 별도 보존한다.

원본 질문·답변·출처·실행 시간은 저장소 상위 experiments/marine-app-comparison에 보관한다. 6문항 단회 실행과 작성자 임시 기준이므로 일반화 성능이나 독립 검증 정확도를 주장할 수 없다.

검증 결과: 833개 관계 자동 분류 완료(성장 252, 성과 348, 성숙 235, 미분류 101; 복수 라벨). 6개 질문 × BM25/CONTEXT/CONTEXT_EXPANDED의 실제 답변·출처·시간을 저장했다. 포장 조건 회수 및 항만 진출 질문의 기업 출처 정합성은 개선했으나 첫 컨설팅 계약 질문의 타 기업 근거 혼입은 남았다. 독립 검증 정확도 향상으로 해석하지 않는다. 관련 회귀 테스트 11개 통과, git diff --check 통과. 상세 비교: 상위 experiments/marine-app-comparison/report.html.
