#!/usr/bin/env python3
"""Build two small V7 refusal-repair corpora from the existing V6 corpus."""
import argparse
import hashlib
import json
from pathlib import Path
import re


TOKEN = re.compile(r"[가-힣A-Za-z0-9]{2,}")


def read_jsonl(path):
    return [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]


def write_jsonl(path, rows):
    Path(path).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))


def digest(row, salt):
    value = f"{salt}:{row.get('source_id', row.get('meta', {}).get('source_key', ''))}:{row.get('question', '')}"
    return hashlib.sha256(value.encode()).hexdigest()


def stable(rows, salt):
    return sorted(rows, key=lambda row: digest(row, salt))


def unique(rows):
    output, seen = [], set()
    for row in rows:
        key = (row.get("question"), row.get("meta", {}).get("source_key"), row.get("source_id"))
        if key not in seen:
            seen.add(key)
            output.append(row)
    return output


def user_body(row):
    return row["prompt"][-1]["content"].split("업무 기록:\n", 1)[1].split("\n\n질문:", 1)[0]


def no_oracle(rows):
    output = []
    for index, row in enumerate(rows):
        query = set(TOKEN.findall(row["question"].casefold()))
        entity = row.get("meta", {}).get("entity", "")
        candidates = []
        for other in rows:
            if other is row or other.get("meta", {}).get("source_key") == row.get("meta", {}).get("source_key"):
                continue
            body = user_body(other)
            if entity and entity in body:
                continue
            overlap = len(query & set(TOKEN.findall(body.casefold())))
            candidates.append((overlap, digest(other, row["question"]), other))
        if not candidates:
            continue
        noise = max(candidates, key=lambda item: (item[0], item[1]))[2]
        prompt = [dict(row["prompt"][0]), {"role": "user", "content":
                  f"업무 기록:\n{user_body(noise)}\n\n질문: {row['question']}"}]
        output.append({"prompt": prompt, "completion": [{"role": "assistant", "content":
                      "현재 제공된 기록에는 질문에 답할 직접 근거가 없어요. 비슷한 내용으로 "
                      "추측하지 않을게요. (근거 부족)"}], "question": row["question"],
                      "label": "unanswerable_hard", "meta": {**row.get("meta", {}),
                      "task": "no_oracle", "repair_pair_id": f"repair-{index:04d}"}})
    return output


def take(rows, count, name):
    if len(rows) < count:
        raise SystemExit(f"{name}: {len(rows)} < {count}")
    return rows[:count]


def repeat_to(rows, count, name):
    if not rows:
        raise SystemExit(f"{name}: empty")
    return [rows[index % len(rows)] for index in range(count)]


def build(source, out_root, negative_count):
    train = read_jsonl(Path(source) / "train.jsonl")
    dev = read_jsonl(Path(source) / "dev.jsonl")
    general = stable([r for r in train if r.get("source") == "smol-koreantalk"], "general")
    qa = stable(unique([r for r in train if r.get("meta", {}).get("task") == "raft"]), "qa")
    weight = stable(unique([r for r in train if r.get("meta", {}).get("task") == "weight"]), "weight")
    negatives = stable(no_oracle(qa), "negative")
    general_count = 2400 - negative_count
    rows = (take(general, general_count, "general") + take(qa, 800, "qa")
            + repeat_to(weight, 800, "weight") + take(negatives, negative_count, "negative"))
    rows = stable(rows, f"repair-{negative_count}")

    dev_qa = stable(unique([r for r in dev if r.get("meta", {}).get("task") == "raft"]), "dev-qa")
    dev_negative = stable(no_oracle(dev_qa), "dev-negative")
    dev_rows = stable(take(dev_qa, 100, "dev qa") + take(dev_negative, 100, "dev negative"),
                      "repair-dev")
    root = Path(out_root)
    root.mkdir(parents=True, exist_ok=True)
    write_jsonl(root / "train.jsonl", rows)
    write_jsonl(root / "dev.jsonl", dev_rows)
    manifest = {"protocol": "v9-refusal-repair-v1", "rows": len(rows),
                "general": general_count, "grounded_qa": 800, "weight": 800,
                "no_oracle": negative_count, "dev_rows": len(dev_rows)}
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(manifest, ensure_ascii=False))


def self_check():
    row = {"prompt": [{"role": "system", "content": "s"}, {"role": "user", "content":
           "업무 기록:\n[C1] 알파는 3일이에요.\n\n질문: 알파는 언제예요?"}],
           "question": "알파는 언제예요?", "meta": {"source_key": "a", "entity": "알파"}}
    other = {**row, "question": "베타는?", "meta": {"source_key": "b", "entity": "베타"},
             "prompt": [row["prompt"][0], {"role": "user", "content":
             "업무 기록:\n[C1] 베타는 5일이에요.\n\n질문: 베타는?"}]}
    near = {**other, "question": "알파벳은?", "meta": {"source_key": "c", "entity": "알파벳"},
            "prompt": [row["prompt"][0], {"role": "user", "content":
            "업무 기록:\n[C1] 베타 일정은 언제예요라고 문의했어요.\n\n질문: 알파벳은?"}]}
    negatives = no_oracle([row, other, near])
    assert "언제예요라고 문의" in negatives[0]["prompt"][-1]["content"]
    assert negatives[0]["completion"][0]["content"].endswith("(근거 부족)")
    print("SELF_CHECK_OK")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source")
    parser.add_argument("--out-root")
    parser.add_argument("--negative-count", type=int)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif not all((args.source, args.out_root, args.negative_count)):
        parser.error("--source, --out-root and --negative-count are required")
    else:
        build(args.source, args.out_root, args.negative_count)


if __name__ == "__main__":
    main()
