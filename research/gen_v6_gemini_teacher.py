#!/usr/bin/env python3
"""Generate source-grounded Slack QA and salience labels with Gemini."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import time

from gemini_api import call_gemini, load_api_key
from gen_v5_qa_teacher import SCHEMA, SYSTEM, valid_qa


V6_SYSTEM = SYSTEM + """

salience는 각 사실을 무조건 core로 두는 절대평가가 아니라, 이 스레드 안에서의 상대평가입니다.
여러 QA를 만들 때 중심 결과·결정만 core로 두고, 이를 이해하는 데 필요한 세부사항은 supporting,
일정·장소·인사·부수 절차·여담은 peripheral로 구분하세요. 실제 차이가 있는데 모든 후보에 같은
salience를 주지 마세요."""


def parse_job(job, text):
    try:
        payload = json.loads(text)
    except (TypeError, json.JSONDecodeError):
        return [], Counter(json=1), []
    accepted, rejected, examples, seen = [], Counter(), [], set()
    for qa in payload.get("qas", []):
        row, reason = valid_qa(qa, job["lines"])
        if reason:
            rejected[reason] += 1
            if len(examples) < 2:
                examples.append({"reason": reason, "qa": qa})
            continue
        key = row["question"].casefold()
        if key in seen:
            rejected["duplicate_question"] += 1
            continue
        seen.add(key)
        accepted.append({**row, "source_key": job["source_key"], "split": job["split"],
                         "channel_id": job["channel_id"], "thread_ts": job["thread_ts"]})
    return accepted, rejected, examples


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jobs")
    parser.add_argument("--out")
    parser.add_argument("--manifest")
    parser.add_argument("--env", default="../.env")
    parser.add_argument("--model", default="gemini-2.5-flash")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        job = {"source_key": "c:1", "split": "train", "channel_id": "c",
               "thread_ts": "1", "lines": ["알타바 설명회를 열기로 했어요."]}
        text = json.dumps({"qas": [{"kind": "atomic", "entity": "알타바",
            "question": "알타바 계획은?", "answer": "알타바 설명회를 열기로 했어요.",
            "evidence_ids": ["L01"], "commitment": "confirmed", "salience": "core"}]})
        rows, rejected, _ = parse_job(job, text)
        assert len(rows) == 1 and not rejected and rows[0]["salience"] == "core"
        print("SELF_CHECK_OK")
        return
    if not args.jobs or not args.out or not args.manifest:
        parser.error("--jobs, --out and --manifest are required")

    jobs = [json.loads(line) for line in open(args.jobs, encoding="utf-8") if line.strip()]
    if args.limit:
        jobs = jobs[:args.limit]
    key = load_api_key(args.env)

    def run(job):
        return call_gemini(args.model, key, V6_SYSTEM, job["user"], 1200, schema=SCHEMA)

    started = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        outputs = list(pool.map(run, jobs))

    rows, rejected, failures, examples = [], Counter(), Counter(), []
    for job, (text, error) in zip(jobs, outputs):
        if error:
            failures[error] += 1
            continue
        parsed, rejects, bad = parse_job(job, text)
        rows.extend(parsed)
        rejected.update(rejects)
        examples.extend({"source_key": job["source_key"], **item} for item in bad)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))
    manifest = {
        "protocol": "v6-gemini-teacher-v1", "model": args.model,
        "jobs": len(jobs), "accepted": len(rows), "api_failures": dict(failures),
        "rejected": dict(rejected), "rejected_examples": examples[:20],
        "by_split": dict(Counter(row["split"] for row in rows)),
        "by_salience": dict(Counter(row["salience"] for row in rows)),
        "jobs_sha256": hashlib.sha256(Path(args.jobs).read_bytes()).hexdigest(),
        "output_sha256": hashlib.sha256(out.read_bytes()).hexdigest(),
        "elapsed_seconds": round(time.time() - started, 3),
    }
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
