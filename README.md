# mydoc

사내 지식 플랫폼. Spring Boot API, Postgres/pgvector, Redis, 정적 웹 UI, Hocuspocus 기반 editing-plane으로 구성된다.

## Native Run

로컬 Postgres 17(pgvector)와 Redis가 떠 있다는 전제의 빠른 개발 경로다.

```bash
cd /Users/boram/mydoc
set -a
. ./.env
set +a
./gradlew --no-daemon bootRun
```

editing-plane은 별도 터미널에서 실행한다.

```bash
cd /Users/boram/mydoc/editing-plane
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
cd /Users/boram/mydoc
docker compose up app editing-plane
```

Compose는 `.env`의 값을 변수 치환에 사용한다. `.env`는 커밋하지 않는다.

## Structure

- `src/main/java/com/mysc/mydoc`: Spring Boot API, 도메인, 수집 파이프라인, MCP
- `src/main/resources/static/index.html`: 현재 정적 웹 UI
- `src/main/resources/db/migration`: Flyway schema
- `editing-plane`: Hocuspocus/Yjs 협업 편집 서버
- `RISKS.md`, `ROADMAP.md`, `QUESTIONS.md`: 개발 전 필독 인수인계 문서
