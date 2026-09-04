#!/usr/bin/env python3
"""
M19-1: SFT 데이터셋 준비 — corpus.jsonl → prompt/completion 형식 train/val JSONL.

교사(Gemini) 산출물을 학생(EXAONE 1.2B)에 증류하기 위한 전처리.
추출 스크립트(exaone_extract*.py)와 동일한 프롬프트를 써서 학습·추론 분포를 맞춘다.

prompt/completion 형식을 쓰는 이유: TRL이 이 형식에선 completion 토큰에만 loss를 거는 게
기본값이다(회의록 본문 예측에 용량 낭비 안 함). messages 형식 + assistant_only_loss는
챗 템플릿에 {% generation %} 마커가 필요한데 EXAONE 템플릿엔 없다.

분할은 doc_id 해시 기반 결정적 분할 — 재실행해도 같은 문서는 같은 쪽에 떨어진다.
val 문서는 학습에 절대 들어가지 않으므로 학생 모델 평가(조건 B' vs 교사)는 val로 한다.

사용:
  python prepare_sft_dataset.py --corpus corpus/corpus.jsonl --prompts scripts/prompts.json \
      --out-dir corpus/sft --val-ratio 0.12
"""
import argparse
import hashlib
import json
import os


def split_of(doc_id, val_ratio):
    h = int(hashlib.sha256(doc_id.encode()).hexdigest(), 16) % 10_000
    return "val" if h < val_ratio * 10_000 else "train"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--val-ratio", type=float, default=0.12)
    args = ap.parse_args()

    prompts = json.load(open(args.prompts))
    system_prompt = prompts["system"]
    user_template = prompts["user_template"]
    canonical = prompts.get("canonical_predicates", "")

    os.makedirs(args.out_dir, exist_ok=True)
    outs = {s: open(os.path.join(args.out_dir, f"{s}.jsonl"), "w") for s in ("train", "val")}
    counts = {"train": 0, "val": 0}

    for line in open(args.corpus):
        row = json.loads(line)
        split = split_of(row["doc_id"], args.val_ratio)
        sample = {
            "doc_id": row["doc_id"],
            "prompt": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_template % (row["input"], canonical)},
            ],
            "completion": [
                {"role": "assistant", "content": json.dumps(row["target"], ensure_ascii=False)},
            ],
        }
        outs[split].write(json.dumps(sample, ensure_ascii=False) + "\n")
        counts[split] += 1

    for f in outs.values():
        f.close()
    print(f"[sft] train={counts['train']} val={counts['val']} → {args.out_dir}/")


if __name__ == "__main__":
    main()
