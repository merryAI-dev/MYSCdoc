#!/usr/bin/env python3
"""
조건 B' — 소형 모델(1.2B)용 청크 추출. 회의록을 문단 단위로 잘라 넣고 결과를 병합한다.

왜: 32B는 24,000자를 통째로 받아도 되지만 1.2B는 안 된다. 문서 전체를 한 번에 넣으면
생성이 길어지면서 모델이 자기가 이미 뽑은 결정을 잊고 같은 객체를 그대로 반복한다
(실측: val 20건 중 6건이 max_new_tokens까지 폭주, 타깃 JSON은 최대 1,683토큰뿐인데도).
문단 단위로 자르면 청크당 생성이 짧아져 그 실패 모드 자체가 사라진다.

부수 효과: 청크 하나가 깨져도 나머지는 살아남는다. 문서 전체가 통째로 버려지지 않는다.

로컬 서빙(Mac, 1.2B)에서도 같은 전처리를 써야 하므로 이 스크립트가 서빙 경로의 원형이다.

사용:
  python exaone_extract_chunked.py --model runs/exaone12b-kg-merged-v2 \
      --corpus corpus/val_only.jsonl --prompts scripts/prompts.json \
      --out runs/student_val_chunked.jsonl --tp 1 --chunk-chars 1500
"""
import argparse
import json
import time

import korean_syntax as ks
from vllm import LLM, SamplingParams


def parse_json_block(text):
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None, "no_json"
    try:
        return json.loads(text[start:end + 1]), None
    except json.JSONDecodeError as exc:
        return None, f"invalid_json:{exc.msg}"


def chunk_paragraphs(text, budget):
    """문단(줄) 경계를 지키며 budget 글자 이하로 묶는다. 문단 자체가 budget을 넘으면 단독 청크."""
    chunks, current = [], []
    size = 0
    for line in text.split("\n"):
        if not line.strip():
            continue
        # 현재 청크가 이미 차 있고 이 줄을 더하면 넘칠 때만 끊는다 (문단 중간 절단 없음).
        if current and size + len(line) > budget:
            chunks.append("\n".join(current))
            current, size = [], 0
        current.append(line)
        size += len(line)
    if current:
        chunks.append("\n".join(current))
    return chunks


def merge(parsed_chunks):
    """청크별 추출 결과를 문서 하나로 합친다 — 표층 중복만 제거."""
    decisions, tacit = [], []
    seen_d, seen_t = set(), set()
    for out in parsed_chunks:
        for d in out.get("decisionPoints", []) or []:
            key = (str(d.get("topic", "")).strip(), str(d.get("decision", ""))[:40].strip())
            if key in seen_d:
                continue
            seen_d.add(key)
            decisions.append(d)
        for t in out.get("tacitKnowledge", []) or []:
            key = str(t.get("statement", ""))[:60].strip()
            if key in seen_t:
                continue
            seen_t.add(key)
            tacit.append(t)
    return {"decisionPoints": decisions, "tacitKnowledge": tacit}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--max-new-tokens", type=int, default=1536)
    ap.add_argument("--tp", type=int, default=1)
    ap.add_argument("--chunk-chars", type=int, default=1500)
    ap.add_argument("--gate", choices=["off", "hint", "filter"], default="off",
                     help="결정 어미 게이트. hint=감지 문장을 프롬프트에 표시, "
                          "filter=감지된 청크만 투입(실측상 문서 손실 있음, ablation용)")
    args = ap.parse_args()

    prompts = json.load(open(args.prompts))
    system_prompt = prompts["system"]
    user_template = prompts["user_template"]
    canonical = prompts.get("canonical_predicates", "")

    rows = [json.loads(l) for l in open(args.corpus)]
    if args.limit:
        rows = rows[:args.limit]

    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=32768)
    tokenizer = llm.get_tokenizer()
    sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens)

    # 전 문서의 모든 청크를 하나의 배치로 — vLLM continuous batching이 알아서 채운다.
    prompts_text, owner = [], []
    dropped = 0
    for i, row in enumerate(rows):
        for chunk in chunk_paragraphs(row["input"], args.chunk_chars):
            marked = []
            if args.gate != "off":
                # 확장 표지 사용 — 핵심 3표지만으로는 청크의 25%만 걸려 문서가 통째로 날아간다.
                marked = [s for s in ks.sentences(chunk)
                          if ks.modality(s, extended=True)]
                if args.gate == "filter" and not marked:
                    dropped += 1
                    continue
            body = chunk
            if args.gate == "hint" and marked:
                body += ("\n\n[결정·의지 어미가 감지된 문장 — 결정 후보로 우선 검토]\n"
                         + "\n".join(f"- {s}" for s in marked))
            messages = [{"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_template % (body, canonical)}]
            prompts_text.append(tokenizer.apply_chat_template(
                messages, tokenize=False, add_generation_prompt=True))
            owner.append(i)
    if dropped:
        print(f"[gate] filter 모드로 청크 {dropped}개 제외", flush=True)

    print(f"[chunk] 문서 {len(rows)} → 청크 {len(prompts_text)} "
          f"(평균 {len(prompts_text)/max(len(rows),1):.1f}개/문서)", flush=True)

    started = time.time()
    outputs = llm.generate(prompts_text, sampling)
    elapsed = time.time() - started

    per_doc = [[] for _ in rows]
    chunk_ok = chunk_fail = runaway = 0
    for idx, output in zip(owner, outputs):
        parsed, error = parse_json_block(output.outputs[0].text)
        if parsed is None:
            chunk_fail += 1
            # 상한까지 채웠으면 폭주 생성, 아니면 형식 오류 — 원인이 다르니 구분해 센다.
            if len(output.outputs[0].token_ids) >= args.max_new_tokens:
                runaway += 1
        else:
            chunk_ok += 1
            per_doc[idx].append(parsed)

    docs_usable = 0
    with open(args.out, "w") as out:
        for row, parts in zip(rows, per_doc):
            merged = merge(parts) if parts else None
            if merged:
                docs_usable += 1
            out.write(json.dumps({
                "doc_id": row["doc_id"],
                "title": row["title"],
                "chunks_ok": len(parts),
                "output": merged,
                "target_decisions": len(row["target"]["decisionPoints"]),
                "target_tacit": len(row["target"]["tacitKnowledge"]),
            }, ensure_ascii=False) + "\n")

    print(f"[done] chunk={args.chunk_chars} gate={args.gate} · "
          f"문서 {docs_usable}/{len(rows)} 사용가능 · "
          f"청크 {chunk_ok}/{chunk_ok + chunk_fail} 파싱 (폭주 {runaway}) · {elapsed:.1f}s")


if __name__ == "__main__":
    main()
