#!/usr/bin/env python3
"""
syntax_grounding — 추출된 결정이 원문의 '결정 어미를 가진 문장'으로 역추적되는지 검사한다.

왜 필요한가: 학생(1.2B 청크)이 교사(Gemini)보다 결정을 1.35배 많이 뽑는데, 이게
(a) 더 잘게 뽑는 입도 차이인지 (b) 없는 결정을 지어낸 환각인지 자동 지표로는 구분이 안 됐다.
한국어는 교착어라 "무엇을 하기로 했는가"가 어미에 실리므로, 추출된 결정을 원문 문장에
되짚어 그 문장이 결정 어미를 갖는지 보면 라벨 없이 충실성을 근사할 수 있다.

측정 방식:
  1. 추출된 결정문 d와 원문 문장 s의 내용형태소(체언·용언) 포함도를 잰다
     containment(d,s) = |content(d) ∩ content(s)| / |content(d)|
     LLM이 재작성해 표층이 달라져도 내용어는 남으므로 표층 일치보다 견고하다.
  2. containment >= θ 인 문장을 d의 근거집합으로 본다.
     grounded       = 근거집합이 비지 않음      (원문에 걸치기라도 하는가)
     modal_grounded = 근거집합에 결정 어미 문장이 있음  (결정 발화에 걸치는가)

주의: 이건 정답 라벨이 아니라 근사다. 어미 없는 평서형 결정("…제외하는 기조로 결정해요")은
modal_grounded에서 누락된다 — 그래서 교사·학생을 같은 잣대로 재고 '차이'를 본다.

사용: python measure_syntax_grounding.py --corpus corpus/val_only.jsonl \
        --runs runs/student_val_gate_off.jsonl runs/student_val_gate_hint.jsonl
"""
import argparse
import json

import korean_syntax as ks

CONTENT_TAGS = ("NNG", "NNP", "NNB", "NR", "SL", "SN", "VV", "VA", "XR")


def content_set(text):
    return {m.form for m in ks.kiwi().tokenize(text or "") if m.tag in CONTENT_TAGS}


def analyze_source(text):
    """원문 문장별로 (내용형태소 집합, 결정어미 여부)를 미리 계산한다."""
    out = []
    for s in ks.sentences(text):
        out.append((content_set(s), ks.modality(s, extended=True) is not None))
    return out


def score(decisions, source, theta):
    grounded = modal = 0
    for d in decisions:
        claim = content_set(str(d.get("decision", "")))
        if not claim:
            continue
        support = [is_modal for words, is_modal in source
                   if len(claim & words) / len(claim) >= theta]
        if support:
            grounded += 1
            if any(support):
                modal += 1
    return grounded, modal


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--runs", nargs="+", required=True)
    ap.add_argument("--thetas", nargs="+", type=float, default=[0.3, 0.5])
    args = ap.parse_args()

    corpus = {json.loads(l)["doc_id"]: json.loads(l) for l in open(args.corpus)}
    sources = {k: analyze_source(v["input"]) for k, v in corpus.items()}

    # 교사(Gemini)를 같은 잣대로 먼저 재서 기준선을 만든다.
    systems = [("teacher(Gemini)", {k: v["target"]["decisionPoints"] for k, v in corpus.items()})]
    for path in args.runs:
        rows = [json.loads(l) for l in open(path)]
        name = path.split("/")[-1].replace(".jsonl", "")
        systems.append((name, {r["doc_id"]: (r["output"] or {}).get("decisionPoints", [])
                               for r in rows}))

    for theta in args.thetas:
        print(f"\n=== θ={theta} (내용형태소 포함도 기준) ===")
        print(f"{'system':28s} {'결정':>5s} {'grounded':>18s} {'modal_grounded':>18s}")
        for name, by_doc in systems:
            total = g_sum = m_sum = 0
            for doc_id, decisions in by_doc.items():
                if doc_id not in sources:
                    continue
                total += len([d for d in decisions if content_set(str(d.get("decision", "")))])
                g, m = score(decisions, sources[doc_id], theta)
                g_sum += g
                m_sum += m
            if total == 0:
                continue
            print(f"{name:28s} {total:5d} {g_sum:8d} ({g_sum/total:5.1%}) "
                  f"{m_sum:8d} ({m_sum/total:5.1%})")


if __name__ == "__main__":
    main()
