#!/usr/bin/env python3
"""
챗 모델 평가 — 지어냄률과 과잉거절률을 함께 잰다 (한쪽만 보면 속는다).

문헌 경고: 거절 SFT는 과잉 거절로 넘어가기 쉽다. 그래서 홀드아웃 질문에 대해
  · unanswerable(쉬움/어려움) — 거절해야 정답. 못 하면 '지어냄'
  · answerable                — 답해야 정답. 거절하면 '과잉거절'
둘을 같은 표에 놓고 본다. 추가로 확정도 어휘 반영률도 잰다(v1 vs v2 차이가 여기서 난다).

비교 대상: 순정 1.2B / v1 학습본 / v2 학습본.

사용:
  python eval_chat_models.py --models base=<dir> v1=<dir> v2=<dir> --eval chat_eval.jsonl
"""
import argparse
import json

from vllm import LLM, SamplingParams

REFUSAL = ("없어요", "없습니다", "찾을 수 없", "확인할 수 없", "담겨 있지 않", "나와 있지 않")
GRADE = ("확정", "논의", "예정", "검토", "조건")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", required=True, help="이름=경로 형식")
    ap.add_argument("--eval", required=True)
    ap.add_argument("--out", default="../runs/chat_eval_results.json")
    args = ap.parse_args()

    cases = [json.loads(l) for l in open(args.eval)]
    print(f"평가 {len(cases)}건: " + ", ".join(
        f"{k}={sum(1 for c in cases if c['label'] == k)}"
        for k in sorted({c["label"] for c in cases})))

    results = {}
    for spec in args.models:
        name, path = spec.split("=", 1)
        llm = LLM(model=path, tensor_parallel_size=1, trust_remote_code=True,
                  dtype="bfloat16", gpu_memory_utilization=0.55, max_model_len=16384)
        tok = llm.get_tokenizer()
        prompts = [tok.apply_chat_template(c["prompt"], tokenize=False,
                                           add_generation_prompt=True,
                                           enable_thinking=False) for c in cases]
        outs = llm.generate(prompts, SamplingParams(temperature=0.1, max_tokens=400))

        stat = {"fabricate": [0, 0], "over_refuse": [0, 0], "graded": [0, 0]}
        samples = []
        for c, o in zip(cases, outs):
            ans = o.outputs[0].text.strip()
            refused = any(m in ans for m in REFUSAL)
            if c["label"].startswith("unanswerable"):
                stat["fabricate"][1] += 1
                stat["fabricate"][0] += (not refused)      # 거절 못 하면 지어냄
            else:
                stat["over_refuse"][1] += 1
                stat["over_refuse"][0] += refused          # 답 있는데 거절하면 과잉
                stat["graded"][1] += 1
                stat["graded"][0] += any(m in ans for m in GRADE)
            if len(samples) < 3:
                samples.append({"label": c["label"], "q": c["question"][:50], "a": ans[:160]})
        results[name] = {"stat": stat, "samples": samples}
        del llm

    print(f"\n{'모델':10s} {'지어냄률':>10s} {'과잉거절률':>12s} {'확정도반영':>12s}")
    for name, r in results.items():
        f_n, f_d = r["stat"]["fabricate"]
        o_n, o_d = r["stat"]["over_refuse"]
        g_n, g_d = r["stat"]["graded"]
        print(f"{name:10s} {f_n}/{f_d} = {f_n/max(f_d,1):5.0%}   "
              f"{o_n}/{o_d} = {o_n/max(o_d,1):5.0%}   {g_n}/{g_d} = {g_n/max(g_d,1):5.0%}")
    json.dump(results, open(args.out, "w"), ensure_ascii=False, indent=1)
    print(f"\n[저장] {args.out}")


if __name__ == "__main__":
    main()
