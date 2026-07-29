#!/usr/bin/env python3
"""Score Gemini and LoRA weight outputs against an independent salience key."""
import argparse
import json
import math
import random
import re
from pathlib import Path


SCORE = {"core": 3, "supporting": 2, "peripheral": 1}


def parse_answer(text, valid_ids):
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip(), flags=re.I)
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.S)
        if not match:
            return None
        try:
            payload = json.loads(match.group())
        except json.JSONDecodeError:
            return None
    output = {}
    for item in payload.get("weights", []):
        item_id, label = item.get("id"), item.get("salience")
        if item_id in valid_ids and label in SCORE:
            output[item_id] = SCORE[label]
    return output if set(output) == set(valid_ids) else None


def group_metrics(gold, predicted):
    ids = sorted(gold)
    pairs, hits = 0, 0.0
    for i, left in enumerate(ids):
        for right in ids[i + 1:]:
            if gold[left] == gold[right]:
                continue
            pairs += 1
            gd = gold[left] - gold[right]
            pd = predicted[left] - predicted[right]
            hits += 1.0 if gd * pd > 0 else 0.5 if pd == 0 else 0.0
    gold_top = {item for item in ids if gold[item] == max(gold.values())}
    pred_top = {item for item in ids if predicted[item] == max(predicted.values())}
    top1 = len(gold_top & pred_top) / len(pred_top)
    dcg, rank = 0.0, 0
    for score in sorted(set(predicted.values()), reverse=True):
        tied = [item for item in ids if predicted[item] == score]
        discounts = [1 / math.log2(position + 2)
                     for position in range(rank, rank + len(tied))]
        dcg += sum(2 ** gold[item] - 1 for item in tied) * sum(discounts) / len(tied)
        rank += len(tied)
    ideal = sorted(ids, key=lambda item: (-gold[item], item))
    idcg = sum((2 ** gold[item] - 1) / math.log2(rank + 2)
               for rank, item in enumerate(ideal))
    return {"pair_hits": hits, "pair_total": pairs, "top1": top1, "ndcg": dcg / idcg}


def failed_group_metrics(gold):
    values = list(gold.values())
    pairs = sum(values[i] != values[j] for i in range(len(values))
                for j in range(i + 1, len(values)))
    return {"pair_hits": 0.0, "pair_total": pairs, "top1": 0.0, "ndcg": 0.0}


def summarize(groups):
    pair_total = sum(group["pair_total"] for group in groups)
    return {"groups": len(groups),
            "pair_accuracy": sum(group["pair_hits"] for group in groups) / pair_total if pair_total else None,
            "top1": sum(group["top1"] for group in groups) / len(groups) if groups else None,
            "ndcg": sum(group["ndcg"] for group in groups) / len(groups) if groups else None}


def bootstrap_delta(candidate, baseline, seed=20260729, samples=10000):
    rng, n = random.Random(seed), len(candidate)
    deltas = []
    for _ in range(samples):
        indices = [rng.randrange(n) for _ in range(n)]
        cand = summarize([candidate[i] for i in indices])["pair_accuracy"]
        base = summarize([baseline[i] for i in indices])["pair_accuracy"]
        deltas.append(cand - base)
    deltas.sort()
    return {"delta": summarize(candidate)["pair_accuracy"] - summarize(baseline)["pair_accuracy"],
            "ci95": [deltas[int(samples * 0.025)], deltas[int(samples * 0.975)]]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold")
    parser.add_argument("--answers", nargs="+")
    parser.add_argument("--baseline", default="gemini")
    parser.add_argument("--out")
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        gold = {"F01": 3, "F02": 1}
        assert parse_answer('{"weights":[{"id":"F01","salience":"core","weight":1},'
                            '{"id":"F02","salience":"peripheral","weight":0.2}]}', gold) == gold
        assert group_metrics(gold, gold)["pair_hits"] == 1
        tied = group_metrics(gold, {"F01": 3, "F02": 3})
        assert tied["top1"] == 0.5 and tied["ndcg"] < 1
        assert failed_group_metrics(gold) == {
            "pair_hits": 0.0, "pair_total": 1, "top1": 0.0, "ndcg": 0.0}
        print("SELF_CHECK_OK")
        return
    if not args.gold or not args.answers or not args.out:
        parser.error("--gold, --answers and --out are required")

    gold_rows = [json.loads(line) for line in open(args.gold, encoding="utf-8") if line.strip()]
    gold = {}
    for row in gold_rows:
        labels = {item["id"]: SCORE[item["salience"]] for item in row["judgments"]}
        if row["weight_group_id"] in gold or len(labels) != len(row["judgments"]):
            raise SystemExit("duplicate gold group or item id")
        if len(set(labels.values())) < 2:
            raise SystemExit(f"gold group has no ranking signal: {row['weight_group_id']}")
        gold[row["weight_group_id"]] = labels
    models = {}
    for path in args.answers:
        models.update(json.load(open(path, encoding="utf-8")))
    by_model, failures = {}, {}
    for name, rows in models.items():
        scored, failed = [], 0
        indexed = {row["meta"]["weight_group_id"]: row for row in rows
                   if row.get("meta", {}).get("task") == "weight"}
        for group_id, labels in gold.items():
            row = indexed.get(group_id)
            prediction = parse_answer(row.get("answer", ""), labels) if row else None
            if prediction is None:
                failed += 1
                scored.append(failed_group_metrics(labels))
            else:
                scored.append(group_metrics(labels, prediction))
        by_model[name], failures[name] = scored, failed
    if args.baseline not in by_model:
        raise SystemExit(f"baseline {args.baseline!r} not found")
    result = {"gold_groups": len(gold), "metrics": {}, "parse_failures": failures,
              "baseline": args.baseline, "paired_vs_baseline": {}}
    for name, groups in by_model.items():
        result["metrics"][name] = summarize(groups)
        if name != args.baseline:
            result["paired_vs_baseline"][name] = bootstrap_delta(groups, by_model[args.baseline])
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
