#!/usr/bin/env python3
"""
32B를 교사로 청크 단위 증류 데이터를 만든다 — Gemini 없이 완전 자체 호스팅.

왜 교사를 바꾸나: 파이프라인이 폐쇄 API에 종속되면 비용·데이터 반출·재현성이 모두 걸린다.
서버의 EXAONE 32B는 한국어 네이티브 모델이고 GPU 사용 기간이 끝나면 접근이 불가능하므로
지금 생성해 둬야 한다.

분할은 v4와 동일하게 유지한다(학습 90 + dev 10). 평가 80문서는 절대 포함하지 않는다 —
기존 조건들과 같은 잣대로 비교하려면 분할이 바뀌면 안 된다. 문서 목록은 새로 계산하지 않고
v4 SFT 파일의 doc_id를 그대로 읽어 쓴다(재계산은 조용한 분할 어긋남의 원인이 된다).

evidence는 원문 그대로여야 의미가 있으므로 생성 즉시 청크 대조로 verbatim율을 잰다.

사용:
  python exaone_teacher_sft.py --model /data/tta/EXAONE/exaone-4.0.1-32B-local \
      --corpus corpus/corpus.jsonl --split-dir corpus/sft_chunk_v4 \
      --prompts scripts/prompts_evidence.json --out-dir corpus/sft_chunk_32b --tp 2
"""
import argparse
import json
import os
import re
import time

import korean_syntax as ks
from chunking import build_body, chunk_paragraphs, parse_json_block
from extraction_schema import EXTRACTION_SCHEMA
from vllm import LLM, SamplingParams


def norm(s):
    return re.sub(r"\s+", "", s or "")


def verbatim_class(ev, chunk):
    if not str(ev).strip():
        return "empty"
    if ev in chunk:
        return "exact"
    if norm(ev) and norm(ev) in norm(chunk):
        return "normalized"
    return "missing"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--split-dir", required=True, help="v4 분할을 읽어올 곳 (train.jsonl/dev.jsonl)")
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--gate", default="hint")
    ap.add_argument("--chunk-chars", type=int, default=1000)
    ap.add_argument("--max-new-tokens", type=int, default=2048)
    ap.add_argument("--tp", type=int, default=2)
    ap.add_argument("--structured", action="store_true",
                     help="디코딩 단계에서 JSON 스키마 강제 — 파싱 실패 13.3%%를 없앤다")
    args = ap.parse_args()

    p = json.load(open(args.prompts))
    system_prompt, user_template = p["system"], p["user_template"]
    canonical = p.get("canonical_predicates", "")

    # v4 분할을 그대로 승계 — 재계산하지 않는다.
    split_of = {}
    for name in ("train", "dev"):
        for line in open(f"{args.split_dir}/{name}.jsonl"):
            split_of[json.loads(line)["doc_id"]] = name
    corpus = {json.loads(l)["doc_id"]: json.loads(l) for l in open(args.corpus)}
    doc_ids = sorted(split_of)
    print(f"[split] 학습대상 문서 {len(doc_ids)} "
          f"(train {sum(v=='train' for v in split_of.values())} / "
          f"dev {sum(v=='dev' for v in split_of.values())})", flush=True)

    jobs = []
    for doc_id in doc_ids:
        for chunk in chunk_paragraphs(corpus[doc_id]["input"], args.chunk_chars):
            body = build_body(chunk, args.gate, ks)
            if body is not None:
                jobs.append((doc_id, chunk, user_template % (body, canonical)))
    print(f"[chunk] 청크 {len(jobs)}건", flush=True)

    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=32768)
    tok = llm.get_tokenizer()
    prompts_text = [tok.apply_chat_template(
        [{"role": "system", "content": system_prompt}, {"role": "user", "content": user}],
        tokenize=False, add_generation_prompt=True) for _, _, user in jobs]

    sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens)
    if args.structured:
        from vllm.sampling_params import StructuredOutputsParams
        sampling.structured_outputs = StructuredOutputsParams(json=EXTRACTION_SCHEMA)
        print("[structured] JSON 스키마 강제 활성", flush=True)

    started = time.time()
    outputs = llm.generate(prompts_text, sampling)
    elapsed = time.time() - started

    os.makedirs(args.out_dir, exist_ok=True)
    files = {n: open(f"{args.out_dir}/{n}.jsonl", "w") for n in ("train", "dev")}
    counts = {"train": 0, "dev": 0}
    tally = {"exact": 0, "normalized": 0, "missing": 0, "empty": 0}
    parse_fail = 0

    for (doc_id, chunk, user), out in zip(jobs, outputs):
        parsed, _ = parse_json_block(out.outputs[0].text)
        if parsed is None:
            parse_fail += 1
            continue
        for d in parsed.get("decisionPoints", []) or []:
            evs = d.get("evidence") or []
            if isinstance(evs, str):
                evs = [evs]
            for ev in evs:
                tally[verbatim_class(str(ev), chunk)] += 1
        split = split_of[doc_id]
        files[split].write(json.dumps({
            "doc_id": doc_id,
            "prompt": [{"role": "system", "content": system_prompt},
                       {"role": "user", "content": user}],
            "completion": [{"role": "assistant",
                            "content": json.dumps(parsed, ensure_ascii=False)}],
        }, ensure_ascii=False) + "\n")
        counts[split] += 1

    for f in files.values():
        f.close()

    total = sum(tally.values())
    print(f"\n[done] {elapsed:.0f}s · train {counts['train']} · dev {counts['dev']} · "
          f"파싱실패 {parse_fail}")
    if total:
        usable = (tally["exact"] + tally["normalized"]) / total
        print(f"[evidence] exact {tally['exact']} · normalized {tally['normalized']} · "
              f"missing {tally['missing']} · empty {tally['empty']}")
        print(f"[evidence] 위치 확정 가능 {usable:.1%} "
              + ("→ 사용 가능" if usable >= 0.85 else "→ 낮음. 프롬프트 보강 검토"))
    else:
        print("[evidence] evidence가 하나도 생성되지 않음 — 프롬프트 전달 확인 필요")


if __name__ == "__main__":
    main()
