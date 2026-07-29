#!/usr/bin/env python3
"""Score actual/irrelevant/counterfactual Slack QA without an LLM judge."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re


VALUE = re.compile(r"\d+(?:[.,]\d+)*|어제|오늘|내일|금일")
REFUSAL = re.compile(
    r"근거\s*부족|확인할 수 없|알 수 없|정보가 없|기록(?:에는|에)?\s*(?:없|나와 있지)|"
    r"제공된 기록.{0,12}(?:없|확인되지)|명시되어 있지|찾을 수 없")
SLACK_ID = re.compile(r"<?@?U[A-Z0-9]{8,}>?")


def value_delta(actual, counterfactual):
    left, right = Counter(VALUE.findall(actual)), Counter(VALUE.findall(counterfactual))
    old, new = list((left - right).elements()), list((right - left).elements())
    if len(old) != 1 or len(new) != 1:
        raise ValueError(f"expected one changed value: {old=} {new=}")
    return old[0], new[0], left[old[0]], right[new[0]]


def answer_rows(paths):
    models = {}
    for path in paths:
        models.update(json.load(open(path, encoding="utf-8")))
    return models


def score_model(rows):
    groups = defaultdict(dict)
    for row in rows:
        groups[row["meta"]["triplet_id"]][row["meta"]["variant"]] = row
    counts, details = Counter(), []
    for triplet_id, group in groups.items():
        if set(group) != {"actual", "irrelevant", "counterfactual"}:
            raise ValueError(f"incomplete triplet: {triplet_id}")
        actual, irrelevant, counter = (group[name] for name in
                                       ("actual", "irrelevant", "counterfactual"))
        old, new, old_count, new_count = value_delta(
            actual["meta"]["expected_answer"], counter["meta"]["expected_answer"])
        actual_values = Counter(VALUE.findall(actual["answer"]))
        counter_values = Counter(VALUE.findall(counter["answer"]))
        actual_ok = actual_values[old] >= old_count and not REFUSAL.search(actual["answer"])
        refuse_ok = bool(REFUSAL.search(irrelevant["answer"]))
        counter_ok = counter_values[new] >= new_count and not REFUSAL.search(counter["answer"])
        memorized = counter_values[old] >= old_count and counter_values[new] < new_count
        counts.update({"n": 1, "actual_ok": actual_ok, "irrelevant_refuse": refuse_ok,
                       "counterfactual_ok": counter_ok, "memorized_original": memorized,
                       "triplet_ok": actual_ok and refuse_ok and counter_ok})
        leaked = any(SLACK_ID.search(row["answer"]) or "<abstain>" in row["answer"]
                     for row in group.values())
        counts["leak_triplet"] += leaked
        details.append({"triplet_id": triplet_id, "question": actual["question"],
                        "old": old, "new": new, "actual_ok": actual_ok,
                        "irrelevant_refuse": refuse_ok, "counterfactual_ok": counter_ok,
                        "memorized_original": memorized, "leaked": leaked,
                        "answers": {name: group[name]["answer"] for name in group}})
    n = counts["n"]
    return {"actual_value_accuracy": counts["actual_ok"] / n,
            "irrelevant_refusal_rate": counts["irrelevant_refuse"] / n,
            "counterfactual_value_accuracy": counts["counterfactual_ok"] / n,
            "original_value_memorization_rate": counts["memorized_original"] / n,
            "triplet_accuracy": counts["triplet_ok"] / n,
            "leakage_rate": counts["leak_triplet"] / n,
            "counts": dict(counts)}, details


def self_check():
    assert value_delta("3월 31일", "3월 24일") == ("31", "24", 1, 1)
    assert value_delta("1박2일", "2박2일") == ("1", "2", 1, 2)
    assert REFUSAL.search("제공된 기록에는 정보가 없어요.")
    print("SELF_CHECK_OK")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--answers", nargs="+")
    parser.add_argument("--out")
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return
    if not args.answers or not args.out:
        parser.error("--answers and --out are required")
    report = {}
    for model, rows in answer_rows(args.answers).items():
        metrics, details = score_model(rows)
        report[model] = {"metrics": metrics, "rows": details}
        print(model, json.dumps(metrics, ensure_ascii=False))
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
