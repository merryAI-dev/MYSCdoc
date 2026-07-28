#!/usr/bin/env python3
"""Reserve 32 boundary pairs for H1 and rebuild the 150-pair sealed set."""
import argparse
import copy
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

from build_v4_sealed_eval import bm25_index, fact_text, load_grades, normalize, read_jsonl
from build_chat_gold_review import case_id


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))


def prompt(system, question, indices, corpus, grades):
    user = (f"질문: {question}\n\n지식그래프 사실:\n{fact_text(indices, corpus, grades)}"
            "\n\n위 사실만 근거로, 각 사실의 확정도를 구분해서 답하세요.\n")
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def synthetic_pairs(system, raw, corpus, grades, count, seed):
    used_nodes = {(row["doc_id"], row["subject"]) for row in raw}
    nodes = defaultdict(list)
    for row in corpus:
        if row["subject"].startswith("결정:"):
            nodes[(row["doc_id"], row["subject"])].append(row["i"])
    candidates = []
    for key, indices in nodes.items():
        if key in used_nodes:
            continue
        decisive = [i for i in indices
                    if corpus[i]["text"][len(key[1]):].lstrip().startswith("값 ")]
        if decisive:
            order = hashlib.sha256(f"{seed}:{key[0]}:{key[1]}".encode()).hexdigest()
            candidates.append((order, key, indices, decisive))
    retrieve = bm25_index(corpus)
    output = []
    for _, (_, subject), gold, decisive in sorted(candidates):
        question = f"{subject.removeprefix('결정:')}은 어떻게 정해졌나요?"
        scores = retrieve(question)
        decisive_index = max(decisive, key=lambda i: (scores.get(i, 0), -i))
        distractors = [i for i in sorted(scores, key=lambda i: (-scores[i], i))
                       if i not in set(gold)][:19]
        if len(distractors) != 19:
            continue
        positive = sorted(distractors + [decisive_index],
                          key=lambda i: (-scores.get(i, 0), i))
        decisive_sha = hashlib.sha256(
            json.dumps(corpus[decisive_index], sort_keys=True).encode()).hexdigest()
        output.append((
            {"prompt": prompt(system, question, positive, corpus, grades),
             "meta": {"variant": "positive", "question": question,
                      "target": "answerable", "decisive_fact_sha256": decisive_sha,
                      "source": "unused-decision-node-template"}},
            {"prompt": prompt(system, question, [i for i in positive if i != decisive_index],
                               corpus, grades),
             "meta": {"variant": "negative", "question": question,
                      "target": "unanswerable", "decisive_fact_sha256": decisive_sha,
                      "source": "unused-decision-node-template"}},
        ))
        if len(output) == count:
            return output
    raise SystemExit(f"only built {len(output)}/{count} synthetic sealed pairs")


def raw_pair(system, source, corpus, grades, retrieve):
    question, subject = source["question"].strip(), source["subject"]
    gold = [i for i in source["gold"]
            if corpus[i]["doc_id"] == source["doc_id"] and corpus[i]["subject"] == subject]
    decisive = [i for i in gold
                if corpus[i]["text"][len(subject):].lstrip().startswith("값 ")]
    if not decisive:
        return None
    scores = retrieve(question)
    decisive_index = max(decisive, key=lambda i: (scores.get(i, 0), -i))
    distractors = [i for i in sorted(scores, key=lambda i: (-scores[i], i))
                   if i not in set(gold)][:19]
    if len(distractors) != 19:
        return None
    positive = sorted(distractors + [decisive_index], key=lambda i: (-scores.get(i, 0), i))
    meta = {"question": question,
            "decisive_fact_sha256": hashlib.sha256(
                json.dumps(corpus[decisive_index], sort_keys=True).encode()).hexdigest()}
    return (
        {"prompt": prompt(system, question, positive, corpus, grades),
         "meta": {**meta, "variant": "positive", "target": "answerable"}},
        {"prompt": prompt(system, question, [i for i in positive if i != decisive_index], corpus, grades),
         "meta": {**meta, "variant": "negative", "target": "unanswerable"}},
    )


def evaluation_rows(pairs, duplicate_pairs, prefix):
    rows = []
    for i, pair in enumerate(pairs, 1):
        positive = copy.deepcopy(pair[0])
        positive["label"] = "answerable"
        positive["question"] = positive["meta"]["question"]
        positive["meta"]["pair_id"] = f"{prefix}-{i:03d}"
        rows.append(positive)
    for i, pair in enumerate(pairs[:duplicate_pairs], 1):
        negative = copy.deepcopy(pair[1])
        negative["label"] = "unanswerable_hard"
        negative["question"] = negative["meta"]["question"]
        negative["meta"]["pair_id"] = f"{prefix}-{i:03d}"
        rows.append(negative)
    return rows


def facts(row):
    text = row["prompt"][1]["content"].split("지식그래프 사실:\n", 1)[1]
    text = text.split("\n\n위 사실만 근거로", 1)[0]
    return [line.split(". ", 1)[1].strip() for line in text.splitlines() if line.strip()]


def completion_pair(positive, negative, pair_id):
    positive, negative = copy.deepcopy(positive), copy.deepcopy(negative)
    removed = [fact for fact in facts(positive) if fact not in facts(negative)]
    if len(removed) != 1 or " — 값 — " not in removed[0]:
        raise SystemExit(f"{pair_id}: decisive value fact not found")
    decisive = removed[0]
    grade = decisive.split("]", 1)[0].lstrip("[")
    topic, value = decisive.split("결정:", 1)[1].split(" — 값 — ", 1)
    qualifier = {
        "확정": "합의된 사항이라 확정된 내용으로 볼 수 있어요.",
        "조건부": "조건이 붙은 결정이라 해당 조건과 함께 봐야 해요.",
        "예정": "계획 단계의 내용이라 아직 실행 결과가 확정된 것은 아니에요.",
        "논의": "아직 검토 단계라 확정된 내용은 아니에요.",
        "미분류": "다만 지식그래프에는 이 사실의 확정도가 분류되어 있지 않아요.",
    }.get(grade, "확정도는 제공된 사실의 표시를 따라야 해요.")
    positive["completion"] = [{"role": "assistant", "content":
        f"{topic.strip()}에 관해서는 {value.strip()} {qualifier}"}]
    negative["completion"] = [{"role": "assistant", "content":
        "지식그래프에 아직 그 질문을 뒷받침하는 내용이 없어요. 제공된 인접 사실만으로는 답을 확정할 수 없어요."}]
    for row, variant, target in ((positive, "positive", "answerable"),
                                 (negative, "negative", "unanswerable")):
        row["meta"] = {**row["meta"], "pair_id": pair_id, "variant": variant,
                       "target": target}
    return positive, negative


def sample_key(row):
    return json.dumps({"prompt": row["prompt"], "completion": row["completion"]},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidate-pairs", required=True)
    ap.add_argument("--control-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--sealed-out", required=True)
    ap.add_argument("--sealed-manifest", required=True)
    ap.add_argument("--audit-out", required=True)
    ap.add_argument("--selection-out", required=True)
    ap.add_argument("--selection-proposals", required=True)
    ap.add_argument("--raw", nargs="+", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--grades", required=True)
    ap.add_argument("--seed", type=int, default=20260728)
    args = ap.parse_args()

    candidates = read_jsonl(args.candidate_pairs)
    if len(candidates) != 332:
        raise SystemExit("candidate file must contain 166 pairs")
    pairs = list(zip(candidates[::2], candidates[1::2]))
    h1, sealed = pairs[:32], pairs[32:]
    control_train = read_jsonl(Path(args.control_dir) / "train.jsonl")
    system = control_train[0]["prompt"][0]["content"]
    raw = [row for path in args.raw for row in read_jsonl(path)]
    corpus = read_jsonl(args.corpus)
    grades = load_grades(args.grades)
    sealed += synthetic_pairs(system, raw, corpus, grades, 16, args.seed)

    sealed_rows = []
    for i, pair in enumerate(sealed, 1):
        for row in copy.deepcopy(pair):
            row["meta"]["pair_id"] = f"sealed-{i:03d}"
            sealed_rows.append(row)
    if len(sealed_rows) != 300 or len({normalize(r["meta"]["question"]) for r in sealed_rows}) != 150:
        raise SystemExit("sealed set is not 150 unique pairs")
    write_jsonl(args.sealed_out, sealed_rows)

    counts = Counter(sample_key(row) for row in control_train)
    eligible = {"answerable": [], "unanswerable": []}
    for i, row in enumerate(control_train):
        target = row["meta"]["target"]
        group = "unanswerable" if target == "unanswerable" else "answerable"
        if counts[sample_key(row)] == 1 and len(eligible[group]) < 32:
            eligible[group].append(i)
    if any(len(rows) != 32 for rows in eligible.values()):
        raise SystemExit(f"not enough singleton control rows: {dict(map(lambda x:(x[0],len(x[1])), eligible.items()))}")
    additions = [completion_pair(*pair, f"boundary-{i:03d}") for i, pair in enumerate(h1, 1)]
    treatment = copy.deepcopy(control_train)
    for index, row in zip(eligible["answerable"], (pair[0] for pair in additions)):
        treatment[index] = row
    for index, row in zip(eligible["unanswerable"], (pair[1] for pair in additions)):
        treatment[index] = row
    write_jsonl(Path(args.out_dir) / "train.jsonl", treatment)
    Path(args.out_dir).mkdir(parents=True, exist_ok=True)
    (Path(args.out_dir) / "dev.jsonl").write_bytes((Path(args.control_dir) / "dev.jsonl").read_bytes())

    blocked = {normalize(row["meta"]["question"]) for row in sealed_rows}
    training_rows = treatment + read_jsonl(Path(args.control_dir) / "dev.jsonl")
    blocked |= {normalize(row["meta"].get("question", "")) for row in training_rows}
    available = [row for row in raw if normalize(row["question"]) not in blocked]
    available.sort(key=lambda row: hashlib.sha256(
        f"{args.seed}:eval:{row['question']}".encode()).hexdigest())
    retrieve = bm25_index(corpus)
    eval_pairs = [pair for row in available if (pair := raw_pair(
        system, row, corpus, grades, retrieve)) is not None]
    if len(eval_pairs) < 247:
        raise SystemExit(f"only {len(eval_pairs)}/247 clean eval questions")
    selection_pairs, audit_pairs = eval_pairs[:167], eval_pairs[167:247]
    selection_rows = evaluation_rows(selection_pairs, 32, "selection")
    write_jsonl(args.selection_out, selection_rows)
    write_jsonl(args.audit_out, evaluation_rows(audit_pairs, 20, "audit"))
    corpus_by_sha = {hashlib.sha256(json.dumps(row, sort_keys=True).encode()).hexdigest(): row["i"]
                     for row in corpus}
    proposals = []
    for index, row in enumerate(selection_rows):
        positive = row["label"] == "answerable"
        evidence = []
        if positive:
            decisive_index = corpus_by_sha[row["meta"]["decisive_fact_sha256"]]
            decisive = fact_text([decisive_index], corpus, grades).split(". ", 1)[1]
            evidence = [facts(row).index(decisive) + 1]
        proposals.append({
            "id": case_id(index, row["question"], row["prompt"][1]["content"]),
            "verdict": "answerable" if positive else "unanswerable", "confidence": "high",
            "evidence": evidence,
            "rationale": (f"질문의 결정적 값 사실이 #{evidence[0]}에 직접 포함돼 있어요."
                          if positive else "짝의 결정적 값 사실 하나가 제거돼 질문에 답할 근거가 없어요."),
        })
    write_jsonl(args.selection_proposals, proposals)
    manifest = {
        "protocol": "v4-h1-seed42-assets-v1", "seed": args.seed,
        "h1_pairs": 32, "sealed_pairs": 150, "synthetic_sealed_pairs": 16,
        "selection_rows_questions": [199, 167], "audit_rows_questions": [100, 80],
        "removed_control_indices": eligible,
        "h1_questions_sha256": [hashlib.sha256(p[0]["meta"]["question"].encode()).hexdigest()
                                  for p in h1],
        "sealed_file_sha256": hashlib.sha256(Path(args.sealed_out).read_bytes()).hexdigest(),
    }
    Path(args.sealed_manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("[done] treatment=449/79 h1=32 pairs sealed=150 pairs "
          "selection=199/167 audit=100/80")
    print(f"[seal] sha256={manifest['sealed_file_sha256']}")


if __name__ == "__main__":
    main()
