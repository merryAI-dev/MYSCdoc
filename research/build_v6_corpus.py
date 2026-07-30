#!/usr/bin/env python3
"""Build the fixed V6 80/10/10 LoRA corpus and source-separated weight eval."""
import argparse
from collections import Counter, defaultdict
import hashlib
import itertools
import json
from pathlib import Path
import re
import unicodedata

from build_v5_corpora import NUMBER, SYSTEM, completion, context, grounded, raft


SALIENCE_SCORE = {"core": 1.0, "supporting": 0.6, "peripheral": 0.2}
SLACK_ID = re.compile(r"<?@?U[A-Z0-9]{8,}>?")
WEIGHT_SYSTEM = """당신은 사내 기록에서 각 사실의 업무적 중요도(salience)를 판정합니다.
확정도나 최신성과 섞지 말고, 이 기록의 핵심 산출물이면 core, 핵심을 뒷받침하면 supporting,
지엽적 절차나 배경이면 peripheral로 분류하세요. 입력 순서에 영향받지 마세요.
JSON 객체 하나로만 답하세요: {\"weights\":[{\"id\":\"F01\",\"salience\":\"core\",\"weight\":1.0}]}"""


def read_jsonl(path):
    return [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def stable(rows, salt):
    return sorted(rows, key=lambda row: hashlib.sha256(
        f"{salt}:{row.get('source_key', row.get('source_id', ''))}:{row.get('question', '')}".encode()
    ).hexdigest())


def sanitize_teacher(rows):
    output = []
    for row in rows:
        if SLACK_ID.search(" ".join(str(row.get(key, ""))
                                    for key in ("entity", "question", "answer"))):
            continue
        clean = dict(row)
        clean["evidence"] = [SLACK_ID.sub("직원", line) for line in row["evidence"]]
        output.append(clean)
    return output


def repeat_to(rows, count, name):
    if not rows:
        raise SystemExit(f"no usable {name} rows")
    return list(itertools.islice(itertools.cycle(rows), count))


def normalized_question(row):
    return " ".join(unicodedata.normalize("NFKC", row["question"]).split()).casefold()


def multiturn_rows(rows, split):
    groups = defaultdict(list)
    for row in rows:
        if row["split"] == split and row["kind"] == "atomic":
            groups[row["source_key"]].append(row)
    output = []
    for source_key, items in groups.items():
        items = stable(items, "multiturn-items:" + source_key)
        for index in range(0, len(items) - 1, 2):
            first, second = items[index:index + 2]
            body, ids = context([first, second])
            prompt = [
                {"role": "system", "content": SYSTEM},
                {"role": "user", "content": f"업무 기록:\n{body}\n\n질문: {first['question']}"},
                {"role": "assistant", "content": completion(first["answer"], [ids[0]])[0]["content"]},
                {"role": "user", "content": f"그와 관련해서, {second['question']}"},
            ]
            output.append({"prompt": prompt,
                           "completion": completion(second["answer"], [ids[1]]),
                           "question": second["question"], "label": "answerable_multiturn",
                           "meta": {"task": "multiturn_grounded", "source_key": source_key,
                                    "entity": second["entity"], "oracle_context_ids": [ids[1]],
                                    "expected_answer": second["answer"],
                                    "expected_numbers": NUMBER.findall(second["answer"]),
                                    "first_question": first["question"]}})
    return stable(output, "multiturn:" + split)


def weight_group(source_key, rows, reverse=False, include_completion=True, require_mixed=True):
    unique = {}
    for row in rows:
        key = re.sub(r"\s+", " ", row["answer"].casefold()).strip()
        unique.setdefault(key, row)
    picked = stable(list(unique.values()), "weight-items:" + source_key)[:6]
    if len(picked) < 2 or (require_mixed and len({row["salience"] for row in picked}) < 2):
        return None
    items = []
    for index, row in enumerate(picked, 1):
        items.append({"id": f"F{index:02d}", "fact": row["answer"],
                      "evidence": row["evidence"], "salience": row["salience"]})
    shown = list(reversed(items)) if reverse else items
    context = "\n".join(dict.fromkeys(line for item in shown for line in item["evidence"]))
    listing = "\n".join(f"{item['id']}: {item['fact']}" for item in shown)
    prompt = [{"role": "system", "content": WEIGHT_SYSTEM},
              {"role": "user", "content": f"## 원문 근거\n{context}\n\n## 사실 목록\n{listing}"}]
    judgments = [{"id": item["id"], "salience": item["salience"],
                  "weight": SALIENCE_SCORE[item["salience"]]} for item in items]
    value = {"prompt": prompt, "question": f"{source_key} 사실 중요도 판정",
             "label": "weight", "meta": {"task": "weight", "source_key": source_key,
                 "weight_group_id": digest([source_key, [item["fact"] for item in items]])[:16],
                 "weight_variant": "reverse" if reverse else "normal",
                 "items": [{k: item[k] for k in ("id", "fact")} for item in items],
                 "teacher_judgments": judgments}}
    if include_completion:
        value["completion"] = [{"role": "assistant", "content": json.dumps(
            {"weights": judgments}, ensure_ascii=False, separators=(",", ":"))}]
    return value


def weight_rows(rows, split, include_completion, reverse, require_mixed=True):
    groups = defaultdict(list)
    for row in rows:
        if row["split"] == split:
            groups[row["source_key"]].append(row)
    output = []
    for source_key in sorted(groups, key=lambda key: hashlib.sha256(
            f"weight:{split}:{key}".encode()).hexdigest()):
        item = weight_group(source_key, groups[source_key], reverse, include_completion,
                            require_mixed)
        if item:
            output.append(item)
    return output


def predicted_judgments(answer, item_ids):
    try:
        payload = json.loads(answer)
    except (TypeError, json.JSONDecodeError):
        return None
    labels = {item.get("id"): item.get("salience") for item in payload.get("weights", [])
              if isinstance(item, dict)}
    if set(labels) != set(item_ids) or any(label not in SALIENCE_SCORE for label in labels.values()):
        return None
    if len(set(labels.values())) < 2:
        return None
    return [{"id": item_id, "salience": labels[item_id],
             "weight": SALIENCE_SCORE[labels[item_id]]} for item_id in item_ids]


def add_completion(row, judgments):
    value = {**row, "meta": {**row["meta"], "teacher_judgments": judgments}}
    value["completion"] = [{"role": "assistant", "content": json.dumps(
        {"weights": judgments}, ensure_ascii=False, separators=(",", ":"))}]
    return value


def read_weight_answers(path):
    payload = json.load(open(path, encoding="utf-8"))
    rows = payload.get("gemini")
    if not isinstance(rows, list):
        raise SystemExit("weight answers must contain a gemini row list")
    return {row["meta"]["weight_group_id"]: row.get("answer", "") for row in rows}


def build(args):
    general = stable(read_jsonl(args.general), "general")
    raw_teacher = read_jsonl(args.teacher)
    teacher = sanitize_teacher(raw_teacher)
    source_splits = defaultdict(set)
    for row in teacher:
        source_splits[row["split"]].add(row["source_key"])
    split_names = list(source_splits)
    overlap = sum(len(source_splits[a] & source_splits[b])
                  for i, a in enumerate(split_names) for b in split_names[i + 1:])
    if overlap:
        raise SystemExit(f"Slack source split overlap: {overlap}")
    if len(general) < args.general_count + args.general_dev:
        raise SystemExit("general corpus is smaller than train+dev request")

    general_train = general[:args.general_count]
    general_dev = general[args.general_count:args.general_count + args.general_dev]
    atomic_train = stable([row for row in teacher if row["split"] == "train"
                           and row["kind"] == "atomic"], "qa-train")
    atomic_dev = stable([row for row in teacher if row["split"] == "dev"
                         and row["kind"] == "atomic"], "qa-dev")
    qa_builder = grounded if args.qa_mode == "grounded" else lambda row: raft(row, atomic_train)
    qa_dev_builder = (grounded if args.qa_mode == "grounded"
                      else lambda row: raft(row, atomic_dev))
    qa_unique = [value for row in atomic_train for value in [qa_builder(row)] if value]
    qa_dev_unique = [value for row in atomic_dev for value in [qa_dev_builder(row)] if value]
    train_qa_questions = {normalized_question(row) for row in qa_unique}
    qa_dev_unique = [row for row in qa_dev_unique
                     if normalized_question(row) not in train_qa_questions]
    qa_train = repeat_to(qa_unique, args.qa_count, "grounded QA")
    qa_dev = repeat_to(qa_dev_unique, args.qa_dev, "grounded QA dev")
    multiturn_train_unique = multiturn_rows(teacher, "train")
    multiturn_train_questions = {normalized_question(row) for row in multiturn_train_unique}
    multiturn_dev_unique = [row for row in multiturn_rows(teacher, "dev")
                            if normalized_question(row) not in multiturn_train_questions]
    multiturn_train = repeat_to(multiturn_train_unique, args.multiturn_count,
                                "Slack multiturn") if args.multiturn_count else []
    multiturn_dev = repeat_to(multiturn_dev_unique, args.multiturn_dev,
                              "Slack multiturn dev") if args.multiturn_dev else []

    root = Path(args.out_root)
    candidates = weight_rows(teacher, "train", False, False, require_mixed=False)
    write_jsonl(root / "weight-train-candidates.jsonl", candidates)
    if args.weight_answers:
        reverse = {row["meta"]["weight_group_id"]: row for row in
                   weight_rows(teacher, "train", False, True, require_mixed=False)}
        answers = read_weight_answers(args.weight_answers)
        pairs = []
        for row in candidates:
            group_id = row["meta"]["weight_group_id"]
            item_ids = [item["id"] for item in row["meta"]["items"]]
            judgments = predicted_judgments(answers.get(group_id), item_ids)
            if judgments and group_id in reverse:
                pairs.append((add_completion(row, judgments),
                              add_completion(reverse[group_id], judgments)))
        weight_teacher = "gemini-direct-relative"
    else:
        normal = weight_rows(teacher, "train", True, False)
        reverse = {row["meta"]["weight_group_id"]: row
                   for row in weight_rows(teacher, "train", True, True)}
        pairs = [(row, reverse[row["meta"]["weight_group_id"]]) for row in normal
                 if row["meta"]["weight_group_id"] in reverse]
        weight_teacher = "qa-generation-salience"
    pairs.sort(key=lambda pair: digest(pair[0]["meta"]["weight_group_id"]))
    needed_pairs = args.weight_count // 2
    if args.weight_count % 2:
        raise SystemExit("weight-count must be even")
    chosen_pairs = repeat_to(pairs, needed_pairs, "weight pair")
    weight_train = list(itertools.chain.from_iterable(chosen_pairs))
    weight_dev_unique = weight_rows(teacher, "dev", True, False)
    weight_dev = repeat_to(weight_dev_unique, args.weight_dev, "weight dev")

    train = stable(general_train + qa_train + multiturn_train + weight_train, "v6-train")
    dev = stable(general_dev + qa_dev + multiturn_dev + weight_dev, "v6-dev")
    write_jsonl(root / "train.jsonl", train)
    write_jsonl(root / "dev.jsonl", dev)
    selection = weight_rows(teacher, "selection", False, False, require_mixed=False)
    sealed = weight_rows(teacher, "sealed", False, False, require_mixed=False)
    write_jsonl(root / "weight-selection.jsonl", selection)
    write_jsonl(root / "weight-sealed.jsonl", sealed)
    multiturn_selection = multiturn_rows(teacher, "selection")
    multiturn_sealed = multiturn_rows(teacher, "sealed")
    write_jsonl(root / "multiturn-selection.jsonl", multiturn_selection)
    write_jsonl(root / "multiturn-sealed.jsonl", multiturn_sealed)
    manifest = {
        "protocol": "v6-weight-first-lora-v1", "source_overlap": overlap,
        "train": {"rows": len(train), "general": len(general_train),
                  "slack_qa": len(qa_train), "slack_qa_unique": len(qa_unique),
                  "slack_multiturn": len(multiturn_train),
                  "weight": len(weight_train), "weight_pairs_unique": len(pairs)},
        "dev": {"rows": len(dev), "general": len(general_dev),
                "slack_qa": len(qa_dev), "slack_multiturn": len(multiturn_dev),
                "weight": len(weight_dev)},
        "weight_eval": {"selection": len(selection), "sealed": len(sealed)},
        "multiturn_eval": {"selection": len(multiturn_selection),
                           "sealed": len(multiturn_sealed)},
        "teacher_filter": {"input": len(raw_teacher), "kept": len(teacher),
                           "dropped_slack_id": len(raw_teacher) - len(teacher)},
        "weight_teacher": weight_teacher,
        "qa_mode": args.qa_mode,
        "distribution": dict(Counter(row.get("source", row.get("meta", {}).get("task"))
                                     for row in train)),
        "general_sha256": hashlib.sha256(Path(args.general).read_bytes()).hexdigest(),
        "teacher_sha256": hashlib.sha256(Path(args.teacher).read_bytes()).hexdigest(),
    }
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def self_check():
    assert repeat_to([1, 2], 5, "test") == [1, 2, 1, 2, 1]
    assert normalized_question({"question": "  같은  질문 "}) == "같은 질문"
    base = {"source_key": "c:1", "answer": "발표자를 정했어요.",
            "evidence": ["발표자는 민지로 정했어요."], "salience": "core"}
    other = {"source_key": "c:1", "answer": "자료 색상을 논의했어요.",
             "evidence": ["자료 색상도 이야기했어요."], "salience": "peripheral"}
    a = weight_group("c:1", [base, other], False, True)
    b = weight_group("c:1", [base, other], True, True)
    assert a["meta"]["teacher_judgments"] == b["meta"]["teacher_judgments"]
    assert a["prompt"] != b["prompt"] and a["completion"] == b["completion"]
    assert weight_group("c:1", [base, {**other, "salience": "core"}],
                        require_mixed=False)
    assert not sanitize_teacher([{**base, "entity": "직원", "question": "U09AA1HN005는?"}])
    assert sanitize_teacher([{**base, "entity": "발표자", "question": "발표자는?"}])[0][
        "evidence"] == ["발표자는 민지로 정했어요."]
    judgments = predicted_judgments(
        '{"weights":[{"id":"F01","salience":"core"},'
        '{"id":"F02","salience":"peripheral"}]}', ["F01", "F02"])
    assert judgments and add_completion(a, judgments)["completion"]
    pair_rows = [{**base, "split": "train", "kind": "atomic", "question": "누구인가요?",
                  "entity": "발표자", "answer": "민지예요."},
                 {**other, "split": "train", "kind": "atomic", "question": "무엇을 논의했나요?",
                  "entity": "자료", "answer": "자료 색상이요."}]
    multi = multiturn_rows(pair_rows, "train")[0]
    assert [m["role"] for m in multi["prompt"]] == ["system", "user", "assistant", "user"]
    assert multi["meta"]["oracle_context_ids"] == ["C2"]
    print("SELF_CHECK_OK")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--general")
    parser.add_argument("--teacher")
    parser.add_argument("--out-root")
    parser.add_argument("--general-count", type=int, default=20000)
    parser.add_argument("--qa-count", type=int, default=2500)
    parser.add_argument("--weight-count", type=int, default=2500)
    parser.add_argument("--multiturn-count", type=int, default=0)
    parser.add_argument("--general-dev", type=int, default=1000)
    parser.add_argument("--qa-dev", type=int, default=250)
    parser.add_argument("--weight-dev", type=int, default=250)
    parser.add_argument("--multiturn-dev", type=int, default=0)
    parser.add_argument("--weight-answers")
    parser.add_argument("--qa-mode", choices=("grounded", "raft"), default="raft")
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif not args.general or not args.teacher or not args.out_root:
        parser.error("--general, --teacher and --out-root are required")
    else:
        build(args)


if __name__ == "__main__":
    main()
