# DEPLOY — Cloud Run 배포 런북

> **상태: 미실행 런북.** gcloud 재인증(`gcloud auth login`)이 필요해 이 문서의 명령은 아직 실행·검증되지 않았다.
> 처음 실행하는 사람이 막히는 지점을 이 문서에 그대로 추기할 것.
> CI 자동 배포는 이 런북이 수동으로 1회 성공한 뒤에만 Actions에 추가한다 (검증 안 된 배포 자동화 금지).

## 플랫폼 결정: Cloud Run (근거)

- M7(Meet 수집)이 어차피 GCP Pub/Sub + 서비스 계정을 요구한다 — 인프라·시크릿·과금을 GCP 한 곳으로.
- 컨테이너 2개(Java 코어, editing-plane)를 서비스 2개로 그대로 올릴 수 있고 WebSocket을 지원한다.
- 대안 비교: Vercel 부적합(장수 WebSocket/JVM), Render·Fly 가능하나 Pub/Sub·Secret Manager와 이원화됨.

## 목표 구성

| 구성요소 | GCP 서비스 | 비고 |
|---|---|---|
| Java 코어 (8080) | Cloud Run `mydoc-core` | **min=max=1 고정** — @Scheduled·Socket Mode·Pub/Sub pull이 오토스케일과 양립 불가 (DEPLOY-PLAN.md B2/B3) |
| editing-plane (8081) | Cloud Run `mydoc-editing` | **min=max=1 고정** (아래 함정 1) |
| Postgres + pgvector | Cloud SQL (PostgreSQL 16) | `vector` 확장 지원됨 |
| Redis | Memorystore (Basic 1GB) | editing-plane 멀티 인스턴스 전까지는 선택 |
| 시크릿 | Secret Manager | .env의 키 전부 |
| 이미지 | Artifact Registry | 리포 1개 `mydoc` |

## 함정 (실행 전에 읽기)

1. **editing-plane은 인스턴스 1개로 고정한다.** Cloud Run은 기본으로 여러 인스턴스를 띄우는데,
   Hocuspocus 세션이 인스턴스 간 공유되려면 Redis 확장이 제대로 붙어야 한다. MVP는
   `--min-instances=1 --max-instances=1`. (Redis 확장 코드는 이미 있으므로 확장은 이후 과제.)
2. **WebSocket 타임아웃**: Cloud Run 요청 타임아웃이 WS 연결에도 적용된다. `--timeout=3600` +
   클라이언트 재연결(Hocuspocus provider 기본 재연결 있음)로 대응.
3. **Cloud SQL 접속**: Cloud Run에서는 유닉스 소켓(`/cloudsql/…`) 또는 커넥터. JDBC는
   `jdbc:postgresql:///mydoc?cloudSqlInstance={CONN}&socketFactory=com.google.cloud.sql.postgres.SocketFactory`
   방식이 표준인데 **의존성 추가가 필요**하다(`com.google.cloud.sql:postgres-socket-factory`).
   의존성을 늘리기 싫으면 Cloud SQL에 사설 IP를 주고 VPC 커넥터로 직결하는 쪽이 코드 무변경.
4. **Memorystore는 VPC 커넥터 필수** — Cloud Run에서 사설 IP로만 접근된다.
5. **M7 Pub/Sub pull**은 Cloud Run의 "요청 없으면 CPU 정지" 모델과 충돌한다.
   `--no-cpu-throttling` + min-instances=1로 상시 CPU를 줘야 pull 루프가 산다. (core 서비스에 적용)
6. **X-Member-Id 인증 상태로는 공개 URL 금지** (RISKS.md C-2). `--ingress=internal` +
   IAP/사내 프록시 뒤에 두거나, 최소한 `--no-allow-unauthenticated`로 IAM 인증을 강제할 것.

## 절차 (순서대로)

```bash
# 0. 인증/프로젝트
gcloud auth login && gcloud auth application-default login
gcloud config set project {PROJECT_ID}
gcloud services enable run.googleapis.com sqladmin.googleapis.com redis.googleapis.com \
  artifactregistry.googleapis.com secretmanager.googleapis.com vpcaccess.googleapis.com \
  meet.googleapis.com workspaceevents.googleapis.com pubsub.googleapis.com drive.googleapis.com

# 1. 이미지
gcloud artifacts repositories create mydoc --repository-format=docker --location=asia-northeast3
gcloud auth configure-docker asia-northeast3-docker.pkg.dev
REG=asia-northeast3-docker.pkg.dev/{PROJECT_ID}/mydoc
docker build -t $REG/core:$(git rev-parse --short HEAD) .
docker build -t $REG/editing:$(git rev-parse --short HEAD) editing-plane/
docker push $REG/core:$(git rev-parse --short HEAD)
docker push $REG/editing:$(git rev-parse --short HEAD)

# 2. DB (사설 IP 경로 — 코드 무변경)
gcloud sql instances create mydoc-pg --database-version=POSTGRES_16 --tier=db-g1-small \
  --region=asia-northeast3 --network=default --no-assign-ip
gcloud sql databases create mydoc --instance=mydoc-pg
gcloud sql users create mydoc --instance=mydoc-pg --password={DB_PASSWORD}
# vector 확장은 DB 접속 후: CREATE EXTENSION vector;

# 3. Redis + VPC 커넥터
gcloud redis instances create mydoc-redis --size=1 --region=asia-northeast3
gcloud compute networks vpc-access connectors create mydoc-conn \
  --region=asia-northeast3 --range=10.8.0.0/28

# 4. 시크릿 (.env 항목 전부 — 값은 Secret Manager에만)
for s in DB_PASSWORD GEMINI_API_KEY INTERNAL_SERVICE_TOKEN COLLAB_JWT_SECRET \
         SLACK_BOT_TOKEN SLACK_APP_TOKEN; do
  gcloud secrets create $s --replication-policy=automatic || true
  # printf '%s' '{value}' | gcloud secrets versions add $s --data-file=-
done

# 5. 서비스 배포 (사설 통신: core <-> editing 은 내부 URL 사용)
gcloud run deploy mydoc-editing --image=$REG/editing:{TAG} --region=asia-northeast3 \
  --vpc-connector=mydoc-conn --min-instances=1 --max-instances=1 --timeout=3600 \
  --no-allow-unauthenticated --port=8081 \
  --set-env-vars=DATABASE_URL={PG_URL},REDIS_URL={REDIS_URL},JAVA_BASE_URL={CORE_URL} \
  --set-secrets=INTERNAL_SERVICE_TOKEN=INTERNAL_SERVICE_TOKEN:latest,COLLAB_JWT_SECRET=COLLAB_JWT_SECRET:latest

gcloud run deploy mydoc-core --image=$REG/core:{TAG} --region=asia-northeast3 \
  --vpc-connector=mydoc-conn --min-instances=1 --no-cpu-throttling --timeout=300 \
  --no-allow-unauthenticated --port=8080 \
  --set-env-vars=DB_URL={JDBC_URL},EDITING_PLANE_URL={EDITING_URL},MYDOC_BASE_URL={CORE_URL} \
  --set-secrets=DB_PASSWORD=DB_PASSWORD:latest,GEMINI_API_KEY=GEMINI_API_KEY:latest,INTERNAL_SERVICE_TOKEN=INTERNAL_SERVICE_TOKEN:latest,COLLAB_JWT_SECRET=COLLAB_JWT_SECRET:latest

# 6. 스모크: /actuator/health 200, /swagger-ui 접근, 문서 CRUD curl (README의 로컬 절차와 동일)
```

M7(Pub/Sub 구독·DWD)은 11-ingest-meet.md의 "GCP / Workspace 설정" 절을 이어서 실행한다.

## 하지 않은 것 (의도적)

- CI 자동 배포 스크립트 — 위 런북 1회 수동 성공 전에는 만들지 않는다.
- Cloud SQL socket-factory 의존성 — 사설 IP 경로로 코드 무변경을 우선했다.
- 커스텀 도메인/HTTPS/LB — 사내 파일럿 범위 밖.
