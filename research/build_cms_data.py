#!/usr/bin/env python3
"""
CMS 실험 데이터 분할 — 학습 100문서를 시간(event_at)으로 slow(과거80)/fast(최근20)로.

sft_sentid_fixed(번호 복원 완료본)를 time_split.json 기준으로 나눈다:
  cms_slow/train.jsonl + dev.jsonl   ← slow 어댑터 학습·조기종료용
  cms_fast/train.jsonl + dev.jsonl   ← fast 어댑터 학습·조기종료용
  cms_eval/dev_old.jsonl, dev_recent.jsonl ← 4칸 손실표 판정용 (학습에 안 씀)

dev_recent이 너무 적으면(5행 미만) fast 학습 문서의 최신 1개를 평가로 이관한다 —
칸이 비면 실험 자체가 판정 불능이 되기 때문.
"""
import json
import os

split = json.load(open("time_split.json"))
slow_ids, fast_ids = set(split["slow"]), set(split["fast"])

rows = {"train": [], "dev": []}
for name in rows:
    for line in open(f"../corpus/sft_sentid_fixed/{name}.jsonl"):
        rows[name].append(json.loads(line))

slow_train = [r for r in rows["train"] if r["doc_id"] in slow_ids]
fast_train = [r for r in rows["train"] if r["doc_id"] in fast_ids]
dev_old = [r for r in rows["dev"] if r["doc_id"] in slow_ids]
dev_recent = [r for r in rows["dev"] if r["doc_id"] in fast_ids]

if len(dev_recent) < 5 and fast_train:
    # 최신 문서 하나를 평가로 이관 (doc_id 사전순이 아니라 원 corpus 시간순을 따르도록
    # time_split의 fast 목록 끝에서부터 찾는다)
    for did in reversed(split["fast"]):
        moved = [r for r in fast_train if r["doc_id"] == did]
        if moved:
            fast_train = [r for r in fast_train if r["doc_id"] != did]
            dev_recent += moved
            print(f"[이관] dev_recent 부족 → 최신 문서 {did[:8]} ({len(moved)}행) 평가로")
            break

def dump(path, items):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        for r in items:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

dump("../corpus/cms_slow/train.jsonl", slow_train)
dump("../corpus/cms_slow/dev.jsonl", dev_old)
dump("../corpus/cms_fast/train.jsonl", fast_train)
dump("../corpus/cms_fast/dev.jsonl", dev_recent)
dump("../corpus/cms_eval/dev_old.jsonl", dev_old)
dump("../corpus/cms_eval/dev_recent.jsonl", dev_recent)

print(f"slow_train {len(slow_train)} · fast_train {len(fast_train)} · "
      f"dev_old {len(dev_old)} · dev_recent {len(dev_recent)}")
assert dev_old and dev_recent, "평가 칸이 비었다 — 분할 재검토 필요"
