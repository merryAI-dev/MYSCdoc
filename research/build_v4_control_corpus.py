#!/usr/bin/env python3
"""Annotate the historical v2 corpus with the 32B re-audit, without changing examples."""
import argparse
import hashlib
import json
from collections import Counter, defaultdict, deque
from pathlib import Path


def digest(row):
    value = {"prompt": row["prompt"], "completion": row["completion"]}
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]


def write_jsonl(path, rows):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--teacher", required=True)
    parser.add_argument("--judged", required=True)
    parser.add_argument("--source-dir", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    teacher = read_jsonl(args.teacher)
    judged = json.loads(Path(args.judged).read_text())["teacher"]["rows"]
    verdicts = {row["sample_id"]: row for row in judged}
    if len(teacher) != 528 or len(verdicts) != 528:
        raise SystemExit(f"teacher/judged must both be 528 rows: {len(teacher)}/{len(verdicts)}")

    by_hash = defaultdict(deque)
    for index, row in enumerate(teacher):
        sample_id = f"v2-{index:04d}"
        if sample_id not in verdicts:
            raise SystemExit(f"missing verdict: {sample_id}")
        by_hash[digest(row)].append((row, verdicts[sample_id]))

    stats = Counter()
    outputs = {}
    for split, expected in (("train", 449), ("dev", 79)):
        source = read_jsonl(Path(args.source_dir) / f"{split}.jsonl")
        if len(source) != expected:
            raise SystemExit(f"{split}: expected {expected}, got {len(source)}")
        annotated = []
        for row in source:
            bucket = by_hash[digest(row)]
            if not bucket:
                raise SystemExit(f"{split}: corpus row is absent from teacher output")
            original, verdict = bucket.popleft()
            target = {"답변": "answerable", "부분답변": "partial", "거절": "unanswerable"}[
                verdict["response"]]
            meta = {
                "question": original["question"],
                "target": target,
                "source_label": original["label"],
                "fabricated": verdict["fabricated"],
                "grade_reflected": verdict["grade_reflected"],
                "audit": "exaone-4.0.1-32b-judge-unvalidated",
            }
            annotated.append({**row, "meta": meta})
            stats[(original["label"], target, verdict["fabricated"])] += 1
        outputs[split] = annotated
        write_jsonl(Path(args.out_dir) / f"{split}.jsonl", annotated)

    if any(by_hash.values()):
        raise SystemExit("teacher rows remain unmatched")
    if Counter(digest(row) for rows in outputs.values() for row in rows) != Counter(map(digest, teacher)):
        raise SystemExit("annotating changed the historical prompt/completion multiset")
    print(f"[done] train={len(outputs['train'])} dev={len(outputs['dev'])} → {args.out_dir}")
    for key, count in sorted(stats.items()):
        print(f"  source={key[0]:21s} target={key[1]:12s} fabricated={str(key[2]):5s} n={count}")


if __name__ == "__main__":
    main()
