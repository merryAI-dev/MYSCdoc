# mydoc

사내 지식 플랫폼. Spring Boot API, Postgres/pgvector, Redis, 정적 웹 UI, Hocuspocus 기반 editing-plane으로 구성된다.

## MYSC Muse · 메리 에이전트 함께 실행

기존 MYSCdoc 채팅에 nanoMuse 에이전트와 메리 아바타를 연결한 로컬 실행 환경이 포함되어 있다. 자신의 Claude API 키를 사용하며, MYSCdoc 검색을 추가 도구로 제공한다. 에이전트·기억·목표·브라우저·화면 제어와 승인 기능을 유지한다.

**Fork 사용자:** [설치부터 실행까지](integrations/nanomuse/README.md#fork--clone-후-빠른-시작)를 따른다. `scripts/setup_muse.py`, `.env.muse.example`, `compose.muse.yml`, `scripts/run_muse_app.py`가 공유용 진입점이다. 사내 코퍼스·API 키·사용자 세션은 포함하지 않는다. nanoMuse 원본과 수정본의 GPL-3.0-or-later 라이선스 및 출처는 [vendor](integrations/nanomuse/vendor/)에 보존되어 있다.

## Native Run

로컬 Postgres 17(pgvector)와 Redis가 떠 있다는 전제의 빠른 개발 경로다.

```bash
cd MYSCdoc
set -a
. ./.env
set +a
./gradlew --no-daemon bootRun
```

editing-plane은 별도 터미널에서 실행한다.

```bash
cd MYSCdoc/editing-plane
npm start
```

기본 포트:

- Java API/static UI: `http://localhost:8080`
- editing-plane WebSocket: `http://localhost:8081`
- Postgres: `localhost:5432`
- Redis: `localhost:6379`

## Docker Compose Run

Docker 경로는 Postgres, Redis, Java app, editing-plane을 같이 띄운다.

```bash
cd MYSCdoc
docker compose up app editing-plane
```

Compose는 `.env`의 값을 변수 치환에 사용한다. `.env`는 커밋하지 않는다.

## Structure

- `src/main/java/com/mysc/mydoc`: Spring Boot API, 도메인, 수집 파이프라인, MCP
- `src/main/resources/static/index.html`: 현재 정적 웹 UI
- `src/main/resources/db/migration`: Flyway schema
- `editing-plane`: Hocuspocus/Yjs 협업 편집 서버
- `RISKS.md`, `ROADMAP.md`, `QUESTIONS.md`: 개발 전 필독 인수인계 문서

## API 기반 AI 설정

기본 답변·교정·요약·검색어 재작성은 Claude API를 사용한다. 로컬 MLX 챗봇과 RL 검색어 재작성 서버는 제거했다.
`research/`와 과거 연구 문서는 기록용이며 앱 실행에 필요하지 않다.

1. `ANTHROPIC_API_KEY`를 실행 환경에 주입한다. `python3 scripts/run_claude_local.py`는 실행 환경 → 로컬 `.env` → macOS 클립보드 순서로 키를 읽는다. `.env`는 Git에서 제외하며 파일 권한을 0600으로 유지한다.
2. 기본 모델은 `claude-haiku-5-5`다. 이 어댑터는 thinking 비활성화와 강제 tool 출력을 지원하는 모델이 필요하다. Gemini 임베딩 어댑터는 제거했다. 문서 검색은 키워드 후보 최대 20개 → Claude 관련성 재정렬 → 문서별 중복 제거 순서로 실행한다. Claude 키가 없거나 재정렬 실패 시 키워드 순서를 사용한다. Gemini 생성은 비교 실험용으로 명시적인 `LLM_PROVIDER=gemini` 설정에서만 사용 가능하다.
3. 위 Native Run 또는 Docker Compose Run 절차로 실행한다. Native Run은 Java 21이 필요하다.

API 키가 없으면 문서 앱의 기동을 막지 않으며 챗봇은 설정 안내를 반환한다.
지식그래프의 ‘AI 검색’은 선택 기능으로 검색어 재작성을 위해 API를 추가 호출한다.
재작성 실패·잘못된 형식·검색 결과 없음이면 원 질문으로 검색한다.

개발 재개 점검 결과와 남은 작업은 `docs/DEVELOPMENT-STATUS.md`를 참고한다.

### Claude만 사용하는 검색 경로

문서 텍스트 청크는 임베딩 없이도 생성·갱신한다. 기존 벡터 열은 보존하지만 기본 실행에서 벡터 API를 호출하지 않는다. 재정렬은 후보 ID 전체의 유효한 순열만 받아들이며, 원문·키 없이 성공/폴백/후보 수/소요 시간을 로그에 기록한다. 검색 응답의 score는 순위 기반 값이며 확률이나 코사인 유사도가 아니다. Claude 호출을 기다리는 동안 SearchService가 DB 트랜잭션을 유지하지 않는다.

현재 후보 검색은 PostgreSQL simple 전문검색이다. 한국어 형태소·동의어와 문장형 질문의 후보 누락은 Claude 재정렬만으로 해결하지 못한다. 지식그래프 채팅은 기존 그래프 검색 → Claude 답변 경로를 유지한다. 이번 문서 재정렬은 그래프 채팅 재정렬과 별도다.

이미 청크가 있는 문서는 즉시 후보 검색 대상이 된다. 과거 임베딩 미설정으로 청크가 생성되지 않았던 문서는 수정·활성화 이벤트 또는 기존 rechunk 작업으로 색인을 생성해야 한다. 이번 작업은 운영 DB에 접근하거나 전체 문서를 소급 재색인하지 않았다.
