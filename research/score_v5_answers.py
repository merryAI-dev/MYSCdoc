#!/usr/bin/env python3
"""Deterministic V5 grounding, abstention, pair and Korean IFEval-style scorer."""
import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path


CITATION = re.compile(r"\(근거:\s*([^)]*)\)")
CID = re.compile(r"C\d+")
NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
TOKEN = re.compile(r"[가-힣A-Za-z0-9]{2,}")
INSUFFICIENT = "(근거 부족)"
CONTENT_F1_THRESHOLD = 0.5


def cited(answer):
    match = CITATION.search(answer)
    return CID.findall(match.group(1)) if match else []


def context_ids(row):
    users = "\n".join(message["content"] for message in row["prompt"]
                      if message["role"] == "user")
    return set(re.findall(r"\[(C\d+)\]", users))


def user_text(row):
    return "\n".join(message["content"] for message in row["prompt"]
                     if message["role"] == "user")


def token_f1(expected, answer):
    gold, got = Counter(TOKEN.findall(expected.casefold())), Counter(TOKEN.findall(answer.casefold()))
    common = sum((gold & got).values())
    if not gold or not got or not common:
        return 0.0
    precision, recall = common / sum(got.values()), common / sum(gold.values())
    return 2 * precision * recall / (precision + recall)


def nugget_recall(expected, answer):
    nuggets = TOKEN.findall(expected.casefold())
    got = answer.casefold()
    return sum(nugget in got for nugget in nuggets) / len(nuggets) if nuggets else 0.0


def sentence_count(text):
    return len([part for part in re.split(r"(?<=[.!?요다])\s+", text.strip()) if part])


def bullet_count(text):
    return len(re.findall(r"(?m)^\s*(?:[-*•]|\d+[.)])\s+", text))


def general_ok(answer, constraints):
    checks = []
    if "bullets" in constraints:
        checks.append(bullet_count(answer) == constraints["bullets"])
    if "contains" in constraints:
        checks.append(all(word in answer for word in constraints["contains"]))
    if "excludes" in constraints:
        checks.append(all(word not in answer for word in constraints["excludes"]))
    if "sentences" in constraints:
        checks.append(sentence_count(answer) == constraints["sentences"])
    if "endswith" in constraints:
        checks.append(answer.rstrip().endswith(constraints["endswith"]))
    if "json_keys" in constraints:
        try:
            value = json.loads(answer)
            checks.append(isinstance(value, dict) and set(value) == set(constraints["json_keys"]))
        except json.JSONDecodeError:
            checks.append(False)
    return bool(checks) and all(checks)


def score_rows(rows):
    counts = Counter()
    pair = defaultdict(dict)
    weight_pair = defaultdict(dict)
    weight_gold = defaultdict(dict)
    for row in rows:
        meta = row["meta"]
        if "weight_pair_id" in meta:
            weight_gold[meta["weight_pair_id"]][meta["weight_variant"]] = meta["expected_answer"]
    details = []
    for row in rows:
        answer, meta = row["answer"], row["meta"]
        task = meta["task"]
        if task == "general":
            ok = general_ok(answer, meta["constraints"])
            counts["general_n"] += 1
            counts["general_ok"] += ok
            details.append({**row, "score": {"general_ok": ok}})
            continue
        citations = cited(answer)
        valid = set(citations).issubset(context_ids(row))
        oracle = set(meta["oracle_context_ids"])
        oracle_cited = bool(oracle & set(citations))
        insufficient = INSUFFICIENT in answer
        unsupported_numbers = set(NUMBER.findall(answer)) - set(meta.get("expected_numbers", []))
        unsupported_numbers -= set(NUMBER.findall(user_text(row)))
        answer_f1 = answer_nugget_recall = contrast_f1 = content_ok = semantic_ok = None
        if task == "no_oracle":
            ok = insufficient and not citations
            counts["negative_n"] += 1
            counts["negative_ok"] += ok
            counts["negative_answered"] += not insufficient
            pair[meta["pair_id"]]["negative"] = ok
        else:
            answer_f1 = token_f1(meta.get("expected_answer", ""), answer)
            answer_nugget_recall = nugget_recall(meta.get("expected_answer", ""), answer)
            content_ok = max(answer_f1, answer_nugget_recall) >= CONTENT_F1_THRESHOLD
            semantic_ok = content_ok
            if "weight_pair_id" in meta:
                other = "swap" if meta["weight_variant"] == "normal" else "normal"
                contrast = weight_gold[meta["weight_pair_id"]].get(other, "")
                contrast_f1 = token_f1(contrast, answer)
                semantic_ok = answer_f1 > contrast_f1
            ok = not insufficient and valid and oracle_cited and semantic_ok
            counts["answer_n"] += 1
            counts["answer_ok"] += ok
            counts["answer_insufficient"] += insufficient
            counts["answer_citation_rows"] += bool(citations)
            counts["answer_oracle_cited"] += oracle_cited
            if task == "raft":
                counts["raft_distractor_only"] += bool(citations) and not oracle_cited
            counts[f"{task}_n"] += 1
            counts[f"{task}_ok"] += ok
            counts["oracle_cited"] += oracle_cited
            counts["token_f1_sum"] += answer_f1
            counts["oracle_coverage_sum"] += len(oracle & set(citations)) / max(len(oracle), 1)
            if "pair_id" in meta:
                pair[meta["pair_id"]]["positive"] = ok
            if "weight_pair_id" in meta:
                weight_pair[meta["weight_pair_id"]][meta["weight_variant"]] = ok
        counts["citation_rows"] += bool(citations)
        counts["valid_citation_rows"] += bool(citations) and valid
        counts["unsupported_number_rows"] += bool(unsupported_numbers)
        details.append({**row, "score": {"ok": ok, "citations": citations,
                        "valid_citations": valid, "oracle_cited": oracle_cited,
                        "insufficient": insufficient,
                        "unsupported_numbers": sorted(unsupported_numbers),
                        "token_f1": answer_f1, "contrast_token_f1": contrast_f1,
                        "nugget_recall": answer_nugget_recall,
                        "content_ok": content_ok,
                        "semantic_ok": semantic_ok}})
    pair_complete = [value for value in pair.values() if set(value) == {"positive", "negative"}]
    weight_complete = [value for value in weight_pair.values() if set(value) == {"normal", "swap"}]
    metrics = {
        "answer_accuracy": counts["answer_ok"] / counts["answer_n"] if counts["answer_n"] else None,
        "automatic_grounded_content_success": (counts["answer_ok"] / counts["answer_n"]
                                                 if counts["answer_n"] else None),
        "no_oracle_accuracy": counts["negative_ok"] / counts["negative_n"] if counts["negative_n"] else None,
        "over_refusal_rate": counts["answer_insufficient"] / counts["answer_n"] if counts["answer_n"] else None,
        "unanswerable_answer_rate": (counts["negative_answered"] / counts["negative_n"]
                                      if counts["negative_n"] else None),
        "pair_accuracy": sum(all(value.values()) for value in pair_complete) / len(pair_complete) if pair_complete else None,
        "weight_counterfactual_accuracy": (sum(all(value.values()) for value in weight_complete)
                                             / len(weight_complete) if weight_complete else None),
        "citation_validity": counts["valid_citation_rows"] / counts["citation_rows"] if counts["citation_rows"] else None,
        "oracle_citation_hit_rate": (counts["answer_oracle_cited"] / counts["answer_citation_rows"]
                                     if counts["answer_citation_rows"] else None),
        "raft_distractor_error_rate": (counts["raft_distractor_only"] / counts["raft_n"]
                                       if counts["raft_n"] else None),
        "mean_answer_token_f1": counts["token_f1_sum"] / counts["answer_n"] if counts["answer_n"] else None,
        "mean_oracle_citation_coverage": (counts["oracle_coverage_sum"] / counts["answer_n"]
                                            if counts["answer_n"] else None),
        "unsupported_number_rate": counts["unsupported_number_rows"] / max(counts["answer_n"] + counts["negative_n"], 1),
        "general_constraint_accuracy": counts["general_ok"] / counts["general_n"] if counts["general_n"] else None,
        "by_task": {task: counts[f"{task}_ok"] / counts[f"{task}_n"]
                    for task in ("grounded", "raft", "multiturn_grounded", "briefing",
                                 "priority", "priority_swap")
                    if counts[f"{task}_n"]},
        "counts": dict(counts),
    }
    return metrics, details


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--answers")
    parser.add_argument("--out")
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        assert general_ok("- 하나\n- 둘", {"bullets": 2})
        assert cited("답이에요. (근거: C2, C4)") == ["C2", "C4"]
        assert token_f1("설명회를 열어요", "알타바 설명회를 열어요") > 0.7
        assert nugget_recall("금요일", "금요일이에요.") == 1.0
        assert context_ids({"prompt": [
            {"role": "user", "content": "[C2] 회의는 금요일이에요."},
            {"role": "assistant", "content": "언제인지 물어보세요."},
            {"role": "user", "content": "그럼 언제예요?"},
        ]}) == {"C2"}
        base = {"prompt": [{"role": "user", "content": "[C1] 회의는 금요일이에요."}],
                "meta": {"task": "grounded", "oracle_context_ids": ["C1"],
                         "expected_answer": "금요일", "expected_numbers": []}}
        metrics, details = score_rows([
            {**base, "answer": "금요일이에요. (근거: C1)"},
            {**base, "answer": "월요일이에요. (근거: C1)"},
        ])
        assert metrics["answer_accuracy"] == 0.5
        assert details[0]["score"]["content_ok"] and not details[1]["score"]["content_ok"]
        print("SELF_CHECK_OK")
        return
    dump = json.load(open(args.answers))
    report = {}
    for name, rows in dump.items():
        metrics, details = score_rows(rows)
        report[name] = {"metrics": metrics, "rows": details}
        print(name, json.dumps(metrics, ensure_ascii=False))
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
