# DEPLOY-FLY — Fly.io 배포 가이드 (파일럿용, 월 $5~15)

> GCP 대신 Fly.io로 배포한다. 이유: core가 상시 켜져 있어야 해서(Slack Socket + 스케줄 잡)
> Cloud Run의 scale-to-zero를 못 쓰는데, 그럴 거면 Cloud SQL 비용($25~35/mo)이 아깝다.
> Fly는 앱이 이미 Dockerfile이라 `fly launch`로 바로 올라가고, Managed Postgres가 pgvector를 지원한다.
> `DEPLOY-PLAN.md`의 게이트 중 **B2·B3·B4는 단일 머신이라 자동 해결**, 남는 건 B1(공개 URL)·B5(pgvector)·B7(CDN).
> 작성일 2026-07-08.

## 배포 범위

| 올리는 것 | 안 올리는 것 |
|---|---|
| mydoc core (Java, Dockerfile) — Fly App 1개, **머신 1대 고정** | **editing-plane** — 현재 UI가 안 부르는 dead service. 실시간 협업 붙일 때. |
| Fly Managed Postgres (pgvector) | Redis — editing-plane 없으면 불필요 |

## ⚠️ 함정 1 (B5) — pgvector는 클러스터 생성 시 켜야 한다

Fly Managed Postgres의 앱 계정(`fly-user`/schema_admin)은 **슈퍼유저가 아니라서 `CREATE EXTENSION`을
직접 못 한다.** 우리 `V1__init.sql:1`이 `CREATE EXTENSION IF NOT EXISTS vector`로 시작하므로,
**pgvector를 미리 활성화해두지 않으면 Flyway 첫 마이그레이션이 권한 오류로 실패**한다.

→ 해결: **클러스터를 만들 때 `--pgvector` 플래그로 확장을 미리 켠다.** 그러면 확장이 이미 존재하므로
`CREATE EXTENSION IF NOT EXISTS`가 no-op으로 통과한다(비슈퍼유저도 IF NOT EXISTS 스킵은 허용).
클러스터를 이미 만들었다면 대시보드 **Extensions 페이지에서 Vector 활성화**로 대체 가능.

---

## 절차

### 0. 사전
```bash
brew install flyctl        # 이미 있으면 skip
fly auth login
```

### 1. 앱 생성 (배포는 아직)
리포 루트에서:
```bash
fly launch --no-deploy
```
- Dockerfile 자동 감지 → `fly.toml` 생성됨.
- 지역: 한국이면 `nrt`(도쿄) 권장.
- Postgres/Redis 붙일지 물으면 **No** (아래에서 pgvector 플래그로 직접 만든다).

### 2. `fly.toml` 편집 — 단일 머신 + 헬스체크 + 포트
생성된 `fly.toml`을 아래 핵심만 맞춘다 (Dockerfile이 8080 EXPOSE):
```toml
[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = false      # 상시 가동 — Socket Mode/스케줄 잡이 죽으면 안 됨
  auto_start_machines = true
  min_machines_running = 1        # 최소 1대 상시

[[http_service.checks]]
  interval = "15s"
  timeout = "5s"
  grace_period = "60s"            # fat-jar 콜드스타트 + Flyway 여유
  method = "GET"
  path = "/actuator/health"

[[vm]]
  memory = "1gb"                  # JVM + Testcontainers 없는 런타임엔 1GB면 충분, 부족하면 2GB
  cpu_kind = "shared"
  cpus = 1
```
> `min_machines_running = 1` + `auto_stop_machines = false`가 B2·B3(잡·Socket 중복)를 막는 핵심 —
> 머신 1대만 돌면 다중 인스턴스 문제가 원천 발생 안 한다. `fly scale count 1`로 대수도 1 고정.

### 3. Managed Postgres 생성 (pgvector 켜서)
```bash
fly mpg create --name mydoc-db --region nrt --pgvector
```
- 생성 후 연결 문자열을 확인: `fly mpg attach mydoc-db` (앱에 DATABASE_URL을 붙여줌) 또는
  대시보드에서 `postgres://<user>:<pass>@<host>:<port>/<db>` 확인.
- 우리 앱은 JDBC URL + 개별 계정 변수를 쓰므로 4번에서 변환해 넣는다.

### 4. 시크릿 설정 (`fly secrets set`)
연결 문자열의 host/port/user/pass/db를 우리 앱 변수로 매핑한다:
```bash
fly secrets set \
  DB_URL="jdbc:postgresql://<host>:<port>/<db>" \
  DB_USERNAME="<user>" \
  DB_PASSWORD="<pass>" \
  DB_POOL_SIZE="5" \
  GEMINI_API_KEY="<재발급 키>" \
  INTERNAL_SERVICE_TOKEN="<32자 이상 랜덤>" \
  COLLAB_JWT_SECRET="<32바이트 이상>"
# Slack 기능 쓰면 (선택):
fly secrets set \
  SLACK_BOT_TOKEN="<xoxb->" SLACK_APP_TOKEN="<xapp->" SLACK_DEFAULT_SPACE_SLUG="<slug>"
# Tiro 쓰면 (선택):
fly secrets set TIRO_API_KEY="<재발급 키>"
```
> - `<host>`는 앱↔DB 사설 연결용 host(대개 `.flympg.net` 또는 내부 host). 외부 노출 host 아님.
> - 노출됐던 키(Gemini·Tiro·Slack)는 **재발급**해서 넣을 것(RISKS C-5).
> - `MYDOC_BASE_URL`은 6번에서 도메인 확정 후 `fly secrets set MYDOC_BASE_URL=...`로 추가.

### 5. 배포
```bash
fly deploy
fly logs        # Flyway 10 migrations applied + Started MydocApplication + (Slack이면) Socket Mode started
```
헬스체크가 초록이면 성공. `fly status`로 머신 1대 running 확인.

### 6. 보안 — 공개 URL 차단 (B1, 핵심)
mydoc의 X-Member-Id는 인증이 아니다(UUID만 알면 위장). **인터넷에 그냥 열면 안 된다.** 택 1:

- **(권장) Fly + Cloudflare Access**: `fly.toml`의 공개 도메인 앞에 Cloudflare Access(무료 티어)로
  회사 이메일 도메인만 통과. URL을 알아도 회사 계정 없인 못 들어옴.
- **(사내망) Tailscale/WireGuard**: Fly에 `flycast`(사설 IPv6)만 두고 공개 도메인 미발급.
  팀은 Tailscale로 Fly 사설망 접속. 완전 비공개.
- 도메인 확정 후 `MYDOC_BASE_URL` 시크릿 업데이트.

### 7. esm.sh(TipTap) 도달 확인 (B7)
배포 후 실제 문서를 열어 편집기가 뜨는지 확인. 사내망/프록시가 esm.sh를 막으면 편집기가 빈 화면 →
TipTap self-host가 후속 과제. (지식그래프·검색·아카이브는 CDN 의존 없음.)

---

## 운영 메모

- **재배포**: 코드 수정 후 `fly deploy`. main push마다 자동배포를 원하면 Fly의 GitHub Actions 연동
  (`FLY_API_TOKEN` 시크릿 + `flyctl deploy`) 추가 — CI 그린 뒤에만 배포되게.
- **비용**: shared-cpu-1x 1GB 머신 상시 + MPG 최소 구성 ≈ 월 $5~15. `fly dashboard`에서 사용량 확인.
  2026-02부터 리전 간 사설망 트래픽 과금되니 앱·DB를 **같은 리전(nrt)**에 두면 무료.
- **매일 18시 잡**: `SLACK_DECISION_CRON` 기본 `0 0 18 * * *`(KST). 머신 상시 가동이라 정상 발화.
- **수동 싱크**: 지식그래프 → ⚙ 설정 → "지금 동기화".
- **백업**: MPG는 자동 백업 제공(플랜별). 운영 들어가면 주기 `pg_dump`도 병행.
- **메모리 부족 시**: JVM이 1GB에서 OOM 나면 `fly.toml`의 `memory = "2gb"` 후 재배포.

## 다른 선택지
- **Railway**: GitHub 연결 + pgvector 템플릿 원클릭이라 CLI 없이 UI로만 가능(더 쉬움, 비슷한 가격).
- **GCP**: **M7 Google Meet 자동수집**(Pub/Sub 강제)을 켤 때만 필요. 그 전까진 Fly로 충분.
