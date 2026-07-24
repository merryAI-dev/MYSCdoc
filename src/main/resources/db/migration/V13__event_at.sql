-- 시간축 복원: 그래프/트리플에 '사건 시각'(회의일·메시지 시각)을 붙인다.
-- 기존엔 created_at(임포트 시각)만 있어 전체 기간 분석이 불가능했다 — 실데이터는 3개월치.

ALTER TABLE document ADD COLUMN event_at timestamptz;
ALTER TABLE knowledge_triple ADD COLUMN event_at timestamptz;

-- ── 문서 사건일 백필 ─────────────────────────────────────────────
-- Drive 회의록: 제목이 'YYYYMMDD [회의명]...' 규칙이라 제목에서 날짜를 뽑는다(약 94% 매칭).
UPDATE document d
SET event_at = to_timestamp(substring(d.title from '^(20\d{6})'), 'YYYYMMDD')
FROM google_drive_ingest_log g
WHERE g.document_id = d.id AND d.title ~ '^20\d{6}';

-- ── 트리플 사건 시각 백필 ────────────────────────────────────────
-- Drive 트리플: 소속 문서의 event_at을 그대로 복사(그래프는 트리플 단위로 시간 필터한다).
UPDATE knowledge_triple t
SET event_at = d.event_at
FROM document d
WHERE d.id = t.document_id AND t.channel_id = 'drive' AND d.event_at IS NOT NULL;

-- Slack 트리플: thread_ts가 epoch 초라 그대로 시각으로 변환(2020년 이후만 — 로컬 테스트 행 배제).
UPDATE knowledge_triple t
SET event_at = to_timestamp(NULLIF(t.thread_ts, '')::float8)
WHERE t.channel_id NOT IN ('drive', 'tiro', 'meetily')
  AND t.thread_ts ~ '^\d+\.?\d*$'
  AND to_timestamp(NULLIF(t.thread_ts, '')::float8) >= timestamptz '2020-01-01';

-- Slack 문서 사건일: 그 문서 트리플들의 최소 event_at으로 채운다(문서=스레드).
UPDATE document d
SET event_at = sub.ev
FROM (SELECT document_id, min(event_at) AS ev FROM knowledge_triple
      WHERE event_at IS NOT NULL GROUP BY document_id) sub
WHERE d.id = sub.document_id AND d.event_at IS NULL;

CREATE INDEX knowledge_triple_event_idx ON knowledge_triple (event_at);
