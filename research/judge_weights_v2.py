#!/usr/bin/env python3
"""
등급 판정 v2 — 조건부를 별도 축으로 분리한다.

v1은 확정/조건부/예정/논의를 한 줄에 세웠는데 축이 섞여 있었다
(docs/RESEARCH-grade-conditioning.md §3):
  · 확정 ↔ 논의 = 사실성 축
  · 확정 ↔ 예정 = 실현성 축
  · 조건부 = '-(으)면' 류 조건 연결어미로 실현성을 유예하는 연산자 — 척도 위의 점이 아니다
문헌도 일치한다. FactBank는 조건문을 두 명제로 쪼개고 조건 표지를 아예 삭제하며(§3.3.1),
ISO 24617-2는 certainty와 conditionality를 별개 qualifier로 둔다.

실무적 손해가 실제로 있었다. v1에서 '조건부' 15%가 "확정인데 조건이 붙음"인지 "아직
안 정해짐"인지 구분되지 않았다. 분리하면 '조건부 확정'과 '조건부 예정'이 갈린다.

출력 축:
  commitment  확정 | 예정 | 논의 | 당위 | 비결정   (조건부 없음 — 아래로 분리)
  conditional true | false                        (조건 충족 시에만 성립하는가)
  condition   조건 내용 (conditional=true일 때만)
  salience    core | supporting | peripheral

v1 결과와 대조 가능하도록 같은 판정 대상(judge_jobs.jsonl)을 쓴다. 두 판을 비교하면
'조건부'로 뭉쳐 있던 것이 어느 등급으로 흩어지는지가 그대로 드러난다.

사용:
  python judge_weights_v2.py --model /data/tta/EXAONE/exaone-4.0.1-32B-local \
      --jobs judge_jobs.jsonl --out ../runs/weights_v2.jsonl --tp 2
"""
import argparse
import json
import time

from chunking import parse_json_block
from vllm import LLM, SamplingParams
from vllm.sampling_params import StructuredOutputsParams

MAX_ITEMS = 40
ITEM_IDS = [f"D{i:03d}" for i in range(1, MAX_ITEMS + 1)]

SYSTEM = """당신은 회의록 분석 전문가입니다. 회의록 원문과 거기서 추출된 결정 후보 목록을
받습니다. 각 후보를 아래 세 가지로 판정하세요. 표현이 아니라 **의미**로 판단하세요.

1. commitment — 결정의 힘. 조건이 붙었는지는 여기서 따지지 마세요(2번에서 따로 답합니다).
   - "확정": 팀이 합의했고 실행이 전제됨. '결정'이라는 단어가 없어도 문맥상 확정이면 확정.
   - "예정": 계획·의지·약속 단계. 아직 합의된 확정은 아니지만 실행 방향이 정해짐.
   - "논의": 제안됐거나 검토 중 — 아직 열려 있음.
   - "당위": "~해야 한다"는 필요성 표명. 결정이 아님.
   - "비결정": 서술·질문·감상·정보 공유. '결정'이라는 단어가 있어도 결정 발화가 아니면 여기.

2. conditional — 특정 조건이 충족되어야만 성립하는 결정입니까?
   "~하면", "~할 경우", "~를 전제로" 같은 조건이 결정의 성립 자체를 좌우하면 true.
   단순한 시점·담당자 언급은 조건이 아닙니다.
   ※ 조건이 붙어도 commitment는 따로 판단하세요.
      예) "예산이 승인되면 3월에 착수하기로 확정했다" → commitment=확정, conditional=true
      예) "예산이 승인되면 검토해 보자" → commitment=논의, conditional=true

3. condition — conditional=true이면 그 조건을 짧은 구로. false이면 빈 문자열.

4. salience — 이 회의 안에서의 위상.
   "core": 이 회의의 핵심 산출물. 나중에 "그때 뭐 정했지?"의 답이 되는 것.
   "supporting": 핵심을 뒷받침하는 부수 결정(일정 조율, 담당 배정 등).
   "peripheral": 지엽적이거나 절차적인 것.

지정된 JSON으로만 답하세요."""

SCHEMA = {
    "type": "object",
    "properties": {
        "judgments": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "id": {"type": "string", "enum": ITEM_IDS},
                "commitment": {"type": "string",
                               "enum": ["확정", "예정", "논의", "당위", "비결정"]},
                "conditional": {"type": "boolean"},
                "condition": {"type": "string"},
                "salience": {"type": "string",
                             "enum": ["core", "supporting", "peripheral"]},
            },
            "required": ["id", "commitment", "conditional", "condition", "salience"]}},
    },
    "required": ["judgments"],
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--jobs", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tp", type=int, default=2)
    ap.add_argument("--max-new-tokens", type=int, default=2560)
    ap.add_argument("--max-model-len", type=int, default=32768)
    args = ap.parse_args()

    jobs = [json.loads(l) for l in open(args.jobs)]
    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              # 0.85: GPU0에 A2A 운영 스코어러(약 3.8GB)가 상주한다. 0.92로 잡으면
              # 그 몫과 부딪혀 OOM으로 죽는다(실제로 겪음). 공용 장비라 여유를 둔다.
              dtype="bfloat16", gpu_memory_utilization=0.85,
              max_model_len=args.max_model_len)
    tok = llm.get_tokenizer()

    prompts = []
    for j in jobs:
        listing = "\n".join(f'{it["id"]}: {it["text"]}' for it in j["items"])
        prompts.append(tok.apply_chat_template(
            [{"role": "system", "content": SYSTEM},
             {"role": "user", "content": f"## 회의록 원문\n{j['context']}\n\n"
                                         f"## 결정 후보 목록\n{listing}\n\n"
                                         f"각 후보를 판정하세요."}],
            tokenize=False, add_generation_prompt=True, enable_thinking=False))

    sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens)
    sampling.structured_outputs = StructuredOutputsParams(json=SCHEMA)

    started = time.time()
    outputs = llm.generate(prompts, sampling)
    elapsed = time.time() - started

    from collections import Counter
    commit_dist, cond_count, judged = Counter(), 0, 0
    ok = fail = 0
    with open(args.out, "w") as out:
        for j, o in zip(jobs, outputs):
            parsed, _ = parse_json_block(o.outputs[0].text)
            if parsed is None:
                fail += 1
                continue
            ok += 1
            by_id = {x["id"]: x for x in parsed.get("judgments", [])}
            rows = []
            for it in j["items"]:
                v = by_id.get(it["id"])
                if v:
                    judged += 1
                    commit_dist[v["commitment"]] += 1
                    cond_count += bool(v.get("conditional"))
                rows.append({"id": it["id"], "text": it["text"],
                             "commitment": v.get("commitment") if v else None,
                             "conditional": v.get("conditional") if v else None,
                             "condition": v.get("condition", "") if v else "",
                             "salience": v.get("salience") if v else None})
            out.write(json.dumps({"doc_id": j["doc_id"], "judgments": rows,
                                  **({"human": j["human"]} if "human" in j else {})},
                                 ensure_ascii=False) + "\n")

    print(f"[done] 문서 {ok}/{len(jobs)} · 판정 {judged}건 · 실패 {fail} · {elapsed:.0f}s")
    print(f"[commitment] {dict(commit_dist)}")
    print(f"[conditional] true {cond_count}/{judged} ({cond_count/max(judged,1):.1%})")


if __name__ == "__main__":
    main()
