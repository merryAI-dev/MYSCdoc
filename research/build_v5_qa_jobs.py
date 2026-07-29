#!/usr/bin/env python3
"""Split Slack threads by source and render numbered 32B teacher jobs."""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


def read_jsonl(path):
    with open(path, encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def stable_key(seed, row):
    raw = f"{seed}:{row['channel_id']}:{row['thread_ts']}"
    return hashlib.sha256(raw.encode()).hexdigest()


def assign_splits(rows, seed):
    ordered = sorted(rows, key=lambda row: stable_key(seed, row))
    n = len(ordered)
    counts = {
        "train": round(n * 0.70),
        "dev": round(n * 0.10),
        "selection": round(n * 0.10),
    }
    counts["sealed"] = n - sum(counts.values())
    output, cursor = [], 0
    for split in ("train", "dev", "selection", "sealed"):
        for row in ordered[cursor:cursor + counts[split]]:
            output.append((split, row))
        cursor += counts[split]
    return output


def make_job(split, row):
    lines = [line.strip() for line in row["input"].splitlines() if line.strip()]
    numbered = "\n".join(f"L{i:02d} {line}" for i, line in enumerate(lines, 1))
    source_key = f"{row['channel_id']}:{row['thread_ts']}"
    return {
        "source_key": source_key,
        "split": split,
        "channel_id": row["channel_id"],
        "thread_ts": row["thread_ts"],
        "message_count": row["message_count"],
        "lines": lines,
        "user": f"업무 대화 원문:\n{numbered}\n\n이 원문에서 검증 가능한 QA 후보를 만드세요.",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus")
    parser.add_argument("--out")
    parser.add_argument("--manifest")
    parser.add_argument("--seed", type=int, default=20260729)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        rows = [{"channel_id": "c", "thread_ts": str(i), "message_count": 2,
                 "input": "가: 하나\n나: 둘"} for i in range(20)]
        first = assign_splits(rows, 7)
        assert first == assign_splits(list(reversed(rows)), 7)
        assert Counter(split for split, _ in first) == {
            "train": 14, "dev": 2, "selection": 2, "sealed": 2}
        assert make_job(*first[0])["lines"] == ["가: 하나", "나: 둘"]
        print("SELF_CHECK_OK")
        return
    if not args.corpus or not args.out or not args.manifest:
        parser.error("--corpus, --out and --manifest are required")

    rows = read_jsonl(args.corpus)
    keys = [(row["channel_id"], row["thread_ts"]) for row in rows]
    if len(keys) != len(set(keys)):
        raise SystemExit("duplicate source thread")
    jobs = [make_job(split, row) for split, row in assign_splits(rows, args.seed)]
    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(job, ensure_ascii=False) + "\n" for job in jobs))
    counts = Counter(job["split"] for job in jobs)
    manifest = {
        "protocol": "v5-source-split-v1",
        "seed": args.seed,
        "source_sha256": hashlib.sha256(Path(args.corpus).read_bytes()).hexdigest(),
        "jobs_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "sources": len(jobs),
        "split_counts": dict(counts),
        "source_overlap": 0,
    }
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"[jobs] {len(jobs)} threads · {dict(counts)} · overlap=0")


if __name__ == "__main__":
    main()
