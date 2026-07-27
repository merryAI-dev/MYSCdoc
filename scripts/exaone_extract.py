#!/usr/bin/env python3
"""
조건 B/C — EXAONE으로 회의록에서 구조화 추출. Gemini(조건 A)와 같은 프롬프트를 쓴다.

공정 비교를 위해 프롬프트는 Java(JsonDecisionExtractPort)에서 그대로 추출해 넘긴다
(prompts.json). 모델만 바꾸고 나머지는 고정 — A2A의 이식성 검증과 같은 통제 방식.

사용:
  python exaone_extract.py --model /data/tta/EXAONE/exaone-4.0.1-32B-local \
      --corpus corpus/corpus.jsonl --prompts prompts.json --out runs/exaone32b.jsonl [--limit 3]
"""
import argparse
import json
import time

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def parse_json_block(text):
    """모델 출력에서 JSON 하나를 건져낸다 (코드펜스·서두 설명 허용) — Java 파서와 같은 관용도."""
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None, "no_json"
    try:
        return json.loads(text[start:end + 1]), None
    except json.JSONDecodeError as exc:
        return None, f"invalid_json:{exc.msg}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--max-new-tokens", type=int, default=2048)
    args = ap.parse_args()

    prompts = json.load(open(args.prompts))
    system_prompt = prompts["system"]
    user_template = prompts["user_template"]

    print(f"[load] {args.model}", flush=True)
    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, torch_dtype=torch.bfloat16, device_map="auto", trust_remote_code=True)
    model.eval()
    print(f"[load] done. device_map={getattr(model, 'hf_device_map', 'n/a')}", flush=True)

    rows = [json.loads(l) for l in open(args.corpus)]
    if args.limit:
        rows = rows[:args.limit]

    out = open(args.out, "w")
    ok = fail = 0
    for i, row in enumerate(rows, 1):
        # Java와 동일: user 템플릿에 (스레드 본문, 표준 관계 목록) 두 개를 채운다.
        user = user_template % (row["input"], prompts.get("canonical_predicates", ""))
        messages = [{"role": "system", "content": system_prompt},
                    {"role": "user", "content": user}]
        text = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        inputs = tokenizer(text, return_tensors="pt").to(model.device)

        started = time.time()
        with torch.no_grad():
            generated = model.generate(**inputs, max_new_tokens=args.max_new_tokens,
                                       do_sample=False, pad_token_id=tokenizer.eos_token_id)
        completion = tokenizer.decode(generated[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
        elapsed = time.time() - started

        parsed, error = parse_json_block(completion)
        if parsed is None:
            fail += 1
        else:
            ok += 1
        out.write(json.dumps({
            "doc_id": row["doc_id"],
            "title": row["title"],
            "elapsed_sec": round(elapsed, 2),
            "input_tokens": int(inputs["input_ids"].shape[1]),
            "parse_error": error,
            "output": parsed,
            "raw": None if parsed else completion[:2000],
        }, ensure_ascii=False) + "\n")
        out.flush()
        print(f"[{i}/{len(rows)}] {elapsed:.1f}s parse={'ok' if parsed else error}", flush=True)
    out.close()
    print(f"[done] ok={ok} fail={fail}")


if __name__ == "__main__":
    main()
