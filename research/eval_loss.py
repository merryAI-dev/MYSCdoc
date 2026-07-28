#!/usr/bin/env python3
"""
CMS(어댑터 스택) 실험 판정용 — 모델의 dev 손실을 시간 구간별로 잰다.

Nested Learning의 핵심 주장(다중 시간축 갱신)을 동결 체크포인트에서 검증하는 4칸 표:
              dev_old(과거)   dev_recent(최근)
  slow만          L00             L01
  slow+fast       L10             L11
성공 기준: L11 < L01 (fast가 최근에 적응) 그리고 L10 ≈ L00 (과거를 잊지 않음).
둘 다 성립해야 "빠른/느린 가중치 분리가 작동한다"고 말할 수 있다.

completion 토큰에만 손실을 계산한다 — 학습과 같은 기준.

사용:
  python eval_loss.py --model <dir> [--adapter <dir>] --data a.jsonl b.jsonl
"""
import argparse
import json

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def mean_completion_loss(model, tok, path, device):
    total_nll = 0.0
    total_tokens = 0
    for line in open(path):
        row = json.loads(line)
        full = tok.apply_chat_template(row["prompt"] + row["completion"], tokenize=False)
        prefix = tok.apply_chat_template(row["prompt"], tokenize=False,
                                         add_generation_prompt=True)
        ids_full = tok(full, return_tensors="pt").input_ids.to(device)
        n_prefix = tok(prefix, return_tensors="pt").input_ids.shape[1]
        labels = ids_full.clone()
        labels[:, :n_prefix] = -100
        n_comp = int((labels != -100).sum())
        if n_comp == 0:
            continue
        with torch.no_grad():
            out = model(input_ids=ids_full, labels=labels)
        # HF loss는 유효 라벨 평균 — 토큰 수를 곱해 총합으로 되돌려 가중 평균을 만든다.
        total_nll += float(out.loss) * n_comp
        total_tokens += n_comp
    return total_nll / max(total_tokens, 1), total_tokens


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--adapter", default=None)
    ap.add_argument("--data", nargs="+", required=True)
    args = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, torch_dtype=torch.bfloat16, device_map="auto", trust_remote_code=True)
    if args.adapter:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, args.adapter)
    model.eval()
    device = next(model.parameters()).device

    label = f"{args.model.split('/')[-1]}" + (f"+{args.adapter.split('/')[-1]}" if args.adapter else "")
    for path in args.data:
        loss, n = mean_completion_loss(model, tok, path, device)
        print(f"[loss] {label} · {path.split('/')[-1]}: {loss:.4f} ({n} tokens)")


if __name__ == "__main__":
    main()
