#!/usr/bin/env python3
"""Generate source-grounded Korean QA candidates with the local 32B teacher."""
import argparse
import json
import re
import time
from collections import Counter
from pathlib import Path


SYSTEM = """당신은 사내 업무 대화를 자연스러운 한국어 QA 학습자료로 바꾸는 교사입니다.
원문에 직접 적힌 사실만 사용하세요. 각 후보의 entity는 근거 줄에 그대로 등장해야 하고,
answer의 회사명·인명·기관명·날짜·수치는 선택한 근거 줄에서만 가져와야 합니다.
evidence_ids에는 원문 앞의 L01, L02 같은 ID를 그대로 넣으세요. entity는 질문과 근거 줄에
똑같은 문자열로 등장해야 합니다.

한 스레드에서 다음을 최대 4개 만드세요.
- atomic 2~3개: 한두 근거 줄로 직접 답할 수 있는 구체적인 질문
- briefing 0~1개: 같은 대상의 근거 두 줄 이상을 묶어 근황·핵심 내용을 자연스럽게 요약

단순 인사, 감탄, 개인정보, 근거 없는 추측은 제외하세요. 질문은 실제 동료가 챗봇에 묻는
짧고 자연스러운 표현으로, 답은 해요체 1~4문장으로 쓰세요. 라벨 이름을 답에 노출하지 마세요.
commitment는 confirmed/planned/discussed/conditional/unknown 중 하나,
salience는 core/supporting/peripheral 중 하나입니다."""

QA = {
    "type": "object",
    "properties": {
        "kind": {"type": "string", "enum": ["atomic", "briefing"]},
        "entity": {"type": "string"},
        "question": {"type": "string"},
        "answer": {"type": "string"},
        "evidence_ids": {"type": "array", "items": {"type": "string",
            "enum": [f"L{i:02d}" for i in range(1, 65)]}},
        "commitment": {"type": "string", "enum": [
            "confirmed", "planned", "discussed", "conditional", "unknown"]},
        "salience": {"type": "string", "enum": ["core", "supporting", "peripheral"]},
    },
    "required": ["kind", "entity", "question", "answer", "evidence_ids",
                 "commitment", "salience"],
}
SCHEMA = {"type": "object", "properties": {
    "qas": {"type": "array", "items": QA, "maxItems": 4}}, "required": ["qas"]}
DIGIT = re.compile(r"\d+(?:[.,]\d+)*")
SLACK_USER = re.compile(r"^U[A-Z0-9]{8,}$")


def valid_qa(qa, lines):
    try:
        raw_ids = list(dict.fromkeys(str(value) for value in qa["evidence_ids"]))
        ids = [int(value.removeprefix("L")) for value in raw_ids]
    except (KeyError, TypeError, ValueError):
        return None, "bad_evidence"
    if not ids or any(value < 1 or value > len(lines) for value in ids):
        return None, "bad_evidence"
    if qa["kind"] == "atomic" and len(ids) > 2:
        return None, "atomic_too_wide"
    if qa["kind"] == "briefing" and len(ids) < 2:
        return None, "briefing_too_narrow"
    evidence = [lines[value - 1] for value in ids]
    joined = " ".join(evidence)
    entity = qa["entity"].strip()
    question, answer = qa["question"].strip(), qa["answer"].strip()
    if (len(entity) < 2 or entity not in joined or SLACK_USER.fullmatch(entity)
            or not re.search(r"[가-힣]", entity)):
        return None, "bad_entity"
    if len(question) < 4 or len(answer) < 8 or entity not in question:
        return None, "bad_text"
    if not set(DIGIT.findall(answer)).issubset(set(DIGIT.findall(joined))):
        return None, "unsupported_number"
    return {**qa, "entity": entity, "question": question, "answer": answer,
            "evidence_ids": ids, "evidence": evidence}, None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--jobs", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--tp", type=int, default=2)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        good = {"kind": "atomic", "entity": "알타바", "question": "알타바 근황은?",
                "answer": "알타바 설명회를 열기로 했어요.", "evidence_ids": ["L01"],
                "commitment": "confirmed", "salience": "core"}
        assert valid_qa(good, ["알타바 설명회를 열기로 했어요."])[0]
        bad = {**good, "answer": "2027년에 열기로 했어요."}
        assert valid_qa(bad, ["알타바 설명회를 열기로 했어요."])[1] == "unsupported_number"
        print("SELF_CHECK_OK")
        return

    from vllm import LLM, SamplingParams
    from vllm.sampling_params import StructuredOutputsParams

    jobs = [json.loads(line) for line in open(args.jobs) if line.strip()]
    if args.limit:
        jobs = jobs[:args.limit]
    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=32768)
    tokenizer = llm.get_tokenizer()
    prompts = [tokenizer.apply_chat_template(
        [{"role": "system", "content": SYSTEM}, {"role": "user", "content": job["user"]}],
        tokenize=False, add_generation_prompt=True, enable_thinking=False) for job in jobs]
    sampling = SamplingParams(temperature=0.0, max_tokens=1200)
    sampling.structured_outputs = StructuredOutputsParams(json=SCHEMA)
    started = time.time()
    outputs = llm.generate(prompts, sampling)

    accepted, rejected, rejected_examples = [], Counter(), []
    per_kind, per_split = Counter(), Counter()
    for job, output in zip(jobs, outputs):
        try:
            parsed = json.loads(output.outputs[0].text)
        except json.JSONDecodeError:
            rejected["json"] += 1
            continue
        seen = set()
        for qa in parsed.get("qas", []):
            row, reason = valid_qa(qa, job["lines"])
            if reason:
                rejected[reason] += 1
                if len(rejected_examples) < 20:
                    rejected_examples.append({"source_key": job["source_key"],
                                              "reason": reason, "qa": qa})
                continue
            key = row["question"].casefold()
            if key in seen:
                rejected["duplicate_question"] += 1
                continue
            seen.add(key)
            record = {**row, "source_key": job["source_key"], "split": job["split"],
                      "channel_id": job["channel_id"], "thread_ts": job["thread_ts"]}
            accepted.append(record)
            per_kind[row["kind"]] += 1
            per_split[job["split"]] += 1

    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in accepted))
    manifest = {"protocol": "v5-32b-teacher-v1", "jobs": len(jobs),
                "accepted": len(accepted), "rejected": dict(rejected),
                "rejected_examples": rejected_examples,
                "by_kind": dict(per_kind), "by_split": dict(per_split),
                "elapsed_seconds": time.time() - started}
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"[teacher] {len(jobs)} jobs → {len(accepted)} QA · {dict(per_kind)}")
    print(f"[reject] {dict(rejected)} · {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
