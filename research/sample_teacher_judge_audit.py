#!/usr/bin/env python3
"""Create a stratified, blinded human-calibration sample from teacher re-judging."""
import argparse
import hashlib
import json
import math
import random
from collections import defaultdict
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--judged", required=True)
    parser.add_argument("--blind-out", required=True)
    parser.add_argument("--key-out", required=True)
    parser.add_argument("--n", type=int, default=100)
    parser.add_argument("--min-per-stratum", type=int, default=5)
    parser.add_argument("--seed", type=int, default=20260728)
    args = parser.parse_args()

    source = Path(args.judged)
    rows = json.loads(source.read_text())["teacher"]["rows"]
    groups = defaultdict(list)
    for row in rows:
        groups[(row["label"], row["response"])].append(row)
    if args.n < len(groups) * args.min_per_stratum:
        raise SystemExit("n is too small for the requested per-stratum minimum")

    rng = random.Random(args.seed)
    for group in groups.values():
        rng.shuffle(group)
    quota = {key: min(args.min_per_stratum, len(group)) for key, group in groups.items()}
    remaining = args.n - sum(quota.values())
    capacity = {key: len(groups[key]) - quota[key] for key in groups}
    total_capacity = sum(capacity.values())
    raw = {key: remaining * capacity[key] / total_capacity for key in groups}
    for key in groups:
        quota[key] += math.floor(raw[key])
    for key in sorted(groups, key=lambda item: raw[item] - math.floor(raw[item]), reverse=True):
        if sum(quota.values()) == args.n:
            break
        if quota[key] < len(groups[key]):
            quota[key] += 1

    selected = [row for key, group in groups.items() for row in group[:quota[key]]]
    rng.shuffle(selected)
    blind, key_rows = [], []
    for index, row in enumerate(selected, 1):
        audit_id = f"audit-{index:03d}"
        blind.append({"audit_id": audit_id, "question": row["question"],
                      "facts": row["facts"], "answer": row["answer"]})
        key_rows.append({"audit_id": audit_id, "sample_id": row["sample_id"],
                         "source_label": row["label"], "judge_response": row["response"],
                         "judge_fabricated": row["fabricated"],
                         "judge_grade_reflected": row["grade_reflected"]})
    if len(blind) != args.n or any("judge" in key for row in blind for key in row):
        raise SystemExit("blind sample self-check failed")

    blind_path, key_path = Path(args.blind_out), Path(args.key_out)
    blind_path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in blind))
    key_path.write_text(json.dumps({
        "protocol": "teacher-judge-human-calibration-v1",
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "seed": args.seed,
        "n": args.n,
        "quota": {f"{label}|{response}": quota[(label, response)]
                  for label, response in sorted(groups)},
        "rows": key_rows,
    }, ensure_ascii=False, indent=2) + "\n")
    print(f"[done] blinded={len(blind)} strata={len(groups)} → {blind_path}")
    for stratum, count in sorted(quota.items()):
        print(f"  {stratum[0]:21s} {stratum[1]:5s} n={count}")


if __name__ == "__main__":
    main()
