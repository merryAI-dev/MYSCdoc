#!/usr/bin/env python3
"""Build a fixed 30-question actual/irrelevant/counterfactual Slack QA eval."""
import argparse
import hashlib
import json
from pathlib import Path
import re

from build_v5_corpora import SYSTEM, distractors, stable


SLACK_ID = re.compile(r"<?@?U[A-Z0-9]{8,}>?")
NUMBER = re.compile(r"(?<![A-Za-z0-9])\d+(?:[.,]\d+)*(?![A-Za-z0-9])")
VALUE_QUESTION = re.compile(
    r"언제|며칠|몇\s*(?:개|명|년|개월|번째|곳|팀|개사|시간)|얼마|규모|기간|"
    r"시각|날짜|비율|퍼센트|몇이에요|몇인가")
RELATIVE_TIME = {"어제": "오늘", "오늘": "내일", "금일": "내일"}


def read_jsonl(path):
    return [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def usable(row):
    return (row.get("kind") == "atomic" and row.get("split") == "selection"
            and VALUE_QUESTION.search(row.get("question", "")))


def sanitize(row):
    return {**row, "entity": SLACK_ID.sub("직원", row.get("entity", "")),
            "question": SLACK_ID.sub("직원", row["question"]),
            "answer": SLACK_ID.sub("직원", row["answer"]),
            "evidence": [SLACK_ID.sub("직원", value) for value in row["evidence"]]}


def make_prompt(question, evidence):
    return [{"role": "system", "content": SYSTEM}, {"role": "user", "content":
            f"업무 기록:\n[C1] {evidence}\n\n질문: {question}"}]


def choose_value(row):
    question, answer, evidence = row["question"], row["answer"], " ".join(row["evidence"])
    candidates = [value for value in NUMBER.findall(answer)
                  if value in evidence and value not in NUMBER.findall(question)]
    if "퍼센트" in question:
        candidates.sort(key=lambda value: f"{value}%" not in answer)
    elif re.search(r"몇\s*(?:개|명|개사|팀)", question):
        candidates.sort(key=lambda value: not re.search(
            rf"{re.escape(value)}\s*(?:개|명|개사|팀)", answer))
    elif "언제" in question and re.search(r"\d+월\s*\d+일", answer):
        candidates.sort(key=lambda value: not re.search(rf"{re.escape(value)}일", answer))
    if candidates:
        return candidates[0]
    return next((value for value in RELATIVE_TIME if value in answer and value in evidence), None)


def changed_value(value, text):
    if value in RELATIVE_TIME:
        return RELATIVE_TIME[value]
    number = float(value.replace(",", ""))
    suffix = text[text.find(value) + len(value):][:3]
    if suffix.startswith("월"):
        changed = int(number) % 12 + 1
    elif suffix.startswith("일"):
        changed = int(number) + 7 if number <= 21 else int(number) - 7
    elif suffix.startswith("년") and number >= 1900:
        changed = int(number) + 1
    elif "%" in suffix:
        changed = min(int(number) + 5, 95)
    elif "," in value:
        changed = int(number) + max(100, int(number) // 5)
    else:
        changed = int(number) + 1
    return f"{changed:,}" if "," in value else str(changed)


def rewrite(row):
    old = choose_value(row)
    if not old:
        return None
    new = changed_value(old, row["answer"])
    pattern = re.compile(rf"(?<![A-Za-z0-9]){re.escape(old)}(?![A-Za-z0-9])")
    return {"counterfactual_evidence": pattern.sub(new, " / ".join(row["evidence"])),
            "counterfactual_answer": pattern.sub(new, row["answer"]),
            "changed_from": old, "changed_to": new}


def valid_rewrite(row, value):
    if not isinstance(value, dict):
        return False
    evidence = " ".join(value.get("counterfactual_evidence", "").split())
    answer = " ".join(value.get("counterfactual_answer", "").split())
    original_evidence = " ".join(" ".join(row["evidence"]).split())
    return bool(evidence and answer and evidence != original_evidence
                and answer.casefold() != row["answer"].casefold()
                and not SLACK_ID.search(evidence + " " + answer))


def build_cases(rows, rewrites, count):
    pool = [row for row in rows if usable(row)]
    cases, accepted = [], []
    for row, rewrite in zip(pool, rewrites):
        if not valid_rewrite(row, rewrite):
            continue
        noise = distractors(row, pool, 1)
        if not noise:
            continue
        accepted.append(row)
        triplet_id = digest([row["source_key"], row["question"]])[:16]
        variants = [
            ("actual", " / ".join(row["evidence"]), row["answer"], "answer"),
            ("irrelevant", " / ".join(noise[0]["evidence"]), None, "refuse"),
            ("counterfactual", rewrite["counterfactual_evidence"],
             rewrite["counterfactual_answer"], "answer"),
        ]
        for variant, evidence, expected, action in variants:
            cases.append({"label": f"context_{variant}", "question": row["question"],
                          "prompt": make_prompt(row["question"], evidence),
                          "meta": {"task": "context_ablation", "variant": variant,
                                   "triplet_id": triplet_id, "source_key": row["source_key"],
                                   "expected_action": action, "expected_answer": expected,
                                   "oracle_context_ids": ["C1"] if action == "answer" else []}})
        if len(accepted) == count:
            break
    if len(accepted) != count:
        raise SystemExit(f"only {len(accepted)}/{count} valid counterfactual rewrites")
    return cases, accepted


def self_check():
    row = {"kind": "atomic", "split": "selection", "entity": "행사",
           "question": "행사는 언제예요?", "answer": "행사는 3일이에요.",
           "evidence": ["행사는 3일에 열려요."], "source_key": "c:1"}
    assert usable(row)
    value = rewrite(row)
    assert value["changed_from"] == "3" and value["changed_to"] == "10"
    assert valid_rewrite(row, value)
    assert not valid_rewrite(row, {"counterfactual_evidence": row["evidence"][0],
                                   "counterfactual_answer": row["answer"]})
    print("SELF_CHECK_OK")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--teacher")
    parser.add_argument("--out")
    parser.add_argument("--manifest")
    parser.add_argument("--count", type=int, default=30)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return
    if not all((args.teacher, args.out, args.manifest)):
        parser.error("--teacher, --out and --manifest are required")
    out = Path(args.out)
    if out.exists():
        raise SystemExit(f"refusing to overwrite {out}")
    rows = stable([sanitize(row) for row in read_jsonl(args.teacher) if usable(row)],
                  "context-ablation")
    rewrites = [rewrite(row) for row in rows]
    cases, accepted = build_cases(rows, rewrites, args.count)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in cases))
    manifest = {"protocol": "v6-context-ablation-v4", "questions": len(accepted),
                "cases": len(cases), "variants": ["actual", "irrelevant", "counterfactual"],
                "source_split": "selection", "rewrite": "local-explicit-value-v1",
                "teacher_sha256": hashlib.sha256(Path(args.teacher).read_bytes()).hexdigest(),
                "output_sha256": hashlib.sha256(out.read_bytes()).hexdigest()}
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
