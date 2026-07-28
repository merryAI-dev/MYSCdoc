#!/usr/bin/env python3
"""
C — 32B로 결정의 '의미론적 무게'를 판정한다. 노드 가중치 증류의 교사 데이터.

지금 그래프는 모든 결정이 같은 무게다. "확정했다"와 "하자고 제안했다"와 "해야 한다"가
동급으로 검색된다. 어미 규칙만으로는 못 가른다 — 표지가 있어도 비결정("결정 기준을
물어봤어요")이 있고, 표지가 없어도 확정("제외하는 기조로 결정해요")이 있다. 판단·추론·
표현 정규화가 필요한 분류라 32B가 교사가 된다.

등급 (commitment): 확정 > 조건부확정 > 의지예정 > 제안검토 > 당위 > 비결정
중요도 (salience): core / supporting / peripheral — 그 회의 안에서의 위상

학습 포인트 검증(핵심): 이 판정이 '의미' 분류인지 '표면 어미 매칭'인지는 로컬에서
어미 규칙 베이스라인과 대조해 확인한다. 32B가 규칙과 갈리는 사례가 곧 의미 판단의 증거다.

사용:
  python judge_weights.py --model /data/tta/EXAONE/exaone-4.0.1-32B-local \
      --jobs judge_jobs.jsonl --out ../runs/weights_32b.jsonl --tp 2
"""
import argparse
import json
import time

from chunking import parse_json_block
from vllm import LLM, SamplingParams
from vllm.sampling_params import StructuredOutputsParams

MAX_ITEMS = 40
ITEM_IDS = [f"D{i:03d}" for i in range(1, MAX_ITEMS + 1)]

SYSTEM = """당신은 회의록 분석 전문가입니다. 회의록 원문과, 거기서 추출된 결정 후보 목록을 받습니다.
각 후보에 대해 두 가지를 판정하세요.

1. commitment — 그 문장에 실린 결정의 힘. 표현이 아니라 **의미**로 판정하세요:
   - "확정": 팀이 합의했고 실행이 전제됨. "결정"이라는 단어가 없어도 문맥상 확정이면 확정.
   - "조건부확정": 특정 조건이 충족되면 실행하기로 함.
   - "의지예정": 화자/팀의 계획·약속·예정 (아직 합의된 확정은 아님).
   - "제안검토": 제안됐거나 검토 중 — 아직 열려 있음.
   - "당위": "~해야 한다"는 진술. 필요성 표명이지 결정이 아님.
   - "비결정": 서술·질문·감상·정보 공유. "결정"이라는 단어가 있어도 결정 발화가 아니면 여기.

2. salience — 이 회의 안에서의 위상:
   - "core": 이 회의의 핵심 산출물. 나중에 "그때 뭐 정했지?"의 답이 되는 것.
   - "supporting": 핵심을 뒷받침하는 부수 결정 (일정 조율, 담당 배정 등).
   - "peripheral": 지엽적이거나 절차적인 것.

원문에 없는 후보(원문과 관련 없는 내용)는 commitment를 "비결정"으로 두세요.
지정된 JSON으로만 답하세요."""

SCHEMA = {
    "type": "object",
    "properties": {
        "judgments": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "id": {"type": "string", "enum": ITEM_IDS},
                "commitment": {"type": "string",
                               "enum": ["확정", "조건부확정", "의지예정",
                                        "제안검토", "당위", "비결정"]},
                "salience": {"type": "string",
                             "enum": ["core", "supporting", "peripheral"]},
            },
            "required": ["id", "commitment", "salience"]}},
    },
    "required": ["judgments"],
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--jobs", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tp", type=int, default=2)
    ap.add_argument("--max-new-tokens", type=int, default=2048)
    # 32B를 GPU 한 장에 얹을 때: 가중치 60GB를 빼면 KV 캐시가 몇 GB뿐이라 32768은 초기화가
    # 실패한다. 판정 프롬프트는 컨텍스트 6천자 + 목록이라 10k면 넉넉하다.
    ap.add_argument("--max-model-len", type=int, default=32768)
    args = ap.parse_args()

    jobs = [json.loads(l) for l in open(args.jobs)]

    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.92,
              max_model_len=args.max_model_len)
    tok = llm.get_tokenizer()

    prompts = []
    for j in jobs:
        listing = "\n".join(f'{it["id"]}: {it["text"]}' for it in j["items"])
        user = (f"## 회의록 원문\n{j['context']}\n\n## 결정 후보 목록\n{listing}\n\n"
                f"각 후보의 commitment와 salience를 판정하세요.")
        prompts.append(tok.apply_chat_template(
            [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
            tokenize=False, add_generation_prompt=True))

    sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens)
    sampling.structured_outputs = StructuredOutputsParams(json=SCHEMA)

    started = time.time()
    outputs = llm.generate(prompts, sampling)
    elapsed = time.time() - started

    ok = fail = judged = 0
    with open(args.out, "w") as out:
        for j, o in zip(jobs, outputs):
            parsed, _ = parse_json_block(o.outputs[0].text)
            if parsed is None:
                fail += 1
                continue
            ok += 1
            by_id = {x["id"]: x for x in parsed.get("judgments", [])}
            judged += len(by_id)
            out.write(json.dumps({
                "doc_id": j["doc_id"],
                "judgments": [{"id": it["id"], "text": it["text"],
                               **({k: by_id[it["id"]][k] for k in ("commitment", "salience")}
                                  if it["id"] in by_id else {"commitment": None, "salience": None})}
                              for it in j["items"]],
                **({"human": j["human"]} if "human" in j else {}),
            }, ensure_ascii=False) + "\n")

    print(f"[done] 문서 {ok}/{len(jobs)} · 판정 {judged}건 · 실패 {fail} · {elapsed:.0f}s")


if __name__ == "__main__":
    main()
