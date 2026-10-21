# MYSCdoc + nanoMuse 로컬 통합

2026-10-21. MYSCdoc의 지식그래프 UI는 유지하고 채팅 창에 nanoMuse 웹 콘솔을 연결한다. nanoMuse의 에이전트 루프에 `myscdoc_search`를 추가한다. 현재는 네이티브 도구이며 MCP 프로토콜 서버는 아니다. 원래 MCP 연결 기능은 유지한다.

## Fork / clone 후 빠른 시작

검증 환경은 macOS, Java 21, Python 3.13, Node.js 22 이상, Docker Compose v2다. 화면 제어는 호스트에서 실행하므로 Muse를 Docker 안에 넣지 않는다. Linux/Windows의 GUI 권한과 입력 드라이버는 별도 검증이 필요하다.

1. 이 저장소 또는 자신의 fork를 clone하고 루트로 이동한다. nanoMuse 런타임·웹 소스·메리 이미지가 이미 포함되어 있어 별도 upstream clone이나 submodule 초기화가 필요 없다.
2. `python3.13 scripts/setup_muse.py`를 실행한다. 가상환경과 Chromium을 설치하고 `.env`가 없을 때만 예시를 복사한다. 기존 `.env`는 덮어쓰지 않는다.
3. `.env`의 `ANTHROPIC_API_KEY`에 **자신의 키**를 넣는다. 모델은 계정에서 사용 가능한 `ANTHROPIC_CHAT_MODEL`로 지정한다. 키는 브라우저나 Git에 넣지 않는다.
4. `docker compose -p myscdoc-muse -f compose.muse.yml up -d --wait`로 전용 DB를 시작한다. 이미 해양수산 DB가 55436에서 실행 중이라면 이 단계는 생략한다. 같은 포트에 DB를 둘 띄우지 않는다. Homebrew standalone Compose 설치 환경에서는 `docker compose` 대신 `docker-compose`를 사용한다.
5. 첫 터미널에서 `python3 scripts/run_muse_app.py`, 두 번째에서 `integrations/nanomuse/.venv/bin/python integrations/nanomuse/serve_local.py`를 실행한다.
6. `http://127.0.0.1:8086/`의 멤버 ID 로그인에 `762538ab-40e5-47fc-a3a2-83ba0d729001`을 입력하고 지식그래프 → **지식그래프에게 묻기**를 연다. 이 ID는 로컬 데모가 생성하는 공개 개발용 ID다.

이 경로는 빈 코퍼스로 시작한다. 실제 사내 자료와 테스트 결과는 제공하지 않는다. 기존 문서는 지우지 않는다. 자신의 자료를 앱에서 추가하거나 `MARINE_CORPUS=/absolute/path/corpus.json python3 scripts/run_muse_app.py`로 명시한다. 코퍼스 형식은 JSON 배열이며 각 항목은 `id`, `member`(문서 파일명), `text`, `sha256`을 가진다. 원문과 추출 요청은 설정한 Claude API로 전송되고 사용료가 발생한다.

종료는 각 터미널에서 Ctrl+C, DB는 `docker compose -p myscdoc-muse -f compose.muse.yml down`이다. DB 볼륨은 보존된다. 화면 제어에는 macOS 화면 기록/손쉬운 사용 권한이 필요할 수 있고, 앱의 실행 승인 절차는 유지된다.

### 웹 수정 및 검증

```sh
cd integrations/nanomuse/web
npm ci --ignore-scripts
npm run check
npm run build
```

빌드 결과도 저장소에 포함하므로 일반 실행에는 Node 빌드가 필요 없다. Python 연결 테스트는 저장소 루트에서 `integrations/nanomuse/.venv/bin/python integrations/nanomuse/test_local.py`로 실행한다. 메리 원본은 `web/public/avatars/merry.jpg`이며 파일을 변형하지 않고 CSS로 왼쪽 얼굴을 표시한다. 용 동영상은 기본 아바타에서 사용하지 않는다. upstream의 `dragon` 설정 ID는 기존 프로필 호환을 위해 유지한다.

## 구성과 실행

- MYSCdoc: `http://127.0.0.1:8086/knowledge.html` → **지식그래프에게 묻기**.
- nanoMuse: `127.0.0.1:8787`, 인증된 로컬 런타임. MYSCdoc의 marine-local 전용 launch API가 로컬 세션을 연결한다.
- 검색 도구: 인증된 MYSCdoc `/api/knowledge/agent/evidence`에서 문서 제목·주체·술어·대상·진술·미검토 분류를 가져온다. 원문 전체 조회는 아니다.
- 모델: 저장된 `.env`의 `ANTHROPIC_API_KEY`, `ANTHROPIC_CHAT_MODEL`을 서버에서 읽고 Anthropic Messages API를 직접 호출한다. 브라우저에 모델 키를 전달하지 않는다.

저장소 루트에서 별도 터미널 두 개로 실행한다. PostgreSQL marine 로컬 인스턴스가 먼저 실행되어 있어야 한다.

```sh
python3 scripts/run_marine_local.py
integrations/nanomuse/.venv/bin/python integrations/nanomuse/serve_local.py
```

Python 3.13 가상환경 및 `requirements.txt`는 현재 macOS 환경 기준이다. 새 환경에서는 해당 가상환경에 의존성을 설치하고 `python -m playwright install chromium`을 실행한다. Java 서비스에는 Java 21이 필요하다. 웹 변경 후에는 `npm ci --ignore-scripts`, `npm run check`, `npm run build`를 `web/`에서 실행한다. 빌드 출력은 vendored runtime의 static 폴더다.

## 유지한 기능과 경계

에이전트 루프, 도구 실행, 로컬 기억, 목표, 브라우저, 컴퓨터 화면 제어, MCP 연결, Sentinel 승인 흐름을 보존했다. 화면 조작·셸 실행 등은 승인을 요청한다. 로컬 기억은 키워드 검색이며 임베딩 학습이나 온톨로지 실험의 성능 개선을 의미하지 않는다. 자동 선제 활동은 기본적으로 꺼져 있다.

모델 factory를 직접 Claude 어댑터로 교체했다. nanoMuse Cloud 요청, 원격 허브, 계정 동기화, 모델 공급자 전환 및 업데이트 확인을 차단/비활성화했다. 사용자 요청에 따른 웹·브라우저 도구의 네트워크 기능은 유지한다. Claude만으로 별도 이미지·영상 생성 모델을 대체하지 않는다. 이는 제한된 통합 검토이며 전체 공급망의 무결성이나 백도어 부재를 보증하는 감사 결과는 아니다.

`.nanomuse-local/`에 세션·기억·작업공간·로컬 인증 토큰을 저장하며 Git에서 제외한다. `.env`도 제외한다. 현재 launch API는 marine-local 개발환경 전용이며 운영 다중 사용자 인증 설계는 별도다. Claude 응답은 완료 후 UI에 전달하므로 현재 어댑터는 토큰 단위 스트리밍을 제공하지 않는다.

## 검증 결과

- 웹 lint, TypeScript, Vitest 112개 통과 및 production build 성공. 번들 크기 경고는 남아 있다.
- `test_local.py` 7개 통과: Claude factory, 이미지/도구 결과 변환, Cloud 요청 차단, redirect 거부, 셸 환경의 키 제거, 화면 조작 승인, 검색 인자 검증.
- MYSCdoc 화면에서 nanoMuse 콘솔 로딩 확인.
- 실제 Claude 질의: “해송물산이 포장까지 자체 제공하기로 확정했나요?” → `myscdoc_search` 성공 → 관련 세 문서 제목과 미확정 설명을 포함한 최종 답변. 원문 전체·최신 후속 결정은 확인하지 않았다는 한계도 표시했다.
- 실행 기록: 상위 작업공간 `experiments/nanomuse-integration/live-chat.json`. 이 단일 사례는 연결 검증이며 검색 정확도 향상의 통계적 증거가 아니다.
- 화면 제어 도구 등록/사용 가능 상태와 승인 정책을 확인했다. 실제 OS 클릭·입력과 모든 원본 기능의 종단간 동작은 아직 검증하지 않았다.

## 출처

nanoMuse `2b967497418cc3a88428744547004afd78b9baa8`, GPL-3.0-or-later. 원본 출처 및 해시는 `vendor/UPSTREAM.json`, 라이선스와 고지는 `vendor/LICENSE`, `vendor/NOTICE`, `vendor/THIRD_PARTY_NOTICES.md`에 보존했다. Python 런타임과 웹 소스를 함께 포함하며 Android/harness의 Ideas JSON은 원본 웹 테스트용 자료다.
