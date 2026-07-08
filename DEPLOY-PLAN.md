# DEPLOY-PLAN — 첫 프로덕션 배포 플랜 (DBA · 백엔드 · 프론트 3자 검토 종합)

> 이 문서는 `DEPLOY.md`(Cloud Run 런북)를 실행하기 **전에** 통과해야 할 게이트를 정의한다.
> DBA·백엔드·프론트 3개 관점에서 각각 리스크를 도출해 종합했다. 실행 절차 자체는 `DEPLOY.md`,
> 리스크 배경은 `RISKS.md`를 함께 본다. 작성일 2026-07-08.

## 0. 한 줄 결론 — 세 관점이 만난 지점

세 검토가 **독립적으로 같은 결론 두 개**에 도달했다. 이게 배포의 뼈대다.

1. **core 서비스는 MVP에서 단일 인스턴스(`--min-instances=1 --max-instances=1`)로 고정한다.**
   `@Scheduled` 의사결정 잡, Slack Socket Mode, M7 Pub/Sub pull — 셋 다 다중 인스턴스에서 깨진다
   (중복 실행·중복 수신·CPU 정지). `DEPLOY.md` 목표구성표는 core를 "무상태 오토스케일 허용"으로
   적었지만 **코드는 single-instance를 요구한다 — 문서가 코드와 상충하므로 DEPLOY.md를 고쳐야 한다.**
2. **공개 URL 절대 금지.** `X-Member-Id`는 인증이 아니다(UUID만 알면 누구든 위장, 스페이스 멤버십 없음).
   `--no-allow-unauthenticated`(IAM) 또는 `--ingress=internal` + IAP/사내 프록시 뒤에 둔다.

이 둘이 안 되면 배포하지 않는다(No-Go).

---

## 1. 배포 차단 게이트 (Blocker — 전부 충족 전 배포 금지)

| # | 관점 | 항목 | 완화 | 근거 |
|---|------|------|------|------|
| B1 | 백/프론트 | X-Member-Id는 인증 아님 | `--no-allow-unauthenticated` 또는 internal ingress + IAP | `HeaderAuthFilter.java`, `SecurityConfig.java:18 permitAll`, RISKS C-2 |
| B2 | 백엔드 | @Scheduled 잡 N개 인스턴스에서 N번 실행 | core `min=max=1` (또는 ShedLock+Postgres LockProvider) | `DecisionExtractionJob.java:91`, ShedLock 없음 확인 |
| B3 | 백엔드 | Slack Socket 다중 연결 → 이벤트 중복 수신 | core `min=max=1` | `SlackSocketModeRunner.java:69 startAsync` |
| B4 | DBA | 오토스케일 시 커넥션 풀 폭발(첫날 장애) | `--max-instances` 상한 + Hikari `maximum-pool-size` 작게 고정 | Hikari 설정 없음(기본 10), Cloud Run 기본 max 100 |
| B5 | DBA | `CREATE EXTENSION vector` 권한/순서 | 슈퍼유저로 **Flyway 전에 1회** 수동 실행 | `V1__init.sql:1`, Cloud SQL은 cloudsqlsuperuser 필요 |
| B6 | 프론트 | knowledge.html detail 패널 XSS | **수정 완료(2026-07-08)** — innerHTML → textContent | `knowledge.html` detail 렌더 |
| B7 | 프론트 | TipTap을 esm.sh CDN에서 런타임 로드 | 배포 대상망에서 도달성 확인, 사내망이면 self-host | `index.html:185-191` |

> B6은 이번 커밋에서 이미 고쳤다. 나머지는 인프라/배포 설정 단계에서 처리한다.

---

## 2. 높음 (첫 배포 신뢰도 — 배포와 함께 처리)

**DBA**
- [ ] **knowledge_triple 풀스캔**: `KnowledgeGraphService.search()`가 매 질의마다 `findAll()`로 전체 트리플을
      메모리 로드 후 앱에서 BM25 계산. 파일럿 소량에선 무해하나 **행 수 선형 증가 → 지연·GC 압박**.
      성장 곡선(행 수, `/graph` 응답시간) 모니터링. 근본해결은 DB-side tsvector/GIN 이관(별도 태스크).
- [ ] **최소 권한 2계정**: `mydoc_migrate`(Flyway DDL 전용, 배포 시만) / `mydoc_app`(런타임 DML만,
      CREATE·DDL 없음). 앱이 상시 슈퍼권한을 갖지 않게.
- [ ] **ivfflat 재학습**: `V2`의 ivfflat 인덱스는 빈 테이블에 만들어져 미학습 상태. 초기 데이터 적재 후
      `REINDEX`. `ivfflat.probes` 세션 설정 없으면 recall 낮음. 데이터 늘면 hnsw 전환 검토.
- [ ] **PITR/백업**: 인스턴스 생성 시 `--enable-point-in-time-recovery` + 백업 명시. Flyway는 자동 롤백
      없음 → forward-fix 원칙(보정 마이그레이션 V+1).

**백엔드**
- [ ] **Gemini 재시도 없음**: 429/5xx/타임아웃 시 즉시 예외. 실키 켜기 전 지수 백오프+jitter 재시도 추가
      (RISKS A-3 무료티어 429 대비). 잡 루프는 이미 스레드별 try/catch로 전체 실패는 막음.
- [ ] **트랜잭션 내 임베딩(RISKS A-2)**: 임베딩이 `@Transactional` 안에서 120s 타임아웃 동안 Hikari
      커넥션 점유 → 풀 고갈 위험. 요청 경로에서 동기 임베딩 제거가 배포 전 조건.
- [ ] **시크릿 주입**: `--set-secrets` 이름과 코드 env 정확히 일치. `DEPLOY.md` 시크릿 루프에서
      `SLACK_DEFAULT_SPACE_SLUG`/`TIRO_API_KEY`/`MEET_*` 누락 시 기능이 **에러 없이 조용히 꺼짐**
      (`@ConditionalOnExpression`). `GOOGLE_APPLICATION_CREDENTIALS`는 **파일 경로**라 Secret을
      볼륨 마운트(`--set-secrets=/path=SECRET:latest`)해야 함. 런타임 SA에 `secretmanager.secretAccessor` 부여.
- [ ] **`.dockerignore`에 `.env`**: `Dockerfile`이 `COPY . .`라 빌드 컨텍스트의 `.env`가 이미지에 딸려
      들어갈 위험(RISKS C-5 키 노출 이력).
- [ ] **startup probe = `/actuator/health`** + 넉넉한 `failureThreshold`(fat-jar 콜드스타트 + Flyway +
      JPA validate). health는 DB 상태 포함 → Cloud SQL VPC 커넥터 미비 시 DOWN으로 배포 롤백됨.
      `--no-cpu-throttling` + `min-instances=1` 없으면 요청 없을 때 Socket/Pub-sub 루프가 죽음(함정 5).

**프론트**
- [ ] **CSP + 보안 헤더**: core에 `Content-Security-Policy`(TipTap self-host면 `script-src 'self'`,
      esm.sh 유지면 `'self' https://esm.sh`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`.
- [ ] **에러 피드백 구멍**: `knowledge.html` 그래프 로드 실패 시 빈 캔버스만(피드백 없음),
      `index.html` 검색 실패를 "결과 없음"으로 위장, 부트/`loadNav` 실패 미처리. 첫 배포 신뢰도에 직결.
- [ ] **TipTap SRI 또는 self-host**: 공급망 변조·오프라인 대비.

---

## 3. 중간 (운영 정책·후속 과제)

- [ ] **editing-plane 배포 범위 결정**: 현재 shipped UI는 Hocuspocus WebSocket을 **호출하지 않는다**
      (저장은 REST 3초 디바운스). editing-plane을 이번에 올리면 **아무도 안 부르는 dead service**가 된다.
      올릴지 말지 결정하고, 안 올리면 `DEPLOY.md`의 editing-plane 구성(함정 1·2)은 이번 범위에서 제외.
      동시 편집은 RISKS C-3대로 "한 문서 한 명 편집" 공지 + 리비전 복구로 운영.
- [ ] **민감 문서 미투입**: 파일럿 기간 인사/재무 등은 넣지 않는다(B1이 완전히 풀리기 전까지).
- [ ] **그래프 limit=60 유지**: force-layout이 O(n²)이라 수백 노드부터 렉. 서버가 이미 60으로 상한.
      확장 시 Barnes-Hut + 라벨 컬링 + alpha decay 정지.

---

## 4. 권장 배포 순서 (게이트 반영)

```
0. 사전(코드):  B6 XSS 수정(완료) · .dockerignore .env · Hikari pool-size 고정 ·
               (선택) ShedLock 또는 core min=max=1 확정 · CSP 헤더
1. DB 준비:    Cloud SQL 생성(--enable-point-in-time-recovery) →
               슈퍼유저로 CREATE EXTENSION vector (B5) →
               mydoc_migrate / mydoc_app 2계정 생성(최소권한)
2. 시크릿:     Secret Manager 등록(누락 없이) + GAC는 볼륨 마운트 +
               런타임 SA에 secretAccessor
3. 이미지:     docker build (.dockerignore 확인) → Artifact Registry push
4. 마이그레이션: mydoc_migrate로 Flyway 실행 → 데이터 적재 후 ivfflat REINDEX
5. core 배포:  --min-instances=1 --max-instances=1 --no-cpu-throttling
               --no-allow-unauthenticated(또는 internal ingress) --timeout=3600
               startup probe=/actuator/health · Hikari pool 작게 · mydoc_app 계정
6. 검증:       health UP → 수동 싱크(POST /api/knowledge/sync)로 파이프라인 1회 확인 →
               채널 옵트인 설정 → 그래프 렌더 확인
7. editing-plane: (범위 결정 시에만) min=max=1
```

## 5. DEPLOY.md에 반영할 수정

- 목표구성표의 core "무상태 오토스케일 허용" → **"단일 인스턴스 고정(min=max=1)"** 로 정정
  (@Scheduled·Socket·Pub/Sub pull이 오토스케일과 양립 불가).
- 시크릿 루프에 누락된 `SLACK_DEFAULT_SPACE_SLUG`·`TIRO_API_KEY`·`MEET_*` 추가.
- `CREATE EXTENSION vector`를 "접속 후 주석"에서 **Flyway 선행 필수 단계**로 승격.
- Hikari `maximum-pool-size` 명시 + `max-instances × pool-size < Cloud SQL max_connections` 부등식 기재.
