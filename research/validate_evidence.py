#!/usr/bin/env python3
"""
evidence 필드가 실제로 '원문 그대로'인지 검증한다 — 교사 데이터 650콜을 쓰기 전에.

evidence의 가치는 전부 verbatim 여부에 달려 있다. 모델이 조금이라도 다듬으면
원문에서 위치를 찾을 수 없고, 그러면 문장 단위 출처도 정확한 modality 계산도 무너진다.
프롬프트로 "그대로 복사하라"고 시켰다고 되는 게 아니라 실측해야 한다.

판정 단계:
  exact      원문에 문자열이 그대로 있음        → 오프셋 확정 가능 (목표)
  normalized 공백만 다름                        → 허용 가능 (정규화 후 위치 확정)
  missing    원문에서 못 찾음                    → 재작성/환각. 이 비율이 높으면 설계 실패

사용:
  venv/bin/python validate_evidence.py --corpus corpus/eval80.jsonl --limit 5
"""
import argparse
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor

import korean_syntax as ks
from chunking import build_body, chunk_paragraphs, parse_json_block
from gemini_extract_chunked import call_gemini, load_api_key


def norm(s):
    return re.sub(r"\s+", "", s or "")


def classify(evidence, chunk):
    if not evidence.strip():
        return "empty"
    if evidence in chunk:
        return "exact"
    if norm(evidence) and norm(evidence) in norm(chunk):
        return "normalized"
    return "missing"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--prompts", default="prompts_evidence.json")
    ap.add_argument("--env", default="../.env")
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--gate", default="hint")
    ap.add_argument("--chunk-chars", type=int, default=1000)
    ap.add_argument("--limit", type=int, default=5)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--out", default="runs/evidence_smoke.jsonl")
    args = ap.parse_args()

    api_key = load_api_key(args.env)
    p = json.load(open(args.prompts))
    rows = [json.loads(l) for l in open(args.corpus)][:args.limit]

    jobs = []
    for row in rows:
        for chunk in chunk_paragraphs(row["input"], args.chunk_chars):
            body = build_body(chunk, args.gate, ks)
            if body is not None:
                # 검증은 청크 원문 대조라 청크를 그대로 들고 다닌다 (병합하면 대조가 불가능).
                jobs.append((row["doc_id"], chunk, p["user_template"] % (body, p.get("canonical_predicates", ""))))

    print(f"[smoke] 문서 {len(rows)} → 청크 {len(jobs)}건 호출", flush=True)
    started = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(
            lambda j: call_gemini(args.model, api_key, p["system"], j[2], 4096), jobs))

    tally = {"exact": 0, "normalized": 0, "missing": 0, "empty": 0}
    decisions = with_ev = 0
    misses = []
    out = open(args.out, "w")
    for (doc_id, chunk, _), (text, err) in zip(jobs, results):
        if text is None:
            continue
        parsed, _ = parse_json_block(text)
        if not parsed:
            continue
        for d in parsed.get("decisionPoints", []) or []:
            decisions += 1
            evs = d.get("evidence") or []
            if isinstance(evs, str):
                evs = [evs]
            if evs:
                with_ev += 1
            for ev in evs:
                verdict = classify(str(ev), chunk)
                tally[verdict] += 1
                if verdict == "missing":
                    misses.append((str(ev)[:90], d.get("decision", "")[:60]))
            out.write(json.dumps({"doc_id": doc_id, "decision": d.get("decision"),
                                  "evidence": evs}, ensure_ascii=False) + "\n")
    out.close()

    total = sum(tally.values())
    print(f"\n[결과] {time.time()-started:.0f}s · 결정 {decisions}건 중 evidence 있음 {with_ev}건")
    if total:
        for k in ("exact", "normalized", "missing", "empty"):
            print(f"  {k:11s} {tally[k]:3d} ({tally[k]/total:5.1%})")
        usable = (tally["exact"] + tally["normalized"]) / total
        print(f"\n  위치 확정 가능: {usable:.1%}")
        print("  → 설계 유효. 650콜 진행 가능" if usable >= 0.85 else
              "  → 재작성이 너무 많다. 프롬프트 보강 필요")
    if misses:
        print(f"\n[원문에서 못 찾은 evidence 예시 {min(3,len(misses))}건]")
        for ev, dec in misses[:3]:
            print(f"  ev : {ev}")
            print(f"  결정: {dec}\n")


if __name__ == "__main__":
    main()
