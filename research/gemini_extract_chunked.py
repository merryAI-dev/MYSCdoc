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
import os
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import korean_syntax as ks
from chunking import build_body, chunk_paragraphs, merge, parse_json_block

ENDPOINT = ("https://generativelanguage.googleapis.com/v1beta/models/"
            "{model}:generateContent")


def load_api_key(env_path):
    """.env에서 키만 읽는다. 값은 어디에도 출력하지 않는다."""
    key = os.environ.get("GEMINI_API_KEY")
    if key:
        return key
    with open(env_path) as f:
        for line in f:
            line = line.strip()
            if line.startswith("GEMINI_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("GEMINI_API_KEY를 .env에서 찾지 못했습니다")


def call_gemini(model, api_key, system_prompt, user_text, max_tokens, retries=4, schema=None):
    generation = {
        "temperature": 0,
        "maxOutputTokens": max_tokens,
        # EXAONE을 non-reasoning으로 돌렸으므로 사고 토큰을 꺼서 조건을 맞춘다.
        "thinkingConfig": {"thinkingBudget": 0},
    }
    if schema is not None:
        # vLLM쪽만 스키마를 강제하면 '모델 차이'가 아니라 '강제 유무 차이'를 재게 된다.
        # 실제로 그 실수를 했다 — Gemini 출력에 evidence_ids가 통째로 빠져 근거가 0건이었다.
        generation["responseMimeType"] = "application/json"
        generation["responseSchema"] = schema
    body = json.dumps({
        "systemInstruction": {"parts": [{"text": system_prompt}]},
        "contents": [{"role": "user", "parts": [{"text": user_text}]}],
        "generationConfig": generation,
    }).encode()
    req = urllib.request.Request(
        ENDPOINT.format(model=model), data=body,
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key})

    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                payload = json.load(resp)
            candidates = payload.get("candidates") or []
            if not candidates:
                return None, "no_candidate"
            parts = candidates[0].get("content", {}).get("parts") or []
            text = "".join(p.get("text", "") for p in parts)
            if not text:
                return None, f"empty:{candidates[0].get('finishReason')}"
            return text, None
        except urllib.error.HTTPError as exc:
            # 429/5xx만 재시도 — 4xx는 요청 자체가 잘못된 것이라 반복해도 같다.
            if exc.code in (429, 500, 502, 503, 504) and attempt < retries - 1:
                time.sleep(2 ** attempt)
                continue
            return None, f"http_{exc.code}"
        except Exception as exc:
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
                continue
            return None, f"error:{type(exc).__name__}"
    return None, "retries_exhausted"


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
