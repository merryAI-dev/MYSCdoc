#!/usr/bin/env python3
"""Build 150 untouched positive/negative question pairs for the sealed V4 evaluation."""
import argparse
import hashlib
import json
import math
import random
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

QUESTION_RE = re.compile(r"(?:^|\n)질문:\s*(.+?)(?:\n|$)")
GRADE_OF = {"확정": "확정", "조건부확정": "조건부", "의지예정": "예정",
            "제안검토": "논의", "당위": "논의", "비결정": "논의"}


def normalize(text):
    return " ".join(unicodedata.normalize("NFKC", text).split()).casefold()


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]


def questions_in(value):
    found = set()
    if isinstance(value, dict):
        if isinstance(value.get("question"), str):
            found.add(value["question"].strip())
        for message in value.get("prompt", []):
            if isinstance(message, dict) and message.get("role") == "user":
                match = QUESTION_RE.search(message.get("content", ""))
                if match:
                    found.add(match.group(1).strip())
        for child in value.values():
            found |= questions_in(child)
    elif isinstance(value, list):
        for child in value:
            found |= questions_in(child)
    return found


def load_questions(path):
    text = Path(path).read_text()
    if Path(path).suffix == ".jsonl":
        return set().union(*(questions_in(json.loads(line)) for line in text.splitlines() if line.strip()))
    return questions_in(json.loads(text))


def tokens(text):
    words = re.findall(r"[가-힣A-Za-z0-9]+", normalize(text))
    # ponytail: character bigrams are the dependency-free Korean tokenizer; replace only
    # if a morphology-aware retriever is shown to improve boundary-pair difficulty.
    return words + [f"c:{word[i:i + 2]}" for word in words for i in range(len(word) - 1)]


def bm25_index(corpus):
    counts = [Counter(tokens(row["text"])) for row in corpus]
    lengths = [sum(counter.values()) for counter in counts]
    postings = defaultdict(list)
    for index, counter in enumerate(counts):
        for token, frequency in counter.items():
            postings[token].append((index, frequency))
    average = sum(lengths) / len(lengths)

    def scores(query):
        result = defaultdict(float)
        for token in set(tokens(query)):
            entries = postings.get(token, ())
            idf = math.log(1 + (len(corpus) - len(entries) + 0.5) / (len(entries) + 0.5))
            for index, frequency in entries:
                denominator = frequency + 1.5 * (1 - 0.75 + 0.75 * lengths[index] / average)
                result[index] += idf * frequency * 2.5 / denominator
        return result

    return scores


def load_grades(path):
    return {(row["doc_id"], "결정:" + row["topic"].strip()): GRADE_OF[row["commitment"]]
            for row in json.loads(Path(path).read_text())}


def fact_text(items, corpus, grades):
    lines = []
    for number, index in enumerate(items, 1):
        row = corpus[index]
        subject = row["subject"]
        remainder = row["text"][len(subject):].strip() if row["text"].startswith(subject) else row["text"]
        predicate, _, value = remainder.partition(" ")
        tag = grades.get((row["doc_id"], subject), "미분류")
        kind = "decision" if subject.startswith("결정:") else "context"
        line = f"{number}. [{tag}] [{kind}] {subject} — {predicate or '근거'} — {value or remainder}"
        lines.append(line)
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--control", required=True, help="annotated control train.jsonl")
    parser.add_argument("--raw", nargs="+", required=True, help="RL train/val JSONL")
    parser.add_argument("--corpus", required=True, help="RL corpus JSONL referenced by raw gold indices")
    parser.add_argument("--exclude", nargs="+", required=True)
    parser.add_argument("--grades", required=True)
    parser.add_argument("--pairs", type=int, default=150)
    parser.add_argument("--seed", type=int, default=20260728)
    args = parser.parse_args()

    control = read_jsonl(args.control)
    system = control[0]["prompt"][0]["content"]
    excluded = {normalize(question) for path in args.exclude for question in load_questions(path)}
    excluded |= {normalize(question) for question in questions_in(control)}

    raw = []
    seen = set()
    for path in args.raw:
        for row in read_jsonl(path):
            question = row["question"].strip()
            if question not in seen:
                seen.add(question)
                raw.append(row)
    random.Random(args.seed).shuffle(raw)

    corpus = read_jsonl(args.corpus)
    if [row["i"] for row in corpus] != list(range(len(corpus))):
        raise SystemExit("RL corpus indices are not contiguous")
    retrieve = bm25_index(corpus)
    grades = load_grades(args.grades)

    output = []
    skipped = {"excluded": 0, "missing_node": 0, "missing_value": 0, "thin_retrieval": 0}
    for source in raw:
        question = source["question"].strip()
        if normalize(question) in excluded:
            skipped["excluded"] += 1
            continue
        gold = [index for index in source["gold"]
                if corpus[index]["doc_id"] == source["doc_id"]
                and corpus[index]["subject"] == source["subject"]]
        if not gold:
            skipped["missing_node"] += 1
            continue
        decisive = [index for index in gold
                    if corpus[index]["text"][len(source["subject"]):].lstrip().startswith("값 ")]
        if not decisive:
            skipped["missing_value"] += 1
            continue

        scores = retrieve(question)
        decisive_index = max(decisive, key=lambda index: (scores.get(index, 0), -index))
        gold_set = set(gold)
        distractors = [index for index in sorted(scores, key=lambda i: (-scores[i], i))
                       if index not in gold_set][:19]
        if len(distractors) != 19:
            skipped["thin_retrieval"] += 1
            continue
        positive_indices = sorted(distractors + [decisive_index],
                                  key=lambda i: (-scores.get(i, 0), i))
        negative_indices = [index for index in positive_indices if index != decisive_index]
        pair_id = f"sealed-{len(output) // 2 + 1:03d}"
        decisive_sha = hashlib.sha256(json.dumps(corpus[decisive_index], sort_keys=True).encode()).hexdigest()
        for variant, indices, target in (("positive", positive_indices, "answerable"),
                                         ("negative", negative_indices, "unanswerable")):
            user = (f"질문: {question}\n\n지식그래프 사실:\n{fact_text(indices, corpus, grades)}"
                    "\n\n위 사실만 근거로, 각 사실의 확정도를 구분해서 답하세요.\n")
            output.append({
                "prompt": [{"role": "system", "content": system},
                           {"role": "user", "content": user}],
                "meta": {"pair_id": pair_id, "variant": variant, "question": question,
                         "target": target, "decisive_fact_sha256": decisive_sha},
            })
        if len(output) == args.pairs * 2:
            break

    if len(output) != args.pairs * 2:
        raise SystemExit(f"only built {len(output) // 2}/{args.pairs} pairs; skipped={skipped}")
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in output))
    manifest = {
        "protocol": "v4-sealed-boundary-pairs-v1",
        "status": "pending_human_verification",
        "seed": args.seed,
        "pairs": args.pairs,
        "rows": len(output),
        "questions_sha256": [hashlib.sha256(row["meta"]["question"].encode()).hexdigest()
                             for row in output[::2]],
        "file_sha256": hashlib.sha256(out.read_bytes()).hexdigest(),
        "excluded_questions": len(excluded),
        "skipped": skipped,
    }
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"[done] {args.pairs} pairs/{len(output)} rows · excluded={len(excluded)} → {out}")
    print(f"[seal] sha256={manifest['file_sha256']}")


if __name__ == "__main__":
    main()
