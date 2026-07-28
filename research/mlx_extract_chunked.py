#!/usr/bin/env python3
"""
로컬 서빙 검증 — MLX로 변환한 학생 모델을 GPU(vLLM)와 같은 조건으로 돌려 품질을 대조한다.

'변환이 됐다'는 검증이 아니다. 양자화·런타임이 바뀌어도 같은 추출이 나오는지가 핵심이라
청킹·게이트·프롬프트를 vLLM 경로(exaone_extract_chunked.py)와 글자 그대로 맞춘다.
같은 평가셋에 돌려 modal_grounded를 비교하면 양자화 손실이 그대로 드러난다.

사용:
  venv/bin/python mlx_extract_chunked.py --model mlx/exaone-v4-q8 \
      --corpus corpus/eval80.jsonl --prompts prompts.json \
      --out runs/mlx_v4_gate_hint.jsonl --gate hint
"""
import argparse
import json
import time

import korean_syntax as ks
from chunking import build_body, chunk_paragraphs, merge, parse_json_block
from mlx_lm import generate, load
from mlx_lm.sample_utils import make_sampler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--gate", choices=["off", "hint", "filter"], default="hint")
    ap.add_argument("--chunk-chars", type=int, default=1000)
    ap.add_argument("--max-tokens", type=int, default=1536)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    prompts = json.load(open(args.prompts))
    system_prompt = prompts["system"]
    user_template = prompts["user_template"]
    canonical = prompts.get("canonical_predicates", "")

    rows = [json.loads(l) for l in open(args.corpus)]
    if args.limit:
        rows = rows[:args.limit]

    model, tokenizer = load(args.model)
    # temp=0 — vLLM 쪽도 greedy라 디코딩 조건을 맞춘다.
    sampler = make_sampler(temp=0.0)

    jobs = []
    for i, row in enumerate(rows):
        for chunk in chunk_paragraphs(row["input"], args.chunk_chars):
            body = build_body(chunk, args.gate, ks)
            if body is not None:
                jobs.append((i, body))

    print(f"[chunk] 문서 {len(rows)} → 청크 {len(jobs)}", flush=True)

    per_doc = [[] for _ in rows]
    ok = fail = 0
    tokens_out = 0
    started = time.time()
    for n, (idx, body) in enumerate(jobs, 1):
        messages = [{"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_template % (body, canonical)}]
        prompt = tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True,
            **{"enable_thinking": False})
        text = generate(model, tokenizer, prompt=prompt, max_tokens=args.max_tokens,
                        sampler=sampler, verbose=False)
        tokens_out += len(tokenizer.encode(text))
        parsed, _ = parse_json_block(text)
        if parsed is None:
            fail += 1
        else:
            ok += 1
            per_doc[idx].append(parsed)
        if n % 50 == 0:
            print(f"  {n}/{len(jobs)} · {time.time()-started:.0f}s", flush=True)
    elapsed = time.time() - started

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

    print(f"[done] gate={args.gate} · 문서 {usable}/{len(rows)} · 청크 {ok}/{ok+fail} 파싱 · "
          f"{elapsed:.0f}s ({tokens_out/max(elapsed,1):.0f} tok/s 생성)")


if __name__ == "__main__":
    main()
