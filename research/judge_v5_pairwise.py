#!/usr/bin/env python3
"""Auxiliary 32B pairwise judge with A/B order reversal."""
import argparse
import json
from collections import Counter
from pathlib import Path


SYSTEM = """당신은 사내 QA 응답을 비교하는 엄격한 평가자입니다.
제공된 기록만 근거로 정확성, 질문 적합성, 자연스러운 한국어를 함께 보세요.
기록에 답이 없으면 추측하지 않고 근거 부족이라고 한 응답이 우수합니다.
문체 차이보다 잘못된 대상·수치·상태, 근거 없는 단정, 엉뚱한 거절을 더 크게 벌점 주세요."""
SCHEMA = {"type": "object", "properties": {
    "winner": {"type": "string", "enum": ["A", "B", "tie"]},
    "a_grounded": {"type": "integer", "minimum": 1, "maximum": 5},
    "b_grounded": {"type": "integer", "minimum": 1, "maximum": 5},
    "a_helpful": {"type": "integer", "minimum": 1, "maximum": 5},
    "b_helpful": {"type": "integer", "minimum": 1, "maximum": 5},
    "a_natural": {"type": "integer", "minimum": 1, "maximum": 5},
    "b_natural": {"type": "integer", "minimum": 1, "maximum": 5},
}, "required": ["winner", "a_grounded", "b_grounded", "a_helpful", "b_helpful",
                "a_natural", "b_natural"]}


def normalize_winner(value, candidate_is_a):
    if value == "tie":
        return "tie"
    return "candidate" if (value == "A") == candidate_is_a else "base"


def aggregate(first, second):
    votes = [normalize_winner(first["winner"], False),
             normalize_winner(second["winner"], True)]
    verdict = votes[0] if votes[0] == votes[1] else "disagree"
    candidate_scores = [first[f"b_{key}"] for key in ("grounded", "helpful", "natural")]
    candidate_scores += [second[f"a_{key}"] for key in ("grounded", "helpful", "natural")]
    base_scores = [first[f"a_{key}"] for key in ("grounded", "helpful", "natural")]
    base_scores += [second[f"b_{key}"] for key in ("grounded", "helpful", "natural")]
    return verdict, (sum(candidate_scores) - sum(base_scores)) / len(candidate_scores)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model")
    parser.add_argument("--answers")
    parser.add_argument("--base-label")
    parser.add_argument("--candidate", action="append", default=[])
    parser.add_argument("--out")
    parser.add_argument("--tp", type=int, default=2)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        a = {"winner": "B", "a_grounded": 3, "b_grounded": 5,
             "a_helpful": 3, "b_helpful": 5, "a_natural": 4, "b_natural": 4}
        b = {**a, "winner": "A", "a_grounded": 5, "b_grounded": 3,
             "a_helpful": 5, "b_helpful": 3}
        assert aggregate(a, b)[0] == "candidate"
        print("SELF_CHECK_OK")
        return
    if not all((args.model, args.answers, args.base_label, args.candidate, args.out)):
        parser.error("--model, --answers, --base-label, --candidate, --out are required")

    from vllm import LLM, SamplingParams
    from vllm.sampling_params import StructuredOutputsParams

    models = json.load(open(args.answers))
    base = [row for row in models[args.base_label] if row["meta"]["task"] != "general"]
    prompts, keys = [], []
    for candidate in args.candidate:
        other = [row for row in models[candidate] if row["meta"]["task"] != "general"]
        if len(base) != len(other):
            raise SystemExit(f"row count differs: {candidate}")
        for index, (left, right) in enumerate(zip(base, other)):
            if (left["question"], left["meta"]["task"]) != (right["question"], right["meta"]["task"]):
                raise SystemExit(f"case order differs: {candidate}/{index}")
            task = left["meta"]["task"]
            reference = ("직접 근거가 제거된 사례이므로 근거 부족이 정답" if task == "no_oracle"
                         else left["meta"].get("expected_answer", ""))
            common = (f"평가 대상:\n{left['prompt'][-1]['content']}\n\n"
                      f"참고 정답: {reference}\n")
            for candidate_is_a in (False, True):
                answer_a = right["answer"] if candidate_is_a else left["answer"]
                answer_b = left["answer"] if candidate_is_a else right["answer"]
                prompts.append([{"role": "system", "content": SYSTEM}, {"role": "user",
                    "content": f"{common}\n응답 A: {answer_a}\n\n응답 B: {answer_b}"}])
                keys.append((candidate, index, candidate_is_a))

    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=32768)
    tokenizer = llm.get_tokenizer()
    rendered = [tokenizer.apply_chat_template(value, tokenize=False, add_generation_prompt=True,
                                               enable_thinking=False) for value in prompts]
    sampling = SamplingParams(temperature=0.0, max_tokens=120)
    sampling.structured_outputs = StructuredOutputsParams(json=SCHEMA)
    outputs = [json.loads(value.outputs[0].text) for value in llm.generate(rendered, sampling)]

    grouped = {}
    for key, value in zip(keys, outputs):
        grouped[key] = value
    report = {"protocol": "v5-32b-pairwise-reversed-v1", "base": args.base_label,
              "candidates": {}}
    for candidate in args.candidate:
        rows, counts, deltas = [], Counter(), []
        for index in range(len(base)):
            verdict, delta = aggregate(grouped[(candidate, index, False)],
                                       grouped[(candidate, index, True)])
            counts[verdict] += 1
            deltas.append(delta)
            rows.append({"index": index, "task": base[index]["meta"]["task"],
                         "verdict": verdict, "score_delta": delta})
        report["candidates"][candidate] = {"counts": dict(counts),
            "candidate_win_rate": counts["candidate"] / len(rows),
            "base_win_rate": counts["base"] / len(rows),
            "mean_score_delta": sum(deltas) / len(deltas), "rows": rows}
    path = Path(args.out)
    if path.exists():
        raise SystemExit(f"refusing to overwrite {path}")
    path.write_text(json.dumps(report, ensure_ascii=False, indent=1))
    print(json.dumps({name: {k: v for k, v in result.items() if k != "rows"}
                      for name, result in report["candidates"].items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
