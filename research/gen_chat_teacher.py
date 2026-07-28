#!/usr/bin/env python3
"""
챗 답변 교사 데이터 생성 — 32B가 (질문 + 등급 붙은 사실) → 답변을 만든다.

학습 목표 두 가지가 데이터에 다 들어 있어야 한다:
  · 확정도 구분 — [확정]은 단정, [논의]는 "확정 아님"으로. 사내에서 제일 많이 묻는
    "정해진 거야, 얘기만 나온 거야?"에 답하려면 이 구분이 답변에 드러나야 한다.
  · 거절 — 근거 없는 질문(unanswerable)에 지어내지 않기. 순정 1.2B는 6건 중 4건 실패했다.

생성 후 자동 점검을 붙인다: unanswerable에 교사가 거절했는지, answerable에 확정도 어휘를
썼는지. 교사가 못 한 건 학생도 못 배우므로 여기서 걸러야 한다.

사용:
  python gen_chat_teacher.py --model /data/tta/EXAONE/exaone-4.0.1-32B-local \
      --jobs chat_sft_jobs.jsonl --out ../runs/chat_teacher.jsonl --tp 2
"""
import argparse
import json
import time

from vllm import LLM, SamplingParams

REFUSAL_MARKERS = ("없어요", "없습니다", "찾을 수 없", "확인할 수 없", "담겨 있지 않")
GRADE_MARKERS = ("확정", "논의", "예정", "검토")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--jobs", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tp", type=int, default=2)
    ap.add_argument("--max-new-tokens", type=int, default=400)
    args = ap.parse_args()

    jobs = [json.loads(l) for l in open(args.jobs)]
    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=16384)
    tok = llm.get_tokenizer()
    prompts = [tok.apply_chat_template(
        [{"role": "system", "content": j["system"]}, {"role": "user", "content": j["user"]}],
        tokenize=False, add_generation_prompt=True, enable_thinking=False) for j in jobs]

    started = time.time()
    outs = llm.generate(prompts, SamplingParams(temperature=0.3, top_p=0.9,
                                                max_tokens=args.max_new_tokens))
    elapsed = time.time() - started

    kept = dropped = 0
    refused_ok = refused_bad = graded = 0
    with open(args.out, "w") as f:
        for j, o in zip(jobs, outs):
            answer = o.outputs[0].text.strip()
            if not answer:
                dropped += 1
                continue
            refused = any(m in answer for m in REFUSAL_MARKERS)
            if j["label"] == "unanswerable":
                # 교사가 거절 못 한 건 학습셋에서 뺀다 — 지어내는 법을 가르치게 된다.
                if not refused:
                    refused_bad += 1
                    dropped += 1
                    continue
                refused_ok += 1
            else:
                if any(m in answer for m in GRADE_MARKERS):
                    graded += 1
            f.write(json.dumps({
                "label": j["label"], "question": j["question"],
                "prompt": [{"role": "system", "content": j["system"]},
                           {"role": "user", "content": j["user"]}],
                "completion": [{"role": "assistant", "content": answer}],
            }, ensure_ascii=False) + "\n")
            kept += 1

    n_unans = sum(1 for j in jobs if j["label"] == "unanswerable")
    n_ans = len(jobs) - n_unans
    print(f"[done] {elapsed:.0f}s · 채택 {kept} · 제외 {dropped}")
    print(f"[거절] 답없음 {n_unans}건 중 교사 거절 {refused_ok} "
          f"(실패 {refused_bad} — 학습셋에서 제외)")
    print(f"[확정도] 답있음 {n_ans}건 중 확정도 어휘 사용 {graded} "
          f"({graded/max(n_ans,1):.0%})")


if __name__ == "__main__":
    main()
