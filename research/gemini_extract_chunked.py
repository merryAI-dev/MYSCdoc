#!/usr/bin/env python3
"""
조건 A′/D — Gemini로 같은 청크에서 구조화 추출. 게이트 유무만 바꿔 효과를 분리한다.

왜 A(기존 코퍼스 타깃)를 그대로 못 쓰나: 그건 Gemini가 문서 '전체'를 보고 뽑은 것이라
청크 입력인 EXAONE 조건과 비교하면 게이트 효과와 청킹 효과가 섞인다. 청킹을 고정하고
게이트만 바꾼 A′/D를 새로 만들어야 2×2가 성립한다.

공정성: EXAONE은 non-reasoning 모드로 돌렸으므로 Gemini도 thinking을 끈다(thinkingBudget=0).
temperature=0으로 양쪽 모두 greedy.

API 키는 mydoc/.env의 GEMINI_API_KEY에서만 읽는다 — 인자로 받지도, 출력하지도 않는다.

사용:
  venv/bin/python gemini_extract_chunked.py --corpus corpus/val_only.jsonl \
      --prompts prompts.json --out runs/gemini_gate_off.jsonl --gate off
"""
import argparse
import json
import time
from concurrent.futures import ThreadPoolExecutor

import korean_syntax as ks
from chunking import build_body, chunk_paragraphs, merge, parse_json_block
from gemini_api import call_gemini, load_api_key


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--env", default="../.env")
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--gate", choices=["off", "hint", "filter"], default="off")
    ap.add_argument("--chunk-chars", type=int, default=1000)
    ap.add_argument("--max-tokens", type=int, default=4096)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    api_key = load_api_key(args.env)
    prompts = json.load(open(args.prompts))
    system_prompt = prompts["system"]
    user_template = prompts["user_template"]
    canonical = prompts.get("canonical_predicates", "")

    rows = [json.loads(l) for l in open(args.corpus)]
    if args.limit:
        rows = rows[:args.limit]

    jobs, dropped = [], 0
    for i, row in enumerate(rows):
        for chunk in chunk_paragraphs(row["input"], args.chunk_chars):
            body = build_body(chunk, args.gate, ks)
            if body is None:
                dropped += 1
                continue
            jobs.append((i, user_template % (body, canonical)))

    print(f"[chunk] 문서 {len(rows)} → 호출 {len(jobs)}건"
          + (f" (filter로 {dropped}개 제외)" if dropped else ""), flush=True)

    started = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(
            lambda job: call_gemini(args.model, api_key, system_prompt, job[1], args.max_tokens),
            jobs))
    elapsed = time.time() - started

    per_doc = [[] for _ in rows]
    ok = fail = 0
    for (idx, _), (text, err) in zip(jobs, results):
        if text is None:
            fail += 1
            continue
        parsed, _ = parse_json_block(text)
        if parsed is None:
            fail += 1
        else:
            ok += 1
            per_doc[idx].append(parsed)

    usable = 0
    with open(args.out, "w") as out:
        for row, parts in zip(rows, per_doc):
            merged = merge(parts) if parts else None
            usable += bool(merged)
            out.write(json.dumps({
                "doc_id": row["doc_id"],
                "title": row["title"],
                "chunks_ok": len(parts),
                "output": merged,
                "target_decisions": len(row["target"]["decisionPoints"]),
                "target_tacit": len(row["target"]["tacitKnowledge"]),
            }, ensure_ascii=False) + "\n")

    print(f"[done] gate={args.gate} · 문서 {usable}/{len(rows)} 사용가능 · "
          f"청크 {ok}/{ok + fail} 파싱 · {elapsed:.1f}s")


if __name__ == "__main__":
    main()
