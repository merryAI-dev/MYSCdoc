#!/usr/bin/env python3
"""
어휘 간극 질문 합성 — 쿼리 재작성 RL에 학습 여지를 만드는 어려운 질문 생성 (H5 대응).

배경: topic 기반 known-item 질문은 naive 검색이 이미 hit@1 92.9%로 포화 — 학습할 게 없다.
실사용자는 노드 라벨의 어휘로 묻지 않으므로, 32B에게 "결정 내용을 노드 어휘 없이 구어체로
묻는 질문"을 만들게 한다. RL-QR의 index-aligned 합성: 골드는 구성상 알고 있고,
질문만 어휘적으로 멀어진다.

생성 후 로컬에서 재감사한다(H3·H4): naive로 못 찾으면서(여지) 골드 텍스트로는 찾을 수
있는(해결가능) 질문만 채택.

사용(GPU 서버, mydoc-kg env):
  python gen_hard_questions.py --model /data/tta/EXAONE/exaone-4.0.1-32B-local \
      --nodes rl_nodes.jsonl --out rl_hard_questions.jsonl --tp 2
"""
import argparse
import json
import time

from chunking import parse_json_block
from vllm import LLM, SamplingParams
from vllm.sampling_params import StructuredOutputsParams

SYSTEM = """당신은 사내 지식 검색 시스템의 테스트 질문을 만드는 사람입니다.
회사 동료가 이 결정을 기억이 가물가물한 상태로 찾으려 할 때 던질 법한 질문을 만드세요.

규칙:
- 반드시 구어체 질문 1문장.
- 【금지 단어】 목록의 단어를 하나도 쓰지 마세요. 그 개념을 다른 말로 돌려 표현하세요.
- 결정된 값(답)도 질문에 넣지 마세요 — 그걸 찾으려고 묻는 질문입니다.
- 상황·맥락(어떤 일 하다가, 누구랑, 왜)을 한 조각 넣어 자연스럽게.
서로 다른 스타일의 질문 3개를 JSON으로만 출력하세요."""

SCHEMA = {"type": "object",
          "properties": {"questions": {"type": "array",
                                       "items": {"type": "string"},
                                       "minItems": 3, "maxItems": 3}},
          "required": ["questions"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--nodes", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tp", type=int, default=2)
    args = ap.parse_args()

    nodes = [json.loads(l) for l in open(args.nodes)]
    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=8192)
    tok = llm.get_tokenizer()

    prompts = []
    for n in nodes:
        user = (f"결정 내용: {n['statement']}\n"
                f"결정된 값: {n['outcome']}\n"
                f"【금지 단어】: {', '.join(n['ban_words'])}\n\n"
                f"이 결정을 찾으려는 동료의 질문 3개를 만드세요.")
        prompts.append(tok.apply_chat_template(
            [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
            tokenize=False, add_generation_prompt=True))

    sampling = SamplingParams(temperature=0.8, top_p=0.95, max_tokens=512)
    sampling.structured_outputs = StructuredOutputsParams(json=SCHEMA)
    started = time.time()
    outputs = llm.generate(prompts, sampling)

    ok = fail = 0
    with open(args.out, "w") as f:
        for n, o in zip(nodes, outputs):
            parsed, _ = parse_json_block(o.outputs[0].text)
            if not parsed:
                fail += 1
                continue
            ok += 1
            f.write(json.dumps({"doc_id": n["doc_id"], "subject": n["subject"],
                                "questions": parsed["questions"]},
                               ensure_ascii=False) + "\n")
    print(f"[done] {ok}/{len(nodes)} 노드 · 실패 {fail} · {time.time()-started:.0f}s")


if __name__ == "__main__":
    main()
