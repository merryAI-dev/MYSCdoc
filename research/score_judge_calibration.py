#!/usr/bin/env python3
"""Score human labels against the blinded 32B-judge calibration key."""
import argparse
import json
from collections import Counter
from pathlib import Path


def kappa(left, right):
    labels = set(left) | set(right)
    observed = sum(a == b for a, b in zip(left, right)) / len(left)
    expected = sum(left.count(x) * right.count(x) for x in labels) / len(left) ** 2
    return (observed - expected) / (1 - expected) if expected < 1 else 1.0


def binary_scores(predicted, actual):
    tp = sum(p and a for p, a in zip(predicted, actual))
    fp = sum(p and not a for p, a in zip(predicted, actual))
    fn = sum(not p and a for p, a in zip(predicted, actual))
    return {"precision": tp / (tp + fp) if tp + fp else 1.0,
            "recall": tp / (tp + fn) if tp + fn else 1.0,
            "tp": tp, "fp": fp, "fn": fn}


def error_rates(rows, response_key):
    neg = [r for r in rows if r["source_label"].startswith("unanswerable")]
    pos = [r for r in rows if not r["source_label"].startswith("unanswerable")]
    far = sum(r[response_key] == "답변" for r in neg) / len(neg) if neg else 0.0
    frr = sum(r[response_key] == "거절" for r in pos) / len(pos) if pos else 0.0
    return {"far": far, "frr": frr, "n_neg": len(neg), "n_pos": len(pos)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", required=True)
    ap.add_argument("--human", required=True)
    ap.add_argument("--out")
    args = ap.parse_args()
    key = json.loads(Path(args.key).read_text())
    human = {row["audit_id"]: row for row in json.loads(Path(args.human).read_text())}
    rows = []
    for expected in key["rows"]:
        got = human.get(expected["audit_id"], {})
        if got.get("response") not in ("거절", "부분답변", "답변"):
            raise SystemExit(f"incomplete human label: {expected['audit_id']}")
        if got.get("fabricated") not in ("예", "아니오") or got.get("grade_reflected") not in ("예", "아니오"):
            raise SystemExit(f"incomplete boolean label: {expected['audit_id']}")
        rows.append({**expected, "human_response": got["response"],
                     "human_fabricated": got["fabricated"] == "예",
                     "human_grade_reflected": got["grade_reflected"] == "예"})
    judge_response = [r["judge_response"] for r in rows]
    human_response = [r["human_response"] for r in rows]
    judge_rates, human_rates = error_rates(rows, "judge_response"), error_rates(rows, "human_response")
    deltas = {name: judge_rates[name] - human_rates[name] for name in ("far", "frr")}
    report = {
        "n": len(rows), "response_accuracy": sum(a == b for a, b in zip(judge_response, human_response)) / len(rows),
        "response_kappa": kappa(judge_response, human_response),
        "response_confusion": {f"{a}->{b}": n for (a, b), n in sorted(Counter(zip(judge_response, human_response)).items())},
        "fabricated": binary_scores([r["judge_fabricated"] for r in rows], [r["human_fabricated"] for r in rows]),
        "grade_reflected": binary_scores([r["judge_grade_reflected"] for r in rows], [r["human_grade_reflected"] for r in rows]),
        "judge_error_rates": judge_rates, "human_error_rates": human_rates, "error_rate_delta": deltas,
    }
    report["gate_pass"] = report["response_kappa"] >= 0.70 and max(map(abs, deltas.values())) <= 0.03
    print(f"response accuracy={report['response_accuracy']:.1%} kappa={report['response_kappa']:.3f}")
    print(f"FAR delta={deltas['far']:+.1%}p FRR delta={deltas['frr']:+.1%}p")
    print(f"fabricated P/R={report['fabricated']['precision']:.1%}/{report['fabricated']['recall']:.1%}")
    print("GATE_PASS" if report["gate_pass"] else "GATE_FAIL_REJUDGE_REQUIRED")
    if args.out:
        Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
