#!/usr/bin/env python3
"""
챗 답변 SFT 교사 데이터의 '입력' — 노드 가중치를 반영하고, 근거 없는 질문을 섞는다.

두 가지를 겨냥한다.

(1) 확정도 구분. 지금 챗 경로는 BM25 상위 20개를 평평하게 던져서, 32B가 판정해 DB에
    넣어둔 weight(확정 1.0 / 조건부 0.9 / 의지예정 0.7 / 제안검토 0.45 …)가 놀고 있다.
    사내에서 제일 많이 묻는 건 "그거 정해진 거야, 얘기만 나온 거야?"인데 지금 모델은
    그 차이를 볼 수 없다. 사실마다 등급을 붙여 주고, 교사에게 구분해 답하라고 지시한다.
    → 학생이 "확정됐어요" vs "논의만 됐어요"를 가려 말하는 걸 배운다.

(2) 거절. 순정 1.2B는 근거가 없어도 지어냈다(거절 테스트 6건 중 4건 실패 — 무관한 결정을
    질문 주제로 갈아끼우고 실명까지 붙였다). 답변 잘한 사례만 모으면 이 능력은 안 생기므로
    답이 없는 질문을 일부러 섞는다.

가중치 등급 표기는 weight 값에서 되돌린다(backfill_weights.py의 commitment×salience).

사용: venv/bin/python build_chat_sft_jobs.py --out chat_sft_jobs.jsonl
"""
import argparse
import json
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
- 확정된 것과 논의 중인 것이 섞여 있으면 둘을 구분해서 말하세요. 논의 단계를 확정처럼 말하면 안 됩니다.
- 답변은 한국어 해요체로, 2~5문장 이내로 간결하게.
- 근거가 된 사실을 자연스럽게 녹여 설명하되, 번호나 원문을 그대로 나열하지는 마세요."""

# weight = commitment 계수 × salience 계수 → 등급 라벨로 되돌린다.
def grade(weight):
    if weight is None:
        return "미분류"
    if weight >= 0.9:
        return "확정"
    if weight >= 0.75:
        return "조건부"
    if weight >= 0.5:
        return "예정"
    return "논의"


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

    conn = psycopg2.connect(host="localhost", dbname="mydoc", user="mydoc", password="changeme")
    cur = conn.cursor()
    cur.execute("SELECT subject, predicate, object, kind, statement, weight FROM knowledge_triple")
    rows = cur.fetchall()
    conn.close()
    bm25 = BM25Okapi([toks(f"{s} {p} {o} {st}") for s, p, o, k, st, w in rows])
    graded = sum(1 for r in rows if r[5] is not None)
    print(f"[corpus] 트리플 {len(rows)} · 가중치 보유 {graded} ({graded/len(rows):.0%})")

    def facts_for(question):
        scores = bm25.get_scores(toks(question))
        top = sorted(range(len(rows)), key=lambda i: -scores[i])[:args.top_k]
        text = ""
        for n, i in enumerate(top, 1):
            s, p, o, k, st, w = rows[i]
            text += f"{n}. [{grade(w)}] [{k}] {s} — {p} — {o}"
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
