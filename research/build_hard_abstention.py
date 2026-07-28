#!/usr/bin/env python3
"""
어려운 거절 사례 생성 — 답이 있는 질문에서 정답 근거만 제거한다.

왜 필요한가: 지금 거절 데이터는 "정수기 필터", "주차장 배정"처럼 그래프에 아예 없는
주제다. 이런 쉬운 부정 사례만 학습하면 학생은 "낯선 주제 = 거절"이라는 얕은 규칙을 배우고,
실제로 위험한 상황 — 관련은 있는데 답이 없는 경우 — 은 여전히 지어낸다. 문헌도 같은 경고를
한다(OCC-RAG는 검증기가 답을 못 찾을 때까지 문맥을 깎아 부정 사례를 '구성'했다).

방식: 답 있는 질문의 골드 노드 트리플만 제외하고 BM25 상위를 채운다. 질문 주제와 어휘는
그대로 겹치지만 답은 없는 상태 — 모델이 가장 지어내기 쉬운 조건이다.

부작용 경계: 과잉 거절. 그래서 답 있는 사례를 그대로 유지한 짝 평가셋도 같이 낸다.

사용: venv/bin/python build_hard_abstention.py --out hard_abstention_jobs.jsonl
"""
import argparse
import json
import os
import random

import psycopg2
from rank_bm25 import BM25Okapi

import build_chat_sft_jobs
import korean_syntax as ks

CONTENT = ("NNG", "NNP", "NNB", "NR", "SL", "SN", "VV", "VA", "XR")


def toks(text):
    return [m.form for m in ks.kiwi().tokenize(text or "") if m.tag in CONTENT]


# 등급은 판정 결과에서 직접 읽는다. weight 역산은 곱을 되돌릴 수 없어 27%가 틀렸다
# — 자세한 이유는 build_chat_sft_jobs.py 문서화 참고.
GRADE_OF = build_chat_sft_jobs.GRADE_OF
load_grades = build_chat_sft_jobs.load_grades


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--eval-out", default="hard_abstention_eval.jsonl")
    ap.add_argument("--n", type=int, default=90)
    ap.add_argument("--top-k", type=int, default=20)
    args = ap.parse_args()

    grades = load_grades()
    conn = psycopg2.connect(host="localhost", dbname="mydoc", user="mydoc",
                            password=os.environ["MYDOC_DB_PASSWORD"])
    cur = conn.cursor()
    cur.execute("""SELECT document_id, subject, predicate, object, kind, statement
                   FROM knowledge_triple""")
    rows = cur.fetchall()
    conn.close()
    bm25 = BM25Okapi([toks(f"{s} {p} {o} {st}") for _, s, p, o, k, st in rows])

    # 노드 키 → 트리플 인덱스 (골드 제거용)
    node_rows = {}
    for i, (did, sub, *_rest) in enumerate(rows):
        node_rows.setdefault((str(did), sub), []).append(i)

    questions = []
    for path in ("rl/train.jsonl", "rl/val.jsonl"):
        for line in open(path):
            r = json.loads(line)
            questions.append((r["question"], (r["doc_id"], r["subject"])))
    rng = random.Random(7)
    rng.shuffle(questions)

    def facts(question, exclude):
        scores = bm25.get_scores(toks(question))
        order = sorted(range(len(rows)), key=lambda i: -scores[i])
        picked, text = 0, ""
        for i in order:
            if i in exclude or scores[i] <= 0:
                continue
            did, s, p, o, k, st = rows[i]
            picked += 1
            text += f"{picked}. [{grades.get((str(did), s), '미분류')}] [{k}] {s} — {p} — {o}"
            if st:
                text += f"  ({st})"
            text += "\n"
            if picked >= args.top_k:
                break
        return text

    hard, paired = [], []
    for question, key in questions[:args.n]:
        gold = set(node_rows.get((key[0], key[1]), []))
        if not gold:
            continue
        # 어려운 거절: 골드만 빼고 나머지 상위 — 주제는 겹치는데 답은 없다
        hard.append({"label": "unanswerable_hard", "question": question,
                     "facts": facts(question, gold)})
        # 짝: 같은 질문에 골드를 남긴 판 — 과잉 거절 측정용
        paired.append({"label": "answerable_paired", "question": question,
                       "facts": facts(question, set())})

    with open(args.out, "w") as f:
        for h in hard:
            f.write(json.dumps(h, ensure_ascii=False) + "\n")
    with open(args.eval_out, "w") as f:
        for h, p in zip(hard, paired):
            f.write(json.dumps(h, ensure_ascii=False) + "\n")
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    print(f"[hard] 어려운 거절 {len(hard)}건 → {args.out}")
    print(f"[eval] 짝 평가셋 {len(hard)*2}건 (거절/정답 쌍) → {args.eval_out}")
    print("  짝 평가로 지어냄률과 과잉거절률을 함께 재야 한다 — 한쪽만 보면 속는다.")


if __name__ == "__main__":
    main()
