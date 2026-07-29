#!/usr/bin/env python3
"""Run Gemini direct weighting on the exact V6 sealed prompts."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path

from gemini_api import call_gemini, load_api_key


IDS = [f"F{i:02d}" for i in range(1, 7)]
SCHEMA = {"type": "object", "properties": {"weights": {"type": "array", "items": {
    "type": "object", "properties": {
        "id": {"type": "string", "enum": IDS},
        "salience": {"type": "string", "enum": ["core", "supporting", "peripheral"]},
        "weight": {"type": "number"}},
    "required": ["id", "salience", "weight"]}}}, "required": ["weights"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--eval", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--env", default="../.env")
    parser.add_argument("--model", default="gemini-2.5-flash")
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    out = Path(args.out)
    if out.exists():
        raise SystemExit(f"refusing to overwrite {out}")
    cases = [json.loads(line) for line in open(args.eval, encoding="utf-8") if line.strip()]
    key = load_api_key(args.env)

    def run(row):
        system = "\n".join(message["content"] for message in row["prompt"]
                           if message["role"] == "system")
        user = "\n\n".join(message["content"] for message in row["prompt"]
                           if message["role"] != "system")
        return call_gemini(args.model, key, system, user, 600, schema=SCHEMA)

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        generated = list(pool.map(run, cases))
    rows = []
    for case, (answer, error) in zip(cases, generated):
        rows.append({"label": case["label"], "question": case["question"],
                     "prompt": case["prompt"], "meta": case["meta"],
                     "answer": answer or "", "error": error})
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"gemini": rows}, ensure_ascii=False, indent=1))
    print(f"[gemini] {sum(not row['error'] for row in rows)}/{len(rows)} complete")


if __name__ == "__main__":
    main()
