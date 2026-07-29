#!/usr/bin/env python3
"""Generate V5 answers for a base model and all LoRA checkpoints."""
import argparse
import glob
import json
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--eval", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--ckpt-root")
    parser.add_argument("--adapter")
    parser.add_argument("--adapter-label", default="adapter")
    parser.add_argument("--label", default="base")
    parser.add_argument("--prefix", default="")
    parser.add_argument("--no-base", action="store_true")
    parser.add_argument("--gpu-frac", type=float, default=0.55)
    parser.add_argument("--max-new-tokens", type=int, default=400)
    args = parser.parse_args()

    from vllm import LLM, SamplingParams
    from vllm.lora.request import LoRARequest

    cases = [json.loads(line) for line in open(args.eval) if line.strip()]
    llm = LLM(model=args.base, trust_remote_code=True, dtype="bfloat16",
              gpu_memory_utilization=args.gpu_frac, max_model_len=16384,
              enable_lora=True, max_lora_rank=32, max_loras=1)
    tokenizer = llm.get_tokenizer()
    rendered = [tokenizer.apply_chat_template(row["prompt"], tokenize=False,
                                               add_generation_prompt=True,
                                               enable_thinking=False) for row in cases]
    sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens)
    targets = [] if args.no_base else [(args.label, None)]
    if args.ckpt_root:
        checkpoints = sorted(glob.glob(f"{args.ckpt_root}/checkpoint-*"),
                             key=lambda path: int(path.rsplit("-", 1)[1]))
        targets += [(f"{args.prefix}/step{path.rsplit('-', 1)[1]}", os.path.abspath(path))
                    for path in checkpoints]
    if args.adapter:
        targets.append((args.adapter_label, os.path.abspath(args.adapter)))
    if not targets:
        parser.error("base, --ckpt-root, or --adapter must select at least one target")
    output = {}
    for index, (name, adapter) in enumerate(targets, 1):
        request = LoRARequest(name, index, adapter) if adapter else None
        generations = llm.generate(rendered, sampling, lora_request=request)
        output[name] = [{"label": row["label"], "question": row["question"],
                         "prompt": row["prompt"], "meta": row["meta"],
                         "answer": generation.outputs[0].text.strip()}
                        for row, generation in zip(cases, generations)]
        print(f"[generate] {name} · {len(cases)}")
    path = Path(args.out)
    if path.exists():
        raise SystemExit(f"refusing to overwrite {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(output, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
