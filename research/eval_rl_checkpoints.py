#!/usr/bin/env python3
"""
Phase 1 판정 — 체크포인트별 held-out hit@k (가설 H14·H17·H19).

판정 기준은 held-out 재작성 hit@k 하나다. 학습 reward는 참고치일 뿐이다(H17 —
SearchGym에서 reward만 오르고 실전은 무너진 사례). 붕괴가 기본값이므로 전 체크포인트를
평가해 최고를 고른다(H14).

3조건 비교(H19): ① 질문 그대로(naive) ② 무학습 1.2B 재작성 ③ RL 체크포인트 재작성.
②가 ①보다 나쁠 수 있음이 문헌에 있다(RL-QR: 무학습 재작성 최대 −27%) — 그래서 ②를
건너뛰면 RL의 기여를 과대평가하게 된다.

사용:
  python eval_rl_checkpoints.py --base <1.2B> --ckpt-root runs/grpo_search \
      --val rl/val.jsonl --corpus rl/rl_corpus.jsonl
"""
import argparse
import glob
import json
import re

import torch
from rank_bm25 import BM25Okapi
from transformers import AutoModelForCausalLM, AutoTokenizer

from kiwipiepy import Kiwi

CONTENT = ("NNG", "NNP", "NNB", "NR", "SL", "SN", "VV", "VA", "XR")
_kiwi = Kiwi()
SYSTEM = ("사내 회의록 지식그래프를 BM25로 검색한다. 질문을 검색에 효과적인 "
          "핵심 키워드 검색어 한 줄로 바꿔라. 검색어만 출력하고 다른 말은 하지 마라.")


def toks(text):
    return [m.form for m in _kiwi.tokenize(text or "") if m.tag in CONTENT]


def hit_stats(ranks, n):
    return {k: sum(1 for r in ranks if r and r <= k) / n for k in (1, 5, 10)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--ckpt-root", required=True)
    ap.add_argument("--val", required=True)
    ap.add_argument("--corpus", required=True)
    args = ap.parse_args()

    corpus = [json.loads(l) for l in open(args.corpus)]
    bm25 = BM25Okapi([toks(c["text"]) for c in corpus])
    val = [json.loads(l) for l in open(args.val)]
    n = len(val)

    def rank_of(query_text, gold):
        q = toks(query_text)
        if not q:
            return None
        scores = bm25.get_scores(q)
        order = sorted(range(len(corpus)), key=lambda i: -scores[i])[:30]
        gset = set(gold)
        return next((r for r, i in enumerate(order, 1)
                     if scores[i] > 0 and i in gset), None)

    # ① naive
    naive = hit_stats([rank_of(v["question"], v["gold"]) for v in val], n)
    print(f"① 질문그대로   hit@1 {naive[1]:.1%} · @5 {naive[5]:.1%} · @10 {naive[10]:.1%}")

    tok = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)

    def eval_model(path, label):
        model = AutoModelForCausalLM.from_pretrained(
            path, torch_dtype=torch.bfloat16, device_map="auto", trust_remote_code=True)
        model.eval()
        ranks = []
        for v in val:
            msgs = [{"role": "system", "content": SYSTEM},
                    {"role": "user", "content": v["question"]}]
            # 이 transformers 버전은 apply_chat_template(return_tensors=)가 dict를 반환 —
            # 문자열로 렌더한 뒤 일반 토크나이저 경로로 텐서를 얻는다.
            text_in = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
            ids = tok(text_in, return_tensors="pt").input_ids.to(model.device)
            with torch.no_grad():
                out = model.generate(ids, max_new_tokens=96, do_sample=False,
                                     pad_token_id=tok.eos_token_id)
            text = tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True).strip()
            text = text.split("\n")[0][:80]
            ranks.append(rank_of(text, v["gold"]))
        del model
        torch.cuda.empty_cache()
        h = hit_stats(ranks, n)
        print(f"{label:14s} hit@1 {h[1]:.1%} · @5 {h[5]:.1%} · @10 {h[10]:.1%}")
        return h

    # ② 무학습 재작성
    eval_model(args.base, "② 무학습재작성")
    # ③ RL 체크포인트들
    ckpts = sorted(glob.glob(f"{args.ckpt_root}/checkpoint-*"),
                   key=lambda p: int(p.rsplit("-", 1)[1]))
    best = (None, 0.0)
    for c in ckpts:
        h = eval_model(c, f"③ step{c.rsplit('-', 1)[1]}")
        if h[10] + h[1] > best[1]:
            best = (c, h[10] + h[1])
    print(f"\n[best] {best[0]}")


if __name__ == "__main__":
    main()
