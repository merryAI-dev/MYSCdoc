#!/usr/bin/env python3
"""
챗 답변 교사 데이터 v2 — 확정도를 '설명과 함께' 학습 타깃에 넣고, 어려운 거절을 섞는다.

v1에서 드러난 것과 문헌이 겹친다:
  · v1은 프롬프트에 [확정]/[논의] 라벨만 붙였다. 그런데 32B조차 확정도를 답변에 반영한 건
    66%뿐이었다. AuthorityBench는 소형 모델일수록 프롬프트의 신뢰도 라벨을 못 따른다고
    보고한다 — 1.2B에 그대로 넘기면 실패할 설계다.
    → CAG(EMNLP 2024)의 처방을 따른다: 교사가 '왜 확정으로 보는지'를 근거와 함께 말하게
      하고, 그 설명이 담긴 답변을 학습 타깃으로 쓴다. 라벨을 장식이 아니라 내용으로 만든다.
  · v1의 거절 사례는 그래프에 아예 없는 주제(정수기·주차장)라 너무 쉽다. 학생은 "낯선 주제
    = 거절"이라는 얕은 규칙을 배우고 정작 위험한 경우 — 관련은 있는데 답이 없는 상황 — 은
    여전히 지어낸다. OCC-RAG는 부정 사례를 '구성'했다.
    → build_hard_abstention.py가 만든 어려운 거절(골드만 제거)을 함께 넣는다.

과잉 거절 위험을 문헌이 명시적으로 경고하므로 짝 평가셋으로 양방향을 잰다.

사용:
  python gen_chat_teacher_v2.py --model <32B> --jobs chat_sft_jobs.jsonl \
      --hard hard_abstention_jobs.jsonl --out ../runs/chat_teacher_v2.jsonl --tp 2
"""
import argparse
import json
import time

from vllm import LLM, SamplingParams

REFUSAL_MARKERS = ("없어요", "없습니다", "찾을 수 없", "확인할 수 없", "담겨 있지 않")
GRADE_MARKERS = ("확정", "논의", "예정", "검토", "조건")

# v1과 다른 지점: 확정도를 '답변 안에서 근거와 함께 말하도록' 요구한다.
SYSTEM_V2 = """당신은 사내 지식그래프를 위키처럼 참고해 답하는 어시스턴트입니다.
아래 '지식그래프 사실'만 근거로 답하세요.

각 사실 앞에는 [확정도] 표시가 있습니다. 이것을 답변에 반드시 녹여 말하세요:
  · [확정] — 팀이 합의해 실행이 전제된 것. "~하기로 했어요"처럼 단정합니다.
  · [조건부] — 조건이 붙은 것. 그 조건을 함께 말합니다.
  · [예정] — 계획·의지 단계. "~할 예정이었어요"처럼 여지를 남깁니다.
  · [논의] — 제안·검토 단계로 확정이 아닙니다. "확정된 건 아니고 논의됐어요"라고 분명히 구분합니다.

지켜야 할 것:
- 확정된 것과 논의 중인 것이 섞여 있으면 반드시 둘을 나눠 말하세요. 논의를 확정처럼 말하면 안 됩니다.
- 왜 그렇게 판단했는지가 답변에서 드러나야 합니다 — "합의된 사항이라" / "아직 검토 단계라"처럼
  확정도의 근거를 한 조각 넣으세요. 라벨 이름만 나열하지는 마세요.
- 제공된 사실에 없는 내용은 지어내지 마세요. 추측 금지.
- 사실이 질문을 커버하지 못하면 "지식그래프에 아직 그 내용이 없어요"라고 말하고, 대신
  그래프에 있는 인접 내용이 무엇인지 한 문장으로 알려주세요.
- 한국어 해요체, 2~5문장."""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--jobs", required=True)
    ap.add_argument("--hard", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tp", type=int, default=2)
    ap.add_argument("--max-new-tokens", type=int, default=420)
    args = ap.parse_args()

    jobs = []
    for line in open(args.jobs):
        j = json.loads(line)
        # v1 job의 사실 목록만 재사용하고 시스템 프롬프트는 v2로 교체한다.
        jobs.append({"label": j["label"], "question": j["question"],
                     "system": SYSTEM_V2, "user": j["user"]})
    for line in open(args.hard):
        h = json.loads(line)
        jobs.append({"label": "unanswerable_hard", "question": h["question"],
                     "system": SYSTEM_V2,
                     "user": f"질문: {h['question']}\n\n지식그래프 사실:\n{h['facts']}\n"
                             f"위 사실만 근거로, 각 사실의 확정도를 구분해서 답하세요.\n"})

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

    stats = {}
    kept = dropped = 0
    with open(args.out, "w") as f:
        for j, o in zip(jobs, outs):
            answer = o.outputs[0].text.strip()
            label = j["label"]
            s = stats.setdefault(label, {"n": 0, "refused": 0, "graded": 0, "dropped": 0})
            s["n"] += 1
            if not answer:
                s["dropped"] += 1
                dropped += 1
                continue
            refused = any(m in answer for m in REFUSAL_MARKERS)
            if label.startswith("unanswerable"):
                # 교사가 거절 못 한 건 뺀다 — 지어내는 법을 가르치게 된다.
                if not refused:
                    s["dropped"] += 1
                    dropped += 1
                    continue
                s["refused"] += 1
            else:
                if any(m in answer for m in GRADE_MARKERS):
                    s["graded"] += 1
            f.write(json.dumps({
                "label": label, "question": j["question"],
                "prompt": [{"role": "system", "content": j["system"]},
                           {"role": "user", "content": j["user"]}],
                "completion": [{"role": "assistant", "content": answer}],
            }, ensure_ascii=False) + "\n")
            kept += 1

    print(f"[done] {elapsed:.0f}s · 채택 {kept} · 제외 {dropped}")
    for label, s in sorted(stats.items()):
        extra = (f"거절 {s['refused']}/{s['n']}" if label.startswith("unanswerable")
                 else f"확정도 반영 {s['graded']}/{s['n']} ({s['graded']/max(s['n'],1):.0%})")
        print(f"  {label:22s} n={s['n']:3d} · {extra} · 제외 {s['dropped']}")


if __name__ == "__main__":
    main()
