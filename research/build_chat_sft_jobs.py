#!/usr/bin/env python3
"""
챗 답변 SFT 교사 데이터의 '입력' — 노드 가중치를 반영하고, 근거 없는 질문을 섞는다.

두 가지를 겨냥한다.

(1) 확정도 구분. 지금 챗 경로는 BM25 상위 20개를 평평하게 던져서, 32B가 내린 확정도
    판정(확정 / 조건부확정 / 의지예정 / 제안검토 …)이 놀고 있다.
    사내에서 제일 많이 묻는 건 "그거 정해진 거야, 얘기만 나온 거야?"인데 지금 모델은
    그 차이를 볼 수 없다. 사실마다 등급을 붙여 주고, 교사에게 구분해 답하라고 지시한다.
    → 학생이 "확정됐어요" vs "논의만 됐어요"를 가려 말하는 걸 배운다.

(2) 거절. 순정 1.2B는 근거가 없어도 지어냈다(거절 테스트 6건 중 4건 실패 — 무관한 결정을
    질문 주제로 갈아끼우고 실명까지 붙였다). 답변 잘한 사례만 모으면 이 능력은 안 생기므로
    답이 없는 질문을 일부러 섞는다.

등급 표기는 판정 결과(runs/node_weights.json)의 commitment를 **직접** 읽는다.

  주의 — 예전에는 DB의 weight 값을 구간으로 잘라 등급을 되돌렸다. 그건 틀린 방법이다.
  weight = commitment 계수 × salience 계수라서 곱을 되돌릴 수 없다. 실측하면 940건 중
  258건(27%)이 잘못된 등급을 달았고, 특히 `확정 × supporting = 0.75`가 전부 [조건부]로
  찍혔다 — 학습 데이터의 [조건부] 229건이 100% 오탐이고, 진짜 조건부 8건은 단 하나도
  [조건부]로 안 갔다. 라벨이 정확히 뒤집혀 있었다. weight는 검색 랭킹 정렬용으로만 쓴다.

사용: venv/bin/python build_chat_sft_jobs.py --out chat_sft_jobs.jsonl
"""
import argparse
import json
import os
import random

import psycopg2
from rank_bm25 import BM25Okapi

import korean_syntax as ks

CONTENT = ("NNG", "NNP", "NNB", "NR", "SL", "SN", "VV", "VA", "XR")

SYSTEM = """당신은 사내 지식그래프를 위키처럼 참고해 답하는 어시스턴트입니다.
아래에 주어진 '지식그래프 사실'만 근거로 답하세요. 다음을 반드시 지키세요:
- 제공된 사실에 없는 내용은 지어내지 마세요. 추측하지 마세요.
- 사실이 질문을 충분히 커버하지 못하면 "지식그래프에 아직 그 내용이 없어요"라고 솔직히 말하세요.
- 각 사실 앞의 [확정도] 표시를 반드시 반영하세요:
  · [확정] 은 팀이 합의해 실행이 전제된 것 — "~하기로 했어요"처럼 단정해 말하세요.
  · [조건부] 는 조건이 붙은 것 — 그 조건을 함께 말하세요.
  · [예정] 은 계획·의지 단계 — "~할 예정이었어요"처럼 여지를 남기세요.
  · [논의] 는 제안·검토 단계로 확정이 아닙니다 — "확정된 건 아니고 논의됐어요"라고 분명히 구분하세요.
  · [미분류] 는 아직 확정도 판정을 받지 않은 사실입니다 — 내용만 전하고 확정도를 단정하지 마세요.
- 확정된 것과 논의 중인 것이 섞여 있으면 둘을 구분해서 말하세요. 논의 단계를 확정처럼 말하면 안 됩니다.
- 답변은 한국어 해요체로, 2~5문장 이내로 간결하게.
- 근거가 된 사실을 자연스럽게 녹여 설명하되, 번호나 원문을 그대로 나열하지는 마세요."""

# 판정 라벨 → 프롬프트에 보여줄 등급. 되돌리는 게 아니라 그대로 옮긴다.
# 당위("~해야 한다")와 비결정(서술·질문)은 결정이 아니므로 확정 쪽에 붙이면 안 된다.
# 둘 다 8건뿐이라 별도 등급을 만들지 않고 [논의]로 보낸다 — "확정이 아님"은 맞게 전달된다.
GRADE_OF = {"확정": "확정", "조건부확정": "조건부", "의지예정": "예정",
            "제안검토": "논의", "당위": "논의", "비결정": "논의"}


def load_grades(path="runs/node_weights.json"):
    """(document_id, subject) → 등급. backfill_weights.py와 같은 키 규칙을 쓴다."""
    return {(m["doc_id"], "결정:" + m["topic"].strip()): GRADE_OF[m["commitment"]]
            for m in json.load(open(path))}


ABSENT_TEMPLATES = [
    "사무실 {x} 관련해서 정해진 규칙이 있나요?",
    "{x} 정책이 어떻게 바뀌었죠?",
    "{x} 관련 결정 사항을 찾아줘",
    "{x} 기준을 어떻게 잡기로 했나요?",
]
ABSENT_TOPICS = [
    "정수기 필터 교체", "주차장 배정", "점심 식대 지원", "사내 헬스장 운영",
    "코스닥 상장 목표", "노트북 교체 주기", "복장 규정", "사내 동호회 지원금",
    "출퇴근 셔틀버스", "명절 상여금", "재택근무 일수", "회의실 예약 상한",
    "사내 카페 운영", "경조사 지원 기준", "도서 구입비 한도", "자격증 응시료 지원",
]


def toks(text):
    return [m.form for m in ks.kiwi().tokenize(text or "") if m.tag in CONTENT]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--answerable", type=int, default=260)
    ap.add_argument("--top-k", type=int, default=20)
    args = ap.parse_args()

    grades = load_grades()
    conn = psycopg2.connect(host="localhost", dbname="mydoc", user="mydoc",
                            password=os.environ["MYDOC_DB_PASSWORD"])
    cur = conn.cursor()
    cur.execute("SELECT document_id, subject, predicate, object, kind, statement "
                "FROM knowledge_triple")
    rows = cur.fetchall()
    conn.close()
    bm25 = BM25Okapi([toks(f"{s} {p} {o} {st}") for d, s, p, o, k, st in rows])

    from collections import Counter
    dist = Counter(grades.get((str(d), s), "미분류") for d, s, p, o, k, st in rows)
    print(f"[corpus] 트리플 {len(rows)} · 등급 분포 {dict(dist)}")

    def facts_for(question):
        scores = bm25.get_scores(toks(question))
        top = sorted(range(len(rows)), key=lambda i: -scores[i])[:args.top_k]
        text = ""
        for n, i in enumerate(top, 1):
            d, s, p, o, k, st = rows[i]
            text += f"{n}. [{grades.get((str(d), s), '미분류')}] [{k}] {s} — {p} — {o}"
            if st:
                text += f"  ({st})"
            text += "\n"
        return text

    def job(question, label):
        return {"label": label, "question": question, "system": SYSTEM,
                "user": f"질문: {question}\n\n지식그래프 사실:\n{facts_for(question)}\n"
                        f"위 사실만 근거로, 각 사실의 확정도를 구분해서 답하세요.\n"}

    jobs, seen = [], set()
    for path in ("rl/train.jsonl", "rl/val.jsonl"):
        for line in open(path):
            q = json.loads(line)["question"]
            if q not in seen:
                seen.add(q)
                jobs.append(job(q, "answerable"))
    rng = random.Random(42)
    rng.shuffle(jobs)
    jobs = jobs[:args.answerable]

    for topic in ABSENT_TOPICS:
        for tpl in ABSENT_TEMPLATES:
            jobs.append(job(tpl.format(x=topic), "unanswerable"))

    rng.shuffle(jobs)
    with open(args.out, "w") as f:
        for j in jobs:
            f.write(json.dumps(j, ensure_ascii=False) + "\n")
    n_ans = sum(1 for j in jobs if j["label"] == "answerable")
    print(f"[jobs] 총 {len(jobs)} · 답 있음 {n_ans} · 답 없음 {len(jobs)-n_ans} → {args.out}")


if __name__ == "__main__":
    main()
