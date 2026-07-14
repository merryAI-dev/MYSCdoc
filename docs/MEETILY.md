# Meetily 연동 가이드 (M17)

[Meetily](https://github.com/Zackriya-Solutions/meetily)는 회의 오디오를 **로컬에서** 녹음·전사하는
오픈소스 회의 비서다. mydoc은 Meetily의 레거시 FastAPI 백엔드에서 회의 전사를 가져와
(사이드바 "⇩ Meetily에서 가져오기") 문서로 보존하고, Slack·Tiro·Drive와 같은
Gemini 지식 추출 파이프라인으로 지식그래프에 연결한다.

요약은 Meetily의 로컬 LLM(Ollama)을 쓰지 않는다 — **raw 전사만 가져오고**
정리·추출은 mydoc의 기존 파이프라인이 담당한다 (Auto Summary는 기본 꺼짐이라 추가 설정 불필요).

## 실행 — macOS·Windows 공통 (Docker 권장 경로)

Meetily 현행 버전은 HTTP API가 없는 데스크톱 앱이라, mydoc 연동은 repo의
`backend/`(FastAPI, 포트 5167 + whisper-server 8178)를 사용한다.
**두 OS 모두 Docker Desktop 위에서 같은 커맨드로 돌리는 것을 표준으로 한다** —
OS별 스크립트 차이(아래)를 전부 우회할 수 있는 유일한 경로다.

```bash
# Meetily repo의 backend/ 디렉터리에서 (macOS·Windows PowerShell 동일)
WHISPER_LANGUAGE=ko docker compose up -d          # macOS/Linux
$env:WHISPER_LANGUAGE="ko"; docker compose up -d  # Windows PowerShell
```

`docker-compose.yml`에 환경변수를 직접 박아두면 OS 무관하게 잊어버릴 일이 없다:

```yaml
services:
  whisper:
    environment:
      - WHISPER_LANGUAGE=ko   # 기본값 en — 반드시 ko로
```

mydoc 쪽 설정은 `.env` 하나 (양 OS 동일):

```
MEETILY_BASE_URL=http://localhost:5167
```

비워두면 기능이 꺼진다(Tiro와 동일한 게이트).

## 네이티브 실행 시 OS별 차이 (Docker를 안 쓸 때)

| 환경 | 기본값 | 바꿀 것 |
|---|---|---|
| macOS 스크립트 | `clean_start_backend.sh`가 언어를 물음 | 프롬프트에 `ko` 입력 → whisper-server에 `--language ko` 전달 |
| **Windows 스크립트** | `start_whisper_server.cmd`에 언어 플래그 자체가 없음 → **en 고정** | cmd 파일의 whisper-server 실행 줄에 `--language ko` 수동 추가 |
| 데스크톱 앱(참고) | 언어 `auto-translate` — **한국어 발화가 영어로 번역 저장됨** | 설정에서 Korean(ko) 선택 |

Windows 네이티브는 스크립트 수정이 필요하므로, 수정 없이 쓰려면 Docker 경로를 권장한다.

## 모델 선택 (양 OS 공통)

**반드시 다국어 모델**을 받을 것: `ggml-medium.bin` 이상 권장(품질 우선이면
`ggml-large-v3-turbo`). `.en` 접미 모델(`ggml-base.en` 등)은 언어 옵션을 무시하고
영어를 강제하므로 한국어에 쓸 수 없다. 모델 교체는 파일 하나 교체로 끝난다 —
whisper-server 시작 시 `-m models/ggml-medium.bin` 경로만 다국어 모델로 지정하면 된다.

## 보안 주의

Meetily 레거시 백엔드는 **무인증 + CORS 와일드카드**다 (repo도 "지원되는 프로덕션 API가
아님"을 명시). 반드시:

- `localhost` 또는 사내망에만 바인딩하고 공개 포트로 노출하지 말 것
- mydoc만 소비자가 되도록 방화벽/리버스 프록시로 격리할 것
- 레거시 스택은 공식 폐기 상태이므로 특정 릴리스 태그에 고정(pin)해 운영할 것

## 동작 방식

1. `POST /api/integrations/meetily/browse` → Meetily `GET /get-meetings` (id·title 목록)
2. 가져오기 클릭 → `GET /get-meeting/{id}`로 전사 세그먼트 수신
   (세그먼트는 서버가 순서를 보장하지 않아 audio_start_time으로 정렬)
3. 문서 생성(원문 보존: 회의 정보 + `[시작s - 끝s]` 타임코드 문단) + `meetily_ingest_log` dedup
4. Gemini 추출 → `knowledge_triple` (source label `meetily`) — 실패해도 원문은 보존되고
   "🔗 지식그래프 동기화"가 나중에 따라잡는다 (Tiro·Drive와 동일)
