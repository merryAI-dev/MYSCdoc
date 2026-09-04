#!/usr/bin/env python3
"""
조건 B/C — EXAONE으로 회의록에서 구조화 추출 (vLLM, 텐서 병렬 + 배치).

exaone_extract.py(HF transformers 순차 생성)는 GPU 사용률 40%대·문서당 최대 72초로
180문서에 ~2시간이 걸렸다. vLLM은 (1) tensor_parallel_size로 GPU 여러 장을 한 모델에
묶고 (2) continuous batching으로 요청을 한꺼번에 처리해 처리량을 크게 올린다.
프롬프트·파싱 로직은 exaone_extract.py와 동일하게 유지해 비교 조건을 고정한다.

사용:
  python exaone_extract_vllm.py --model /data/tta/EXAONE/exaone-4.0.1-32B-local \
      --corpus corpus/corpus.jsonl --prompts scripts/prompts.json \
      --out runs/condB_exaone32b.jsonl --tp 2
"""
import argparse
import json
import time

from vllm import LLM, SamplingParams


def parse_json_block(text):
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
    ap.add_argument("--tp", type=int, default=2, help="tensor_parallel_size — GPU 몇 장에 모델을 나눌지")
    ap.add_argument("--repetition-penalty", type=float, default=1.0,
                     help="1.0=끔. 소규모 SFT 모델의 반복 루프 완화용 (예: 1.15)")
    args = ap.parse_args()

    prompts = json.load(open(args.prompts))
    system_prompt = prompts["system"]
    user_template = prompts["user_template"]
    canonical = prompts.get("canonical_predicates", "")

    rows = [json.loads(l) for l in open(args.corpus)]
    if args.limit:
        rows = rows[:args.limit]

    print(f"[load] {args.model} (tensor_parallel_size={args.tp})", flush=True)
    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=32768)
    tokenizer = llm.get_tokenizer()
    sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens,
                               repetition_penalty=args.repetition_penalty)

    prompts_text = []
    for row in rows:
        user = user_template % (row["input"], canonical)
        messages = [{"role": "system", "content": system_prompt}, {"role": "user", "content": user}]
        prompts_text.append(tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True))

    print(f"[generate] {len(prompts_text)}건 배치 시작", flush=True)
    started = time.time()
    # vLLM이 continuous batching으로 전부 동시 처리 — 순차 for-loop 대비 핵심 속도 차이.
    outputs = llm.generate(prompts_text, sampling)
    elapsed_total = time.time() - started
    print(f"[generate] 완료: {elapsed_total:.1f}s ({elapsed_total/max(len(rows),1):.2f}s/문서 평균)", flush=True)

    ok = fail = 0
    with open(args.out, "w") as out:
        for row, output in zip(rows, outputs):
            completion = output.outputs[0].text
            parsed, error = parse_json_block(completion)
            if parsed is None:
                fail += 1
            else:
                ok += 1
            out.write(json.dumps({
                "doc_id": row["doc_id"],
                "title": row["title"],
                "input_tokens": len(output.prompt_token_ids),
                "output_tokens": len(output.outputs[0].token_ids),
                "parse_error": error,
                "output": parsed,
                "raw": None if parsed else completion[:2000],
            }, ensure_ascii=False) + "\n")

    print(f"[done] ok={ok} fail={fail} · 총 {elapsed_total:.1f}s")


if __name__ == "__main__":
    main()
