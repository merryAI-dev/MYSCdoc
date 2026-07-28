#!/usr/bin/env python3
"""
평가 검정력 확보를 위한 재분할 — 학습 문서를 줄이고 평가 문서를 80개로 늘린다.

왜: 문서 20개로는 8~10pt 차이가 유의하지 않았다(z=1.27, p≈0.20). 차이가 없어서가 아니라
판별할 검정력이 없어서다. 그런데 180문서 중 160개는 v3의 학습 데이터라 그냥 쓰면 오염이다.

추가 API 호출 0: 이미 만들어둔 647개 청크 Gemini 출력을 재사용한다. 학습에서 뺀 60문서의
청크 출력은 버리지 않고 문서 단위로 병합해 '교사 기준선'으로 쓴다.

분할 불변식:
  - 원래 val 20문서는 반드시 평가셋에 남는다 (한 번도 학습에 안 쓰인 유일한 문서들)
  - dev(체크포인트 선택)는 학습 문서에서만 뽑는다 — 평가셋과 절대 겹치지 않는다

사용: venv/bin/python resplit_for_power.py
"""
import json
import os
from collections import defaultdict

from chunking import merge

SFT_DIR = "corpus/sft_chunk"
OUT_DIR = "corpus/sft_chunk_v4"
EVAL_FROM_TRAIN = 60      # 학습에서 평가로 옮길 문서 수
DEV_DOCS = 10             # 학습 문서 중 체크포인트 선택용


def main():
    # 1) 기존 청크 SFT 쌍을 문서별로 모은다 (train+dev 모두 = 원래 학습 160문서)
    pairs = defaultdict(list)
    for split in ("train", "dev"):
        for line in open(f"{SFT_DIR}/{split}.jsonl"):
            r = json.loads(line)
            pairs[r["doc_id"]].append(r)
    train_docs = sorted(pairs)                       # 결정적 순서
    print(f"[in] 학습 문서 {len(train_docs)} · 청크쌍 {sum(len(v) for v in pairs.values())}")

    # 2) 앞에서부터 60문서를 평가로 이관 (정렬 순서라 재현 가능)
    moved = train_docs[:EVAL_FROM_TRAIN]
    kept = train_docs[EVAL_FROM_TRAIN:]
    dev_ids = set(kept[:DEV_DOCS])
    train_ids = set(kept[DEV_DOCS:])
    print(f"[split] 평가로 이관 {len(moved)} · 학습 {len(train_ids)} · dev {len(dev_ids)}")

    # 3) 새 학습셋 — 이관된 문서의 쌍은 학습에서 완전히 제외한다
    os.makedirs(OUT_DIR, exist_ok=True)
    counts = {}
    for name, ids in (("train", train_ids), ("dev", dev_ids)):
        with open(f"{OUT_DIR}/{name}.jsonl", "w") as f:
            n = 0
            for doc_id in sorted(ids):
                for r in pairs[doc_id]:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
                    n += 1
            counts[name] = n
    print(f"[out] 학습쌍 train={counts['train']} dev={counts['dev']}")

    # 4) 이관된 60문서의 Gemini 청크 출력을 문서 단위로 병합 = 교사 기준선
    corpus = {json.loads(l)["doc_id"]: json.loads(l) for l in open("corpus/corpus.jsonl")}
    with open("runs/gemini_eval_expanded.jsonl", "w") as out:
        for doc_id in moved:
            chunk_outs = []
            for r in pairs[doc_id]:
                # completion은 Gemini가 그 청크에서 뽑은 JSON 문자열이다.
                chunk_outs.append(json.loads(r["completion"][0]["content"]))
            row = corpus[doc_id]
            out.write(json.dumps({
                "doc_id": doc_id,
                "title": row["title"],
                "chunks_ok": len(chunk_outs),
                "output": merge(chunk_outs),
                "target_decisions": len(row["target"]["decisionPoints"]),
                "target_tacit": len(row["target"]["tacitKnowledge"]),
            }, ensure_ascii=False) + "\n")
        # 원래 val 20문서는 이미 문서 단위로 병합된 결과가 있다 — 그대로 이어붙인다.
        n_val = 0
        for line in open("runs/gemini_gate_hint.jsonl"):
            out.write(line)
            n_val += 1
    print(f"[out] 교사 기준선 {len(moved)}(이관) + {n_val}(원래 val) = {len(moved) + n_val}문서")

    # 5) 학생이 추출할 평가 코퍼스 (80문서)
    eval_ids = list(moved) + [json.loads(l)["doc_id"] for l in open("runs/gemini_gate_hint.jsonl")]
    with open("corpus/eval80.jsonl", "w") as out:
        for doc_id in eval_ids:
            out.write(json.dumps(corpus[doc_id], ensure_ascii=False) + "\n")
    print(f"[out] 평가 코퍼스 {len(eval_ids)}문서 → corpus/eval80.jsonl")

    # 6) 누수 검사 — 학습/평가가 한 문서라도 겹치면 즉시 실패시킨다
    overlap = (train_ids | dev_ids) & set(eval_ids)
    assert not overlap, f"누수! 학습·평가 겹침: {overlap}"
    print("[check] 학습·평가 문서 겹침 없음 ✓")


if __name__ == "__main__":
    main()
