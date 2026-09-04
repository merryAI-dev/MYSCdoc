#!/usr/bin/env python3
"""
M19-1 후처리: LoRA 어댑터 병합 → 로컬 서빙용 HF 모델 저장.

이후 Mac에서 GGUF 변환·서빙 (llama.cpp >= b5932가 exaone4 아키텍처 지원):
  python convert_hf_to_gguf.py <merged-dir> --outtype bf16 --outfile exaone12b-kg.gguf
  ./llama-quantize exaone12b-kg.gguf exaone12b-kg-Q8_0.gguf Q8_0
  ./llama-server -m exaone12b-kg-Q8_0.gguf --jinja        # --jinja 필수: EXAONE 챗 템플릿 적용

주의:
  - 1.2B는 Q4까지 내리면 구조화 출력 정확도가 눈에 띄게 깨진다 — Q8_0(또는 Q6_K) 사용.
  - Ollama는 exaone4 렌더러가 메인라인에 없다(2026-07 기준) — llama-server나 LM Studio 사용.
  - 병합은 bf16 그대로 (fp32 업캐스트 후 절단 금지).

사용:
  env/bin/python scripts/merge_and_export.py \
      --base /data/tta/EXAONE/exaone-4.0.1-1.2B-local \
      --adapter runs/sft_exaone12b_lora/best --out runs/exaone12b-kg-merged
"""
import argparse

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--adapter", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    base = AutoModelForCausalLM.from_pretrained(
        args.base, torch_dtype=torch.bfloat16, trust_remote_code=True)
    merged = PeftModel.from_pretrained(base, args.adapter).merge_and_unload()
    merged.save_pretrained(args.out)
    AutoTokenizer.from_pretrained(args.base, trust_remote_code=True).save_pretrained(args.out)
    print(f"[done] merged model → {args.out}")


if __name__ == "__main__":
    main()
