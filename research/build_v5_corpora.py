#!/usr/bin/env python3
"""Build matched V5 H1-RAFT and H2-weighted-briefing SFT/eval corpora."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path


SYSTEM = """당신은 사내 업무 기록을 바탕으로 답하는 한국어 챗봇입니다.
질문에 바로 답하고, 제공된 기록 밖의 회사명·인명·기관명·날짜·수치는 추측하지 마세요.
답할 근거가 있으면 마지막에 `(근거: C1, C2)`처럼 사용한 기록 ID를 적으세요.
직접 근거가 없으면 비슷한 대상을 같은 것으로 취급하지 말고 `(근거 부족)`으로 끝내세요.
확정, 예정, 논의, 조건부 상태를 구분해 자연스러운 해요체로 답하세요."""
GENERAL_SYSTEM = "한국어로 자연스럽고 정확하게 지시를 따르세요."
TOKEN = re.compile(r"[가-힣A-Za-z0-9]{2,}")
NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
SAL_ORDER = {"core": 0, "supporting": 1, "peripheral": 2}
COMMIT_KO = {"confirmed": "확정", "planned": "예정", "discussed": "논의",
             "conditional": "조건부", "unknown": "미분류"}
SAL_KO = {"core": "핵심", "supporting": "보조", "peripheral": "주변"}


def read_jsonl(path):
    with open(path, encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def stable(rows, salt):
    def material(row):
        if isinstance(row, dict):
            return f"{row.get('source_key')}:{row.get('question')}:{row.get('kind')}"
        return str(row)
    return sorted(rows, key=lambda row: hashlib.sha256(
        f"{salt}:{material(row)}".encode()).hexdigest())


def terms(text):
    return set(TOKEN.findall(text.casefold()))


def distractors(target, pool, count):
    query = terms(target["question"])
    candidates = []
    for row in pool:
        if row["source_key"] == target["source_key"]:
            continue
        evidence = " ".join(row["evidence"])
        if target["entity"] in evidence:
            continue
        score = len(query & terms(row["question"] + " " + evidence))
        tie = digest([target["question"], row["source_key"], row["question"]])
        candidates.append((-score, tie, row))
    return [row for _, _, row in sorted(candidates)[:count]]


def context(items, weighted=False):
    lines, ids = [], []
    for index, item in enumerate(items, 1):
        cid = f"C{index}"
        ids.append(cid)
        prefix = f"[{cid}]"
        if weighted:
            prefix += (f"[중요도={SAL_KO[item['salience']]}]"
                       f"[상태={COMMIT_KO[item['commitment']]}]"
                       f"[시점={item['thread_ts']}]")
        lines.append(prefix + " " + " / ".join(item["evidence"]))
    return "\n".join(lines), ids


def prompt(question, body):
    return [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"업무 기록:\n{body}\n\n질문: {question}"}]


def completion(answer, citation_ids):
    clean = re.sub(r"\s*\(근거(?::| 부족).*?\)\s*$", "", answer).strip()
    suffix = f"(근거: {', '.join(citation_ids)})"
    return [{"role": "assistant", "content": f"{clean} {suffix}"}]


def sample(row, task, body, answer, oracle_ids, label, extra=None):
    meta = {"task": task, "source_key": row["source_key"], "entity": row["entity"],
            "oracle_context_ids": oracle_ids, "expected_answer": row["answer"],
            "expected_numbers": NUMBER.findall(row["answer"]), **(extra or {})}
    return {"prompt": prompt(row["question"], body),
            "completion": [{"role": "assistant", "content": answer}],
            "question": row["question"], "label": label, "meta": meta}


def grounded(row):
    body, ids = context([row])
    return sample(row, "grounded", body, completion(row["answer"], ids)[0]["content"],
                  ids, "answerable")


def no_oracle(row, pool):
    noise = distractors(row, pool, 2)
    if len(noise) < 2:
        return None
    body, _ = context(noise)
    answer = (f"현재 제공된 기록에는 ‘{row['question']}’에 답할 직접 근거가 없어요. "
              "비슷한 내용만으로 같은 대상의 사실을 추측하지 않을게요. (근거 부족)")
    return sample(row, "no_oracle", body, answer, [], "unanswerable_hard",
                  {"removed_answer": row["answer"]})


def raft(row, pool):
    noise = distractors(row, pool, 3)
    if len(noise) < 3:
        return None
    order = stable([row] + noise, "raft-order:" + row["question"])
    body, ids = context(order)
    oracle = [ids[order.index(row)]]
    return sample(row, "raft", body, completion(row["answer"], oracle)[0]["content"],
                  oracle, "answerable_raft")


def briefing(row):
    evidence_items = [{**row, "evidence": [line]} for line in row["evidence"]]
    evidence_items = sorted(evidence_items, key=lambda item: SAL_ORDER[item["salience"]])
    body, ids = context(evidence_items, weighted=True)
    return sample(row, "briefing", body, completion(row["answer"], ids)[0]["content"],
                  ids, "answerable_briefing",
                  {"salience": row["salience"], "commitment": row["commitment"]})


def priority(rows, swap=False, include_completion=True):
    """Ask for the fact marked most important; swapping labels changes the gold answer."""
    items = stable(rows[:2], "priority-order:" + rows[0]["source_key"])
    ranked = []
    for row in items:
        original_rank = rows.index(row)
        salience = ("supporting", "core")[original_rank] if swap else ("core", "supporting")[original_rank]
        ranked.append({**row, "salience": salience})
    body, ids = context(ranked, weighted=True)
    winner = rows[1] if swap else rows[0]
    oracle = [ids[items.index(winner)]]
    when = datetime.fromtimestamp(float(rows[0]["thread_ts"]), timezone.utc)
    entities = "·".join(dict.fromkeys(row["entity"] for row in rows[:2]))
    question = (f"{when.year}년 {when.month}월 {when.day}일 {entities} 관련 기록에서 "
                "가장 중요한 내용을 한 가지만 알려줘.")
    answer = completion(winner["answer"], oracle)[0]["content"]
    value = sample({**winner, "question": question}, "priority_swap" if swap else "priority",
                   body, answer, oracle, "answerable_priority",
                   {"weight_pair_id": digest([winner["source_key"], "weight"])[:16],
                    "weight_variant": "swap" if swap else "normal"})
    if not include_completion:
        value.pop("completion", None)
    return value


def split_rows(rows, split):
    selected = [row for row in rows if row["split"] == split]
    by_source = defaultdict(lambda: {"atomic": [], "briefing": []})
    for row in stable(selected, f"group:{split}"):
        by_source[row["source_key"]][row["kind"]].append(row)
    atomics = [row for group in by_source.values() for row in group["atomic"][:2]]
    briefings = [group["briefing"][0] for group in by_source.values() if group["briefing"]]
    return by_source, atomics, briefings


def build_train(rows, split, arm):
    by_source, atomics, briefings = split_rows(rows, split)
    common = [grounded(row) for row in atomics]
    common += [value for source in stable(list(by_source), f"no:{split}")
               if by_source[source]["atomic"]
               for value in [no_oracle(by_source[source]["atomic"][0], atomics)] if value]
    h1 = [value for source in stable(list(by_source), f"h1:{split}")
          if by_source[source]["atomic"]
          for value in [raft(by_source[source]["atomic"][0], atomics)] if value]
    h2 = [priority(group["atomic"][:2]) for group in by_source.values()
          if len(group["atomic"]) >= 2]
    treatment_n = min(len(h1), len(h2))
    treatment = stable(h1 if arm == "h1" else h2, f"treatment:{split}:{arm}")[:treatment_n]
    return stable(common + treatment, f"output:{split}:{arm}"), {
        "grounded": len(atomics), "no_oracle": len(common) - len(atomics),
        "treatment": treatment_n, "rows": len(common) + treatment_n}


def build_eval(rows, split):
    by_source, atomics, briefings = split_rows(rows, split)
    cases = []
    for source in stable(list(by_source), f"eval:{split}"):
        group = by_source[source]
        if group["atomic"]:
            row = group["atomic"][0]
            positive = raft(row, atomics)
            negative = no_oracle(row, atomics)
            if positive and negative:
                pair_id = digest([split, source, row["question"]])[:16]
                positive["meta"]["pair_id"] = pair_id
                negative["meta"]["pair_id"] = pair_id
                positive.pop("completion", None)
                negative.pop("completion", None)
                cases += [positive, negative]
        if group["briefing"]:
            item = briefing(group["briefing"][0])
            item.pop("completion", None)
            cases.append(item)
        if len(group["atomic"]) >= 2:
            cases += [priority(group["atomic"][:2], include_completion=False),
                      priority(group["atomic"][:2], swap=True, include_completion=False)]
    return cases


def retrieval_metrics(rows, split):
    corpus = [row for row in rows if row["kind"] == "atomic"]
    queries = [row for row in corpus if row["split"] == split]
    hits = Counter()
    for query in queries:
        qterms = terms(query["question"])
        ranked = sorted(corpus, key=lambda row: (
            -len(qterms & terms(" ".join(row["evidence"]))),
            digest([query["question"], row["source_key"], row["question"]])))
        rank = next(index for index, row in enumerate(ranked, 1)
                    if row["source_key"] == query["source_key"])
        for k in (1, 5, 10):
            hits[k] += rank <= k
    return {f"hit@{k}": hits[k] / len(queries) if queries else None for k in (1, 5, 10)}


def general_eval():
    specs = [
        ("핵심 장점 세 가지를 불릿으로만 적어줘: 로컬 AI", {"bullets": 3}),
        ("'근거'와 '검증'이라는 단어를 모두 포함해 한 문장으로 답해줘.",
         {"contains": ["근거", "검증"], "sentences": 1}),
        ("회의 준비 체크리스트를 정확히 네 항목으로 써줘.", {"bullets": 4}),
        ("JSON으로만 답해줘. 키는 question과 answer 두 개야.",
         {"json_keys": ["question", "answer"]}),
        ("두 문장으로 협업의 장점을 설명하고 마지막을 '함께 해봐요.'로 끝내줘.",
         {"sentences": 2, "endswith": "함께 해봐요."}),
        ("업무 자동화의 주의점 두 가지를 번호 목록으로만 적어줘.", {"bullets": 2}),
        ("'보안'을 포함하고 '무조건'은 쓰지 말고 한 문장으로 답해줘.",
         {"contains": ["보안"], "excludes": ["무조건"], "sentences": 1}),
        ("JSON으로만 답해줘. 키는 status와 reason이야.", {"json_keys": ["status", "reason"]}),
        ("정확히 세 문장으로 좋은 회의록의 조건을 설명해줘.", {"sentences": 3}),
        ("데이터 검증 절차를 다섯 개 불릿으로 적어줘.", {"bullets": 5}),
        ("'질문'과 '확인'을 포함해 두 문장으로 답해줘.",
         {"contains": ["질문", "확인"], "sentences": 2}),
        ("한 문장으로 답하고 마지막을 '검토가 필요해요.'로 끝내줘.",
         {"sentences": 1, "endswith": "검토가 필요해요."}),
        ("JSON으로만 답해줘. 키는 owner, action, due 세 개야.",
         {"json_keys": ["owner", "action", "due"]}),
        ("AI 도입의 장점을 정확히 네 항목의 번호 목록으로 적어줘.", {"bullets": 4}),
        ("'근거'는 포함하고 '확실히'는 제외해 한 문장으로 답해줘.",
         {"contains": ["근거"], "excludes": ["확실히"], "sentences": 1}),
        ("프로젝트 회고 질문 세 개를 불릿으로만 써줘.", {"bullets": 3}),
        ("두 문장으로 답하고 마지막을 '다시 확인할게요.'로 끝내줘.",
         {"sentences": 2, "endswith": "다시 확인할게요."}),
        ("JSON으로만 답해줘. 키는 risk와 mitigation이야.",
         {"json_keys": ["risk", "mitigation"]}),
        ("사용자 피드백을 받는 방법 두 가지를 불릿으로 적어줘.", {"bullets": 2}),
    ]
    rows = []
    for index, (question, constraints) in enumerate(specs):
        rows.append({"prompt": [{"role": "system", "content": GENERAL_SYSTEM},
                                 {"role": "user", "content": question}],
                     "question": question, "label": "general_constraint",
                     "meta": {"task": "general", "constraints": constraints,
                              "case_id": f"g-{index:02d}"}})
    return rows


def validate(train_h1, train_h2, dev_h1, dev_h2, selection, sealed, rows):
    sources = defaultdict(set)
    for row in rows:
        sources[row["split"]].add(row["source_key"])
    names = list(sources)
    overlap = sum(len(sources[a] & sources[b]) for i, a in enumerate(names) for b in names[i + 1:])
    if overlap:
        raise SystemExit(f"source split overlap: {overlap}")
    if len(train_h1) != len(train_h2) or len(dev_h1) != len(dev_h2):
        raise SystemExit("arm row counts differ")
    common_h1 = Counter(digest(row) for row in train_h1 if row["meta"]["task"] in {"grounded", "no_oracle"})
    common_h2 = Counter(digest(row) for row in train_h2 if row["meta"]["task"] in {"grounded", "no_oracle"})
    if common_h1 != common_h2:
        raise SystemExit("common training rows differ")
    for name, cases in (("selection", selection), ("sealed", sealed)):
        if not cases or any("completion" in row for row in cases):
            raise SystemExit(f"{name} is empty or contains answers")
    return {"source_overlap": overlap, "common_train_rows": sum(common_h1.values())}


def self_check():
    base = {"source_key": "a", "split": "train", "kind": "atomic", "entity": "알타바",
            "question": "알타바 계획은?", "answer": "설명회를 열기로 했어요.",
            "evidence": ["알타바 설명회를 열기로 했어요."], "evidence_ids": [1],
            "salience": "core", "commitment": "confirmed", "thread_ts": "1"}
    pool = [base] + [{**base, "source_key": str(i), "entity": f"회사{i}",
                      "question": f"회사{i} 계획은?", "evidence": [f"회사{i} 행사를 열어요."]}
                     for i in range(2, 7)]
    assert raft(base, pool)["meta"]["oracle_context_ids"]
    assert no_oracle(base, pool)["completion"][0]["content"].endswith("(근거 부족)")
    assert briefing({**base, "kind": "briefing", "evidence": ["첫째", "둘째"]})["meta"]["task"] == "briefing"
    print("SELF_CHECK_OK")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--teacher")
    parser.add_argument("--out-root")
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return
    if not args.teacher or not args.out_root:
        parser.error("--teacher and --out-root are required")
    rows = read_jsonl(args.teacher)
    root = Path(args.out_root)
    train_h1, train_h1_stats = build_train(rows, "train", "h1")
    train_h2, train_h2_stats = build_train(rows, "train", "h2")
    dev_h1, dev_h1_stats = build_train(rows, "dev", "h1")
    dev_h2, dev_h2_stats = build_train(rows, "dev", "h2")
    selection = build_eval(rows, "selection") + general_eval()
    sealed = build_eval(rows, "sealed") + general_eval()
    audit = validate(train_h1, train_h2, dev_h1, dev_h2, selection, sealed, rows)
    for arm, train, dev in (("h1", train_h1, dev_h1), ("h2", train_h2, dev_h2)):
        write_jsonl(root / arm / "train.jsonl", train)
        write_jsonl(root / arm / "dev.jsonl", dev)
    write_jsonl(root / "selection.jsonl", selection)
    write_jsonl(root / "sealed.jsonl", sealed)
    manifest = {"protocol": "v5-matched-arms-v1", "train": {
        "h1": train_h1_stats, "h2": train_h2_stats}, "dev": {
        "h1": dev_h1_stats, "h2": dev_h2_stats},
        "selection_rows": len(selection), "sealed_rows": len(sealed),
        "retrieval": {split: retrieval_metrics(rows, split)
                      for split in ("selection", "sealed")}, **audit,
        "teacher_sha256": hashlib.sha256(Path(args.teacher).read_bytes()).hexdigest()}
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
