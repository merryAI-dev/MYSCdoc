-- M22: 의미 가중치 — 32B 판정(commitment×salience)에서 온 노드 무게를 트리플에 저장한다.
-- null = 미판정(중립 0.7로 취급). 백필은 research/backfill_weights.py가 한다.
ALTER TABLE knowledge_triple ADD COLUMN weight real;
