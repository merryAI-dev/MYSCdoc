# ROADMAP — Codex 인수인계

M0~M6 백엔드 + 정적 웹 UI까지 완료된 상태에서 남은 개발을 4단계로 정리한다.
작성 시점 상태: 커밋 `1fad5af`, 전체 `./gradlew clean build` green, 로컬 네이티브(Postgres 17 + pgvector + Redis) 실행 확인.

## 작업 규칙 (기존 컨벤션 유지)

- 스펙 원문은 `/Users/boram/mydoc-spec/*.md`. 스펙과 충돌하거나 우회가 필요하면 코드로 조용히 해결하지 말고 `QUESTIONS.md`에 P0로 기록.
- 커밋 프리픽스: 마일스톤 단위 `M4: ...` 또는 기능 설명. 모든 변경은 Testcontainers acceptance 테스트(`M0AcceptanceTest`~`M6AcceptanceTest`)로 잠근다.
- 검증 없이 완료 주장 금지: targeted test → `clean build` → (해당 시) 실제 bootRun/curl 순서.
- `.env`는 절대 커밋 금지 (gitignored). 실키는 `.env`에만.

## 함정 목록 (건드리기 전에 알아야 함)

- `chunk.embedding`은 `vector(1536)` 고정 — Gemini 임베딩은 `mydoc.gemini.embedding-dimensions=1536`으로 차원을 핀. 모델 바꾸면 차원 확인 필수.
- `replaceBlocks`의 DRAFT→ACTIVE 전이에서 `SLACK_INGEST`/`AI_SUGGESTION` cause는 DRAFT 유지 (스펙 충돌의 승인된 해석, `DocumentService.java:128`).
- `Document`에 `@Version` 있음 — 상태 전이 코드 추가 시 optimistic lock 충돌(409) 고려.
- Spring AI를 쓰지 않음: 공식 `spring-ai-starter-model-google-genai`는 무거운 전이 의존성(gRPC/protobuf)으로 기동을 10초→136초로 만들어 제거함. Gemini 호출은 `ai/GoogleGenAiChatClient.java`, `ai/GoogleGenAiEmbeddingAdapter.java`의 RestClient 직접 구현. 다시 SDK로 돌아가지 말 것.
- UI 블록 매핑(`static/index.html`의 `blockType()`)은 `editing-plane/server.js`의 매핑과 동일해야 함. 한쪽만 고치면 왕복 저장이 데이터를 망가뜨림.
- 두 Gradle 테스트를 병렬 실행하면 같은 `build/` 디렉터리의 XML 결과 파일이 충돌해 가짜 실패가 남 — 순차 실행할 것.

---

## Phase 1 — 안정화 (실키 검증 + 운영 준비)

목표: fake로만 검증된 경로를 실제 환경에서 닫는다.

1. **Gemini 실키 검증 (M5 교정 → M2 검색 순)**
   - `POST /api/documents/{id}/corrections`를 실키로 호출. Gemini는 JSON을 ```json 코드펜스로 감싸는 습성이 있어 `CorrectionService.parse()`가 깨질 가능성 높음 — 프롬프트에 "코드펜스 금지" 명시 또는 파서에서 펜스 스트립.
   - `JsonThreadSummaryPort`(Slack 요약)도 동일 리스크.
   - M2: 한국어 쿼리로 시맨틱 랭킹 품질 확인 (`gemini-embedding-2`, taskType 미지정 상태 — 검색용은 `RETRIEVAL_QUERY`/`RETRIEVAL_DOCUMENT` 구분이 품질에 영향, 필요 시 `GoogleGenAiEmbeddingAdapter`에 추가).
2. **Slack 실워크스페이스 검증 (M4)**: 봇 토큰 발급 후 bookmark 이모지 → 스레드 수집 → 문서 생성 → 답글 흐름 end-to-end. `QUESTIONS.md`의 남은 P0 3개가 전부 이 항목과 1번임.
3. **`ChunkingService.rechunk`의 `embedAll`을 트랜잭션 밖으로**: 코드에 `ponytail:` 주석으로 명시된 업그레이드. 임베딩 계산 먼저 → 짧은 쓰기 트랜잭션(락+delete+insert). 실키가 켜지면 커넥션 풀 고갈이 현실화됨.
4. **자동저장 리비전 스쿼시**: UI가 3초 디바운스 `PUT blocks` → 저장마다 Revision 생성. 같은 편집자의 짧은 간격(예: 10분) 연속 SNAPSHOT/MANUAL 리비전은 최신으로 갱신(squash)하는 서버측 처리.
5. **Slack 중복 수집 직렬화**: `SlackIngestService.onReactionAdded`에서 summarize 전에 `pg_advisory_xact_lock(hashtext(channelId || ':' || threadTs))` — 이전에 로그 선삽입 방식은 FK/aborted-tx 문제로 되돌렸음(QUESTIONS.md 기록 참조). advisory lock이 정답.
6. **운영 기초**: README.md(로컬 실행: 네이티브/도커 두 경로), docker-compose에 앱+editing-plane 서비스 추가, 구조 요약.

## Phase 2 — 핵심 경험 완성 (실사용 가능한 제품)

1. **실시간 공동편집 UI 연결 (최대 항목)**: editing-plane(Hocuspocus)은 완성·테스트됨. UI(`static/index.html`)는 현재 REST 저장(last-write-wins). `POST /api/internal/collab-tokens`로 JWT 받아 `@hocuspocus/provider` + `@tiptap/y-tiptap`으로 WebSocket 편집 전환. 편집 평면 다운 시 REST 읽기전용 폴백(스펙 05-editing-plane.md:103에 설계 있음).
2. **진짜 로그인**: X-Member-Id UUID 수동 입력 → Google OAuth(MYSC 워크스페이스) 또는 Slack OAuth. 세션 쿠키/JWT. `HeaderAuthFilter` 교체 지점.
3. **하이브리드 검색 UI**: 현재 UI는 키워드만 노출. 임베딩 켜지면 keyword+vector 결과 통합 표시.
4. **에디터 UX**: 슬래시 커맨드, 블록 드래그 핸들, 이미지 업로드(현재 IMAGE는 URL만 — 파일 저장소 결정 필요), 마크다운 입력 단축키.
5. **CDN 탈피**: TipTap을 esm.sh CDN에서 로컬 번들로 (사내망/오프라인 대응). 이 시점에 단일 HTML → 빌드 있는 프론트(Next.js, 스펙 10-milestones.md:98) 전환 여부 결정.

## Phase 3 — 조직 도입 (권한 + 워크플로우)

1. **Space 멤버십/권한 모델 — 스펙 결정 선행 필요**: 현 스펙은 space 멤버십 개념이 없고 모든 멤버가 모든 문서 편집 가능(verify/archive/owner변경만 owner/ADMIN). 조직 확대 시 `space_member` 테이블 + 역할(VIEWER/EDITOR/OWNER) + 전 API 권한 체크. 2026-07-04 보안 감사에서 "by-design"으로 기각한 IDOR류 항목들이 이 단계의 요구사항 목록이 됨 (QUESTIONS.md 감사 기록 참조).
2. **Slack 워크플로우 심화**: stale DM에 인터랙티브 [검증] 버튼(Slack Block Kit + 액션 엔드포인트), mydoc 링크 unfurl, 채널→스페이스 매핑 설정(현재 `SLACK_DEFAULT_SPACE_SLUG` 하나뿐).
3. **검증 운영**: 문서별 ttlDays 편집 UI, 검증 위임(owner 아닌 지정 검증자), 스페이스별 신선도 현황.
4. **MCP 확장**: `update_document` 툴, 스페이스 필터 인자, 사내 Claude 배포 연동 가이드.

## Phase 4 — 지능화 / 확장

1. **AI 심화**: 문서 열람 시 관련 문서 추천(임베딩 인프라 재사용), 중복·모순 문서 감지 배치, 주간 지식 다이제스트 DM.
2. **수집 소스 확장**: Google Docs/Drive 가져오기, 회의록(음성 전사) 파이프라인, 이메일 스레드.
3. **지식 공백 분석**: 검색 실패(결과 0) 쿼리 로깅 → "없는 문서" 신호 대시보드, 문서 조회 통계.
4. **스케일**: chunk ivfflat 인덱스 재빌드 전략(현재 "little data" 상태로 생성됨 — 데이터 축적 후 lists 파라미터 튜닝), 검색 캐싱, 멀티 워크스페이스/테넌시.

---

우선순위 원칙: Phase 1은 순서대로(1→2번이 P0 해소), Phase 2의 1번(공동편집)은 독립적이라 Phase 1과 병행 가능. Phase 3의 1번은 코드보다 스펙 합의가 먼저다.
