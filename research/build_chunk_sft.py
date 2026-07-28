#!/usr/bin/env python3
"""
M19-1 재설계: 청크 단위 증류 코퍼스 생성 — 학습 분포를 서빙 분포에 맞춘다.

기존 학습셋의 결함: 입력이 '문서 전체(24,000자)'였는데 서빙은 '청크(1,000자)'다.
학생은 청크 크기 입력을 한 번도 못 보고 평가받았다. 여기서 그 불일치를 없앤다.

교사도 바꾼다 — 문서 전체 Gemini가 아니라 실측상 최고 조건(Gemini + 어미 게이트 hint,
modal_grounded 59.2%)을 청크마다 호출해 타깃으로 쓴다. 학생이 서빙 때 만날 입력과
글자 그대로 같은 것을 학습한다.

분할: 학습 문서 160개를 150(train)/10(dev)로 나눈다. dev는 학습 중 eval_loss·best
체크포인트 선택용이다. 최종 평가용 val 20문서는 여기에 일절 섞지 않는다 — 기존 학습은
val로 체크포인트를 골라 약한 선택 누수가 있었고, 이번에 그것도 없앤다.

사용:
  venv/bin/python build_chunk_sft.py --corpus corpus/corpus.jsonl \
      --train-ids corpus/sft_train_ids.jsonl --prompts prompts.json --out-dir corpus/sft_chunk
"""
import argparse
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor

import korean_syntax as ks
from chunking import build_body, chunk_paragraphs, parse_json_block
from gemini_extract_chunked import call_gemini, load_api_key

DEV_DOCS = 10


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--train-ids", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--env", default="../.env")
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--gate", default="hint", choices=["off", "hint"])
    ap.add_argument("--chunk-chars", type=int, default=1000)
    ap.add_argument("--max-tokens", type=int, default=4096)
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    api_key = load_api_key(args.env)
    prompts = json.load(open(args.prompts))
    system_prompt = prompts["system"]
    user_template = prompts["user_template"]
    canonical = prompts.get("canonical_predicates", "")

    train_ids = {json.loads(l)["doc_id"] for l in open(args.train_ids)}
    rows = [json.loads(l) for l in open(args.corpus) if json.loads(l)["doc_id"] in train_ids]
    rows.sort(key=lambda r: r["doc_id"])          # 결정적 순서 — 재실행해도 같은 분할
    dev_ids = {r["doc_id"] for r in rows[:DEV_DOCS]}

    jobs = []
    for row in rows:
        for chunk in chunk_paragraphs(row["input"], args.chunk_chars):
            body = build_body(chunk, args.gate, ks)
            if body is None:
                continue
            jobs.append((row["doc_id"], user_template % (body, canonical)))

    print(f"[build] 문서 {len(rows)} (dev {len(dev_ids)}) → Gemini 호출 {len(jobs)}건", flush=True)

    started = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(
            lambda job: call_gemini(args.model, api_key, system_prompt, job[1], args.max_tokens),
            jobs))
    elapsed = time.time() - started

    os.makedirs(args.out_dir, exist_ok=True)
    outs = {s: open(os.path.join(args.out_dir, f"{s}.jsonl"), "w") for s in ("train", "dev")}
    counts = {"train": 0, "dev": 0}
    failed = 0

    for (doc_id, user), (text, err) in zip(jobs, results):
        if text is None:
            failed += 1
            continue
        parsed, _ = parse_json_block(text)
        if parsed is None:
            failed += 1
            continue
        split = "dev" if doc_id in dev_ids else "train"
        outs[split].write(json.dumps({
            "doc_id": doc_id,
            "prompt": [{"role": "system", "content": system_prompt},
                       {"role": "user", "content": user}],
            "completion": [{"role": "assistant",
                            "content": json.dumps(parsed, ensure_ascii=False)}],
        }, ensure_ascii=False) + "\n")
        counts[split] += 1

    for f in outs.values():
        f.close()
    print(f"[done] train {counts['train']} · dev {counts['dev']} · 실패 {failed} · {elapsed:.1f}s")


if __name__ == "__main__":
    main()
