#!/usr/bin/env python3
"""
논문용 핵심 분석 — 표면 어미 규칙 vs 32B 의미 판정의 일치·괴리를 상세 집계한다.

주장하려는 것: 한국어 결정 발화의 무게는 어미 표지만으로 결정되지 않으며(표면 규칙의 한계),
LLM은 표지를 넘어 의미로 분류한다(의미 판단의 증거). 괴리 사례의 수와 내용이 그 증거다.

산출물:
  runs/weight_semantics_report.md   — 전 수치 상세 리포트 (논문 표의 원자료)
  runs/weight_divergence_cases.jsonl — 괴리 사례 전수 (질적 분석·부록용)

측정 설계 주의: 판정 대상 결정문은 Gemini가 해요체로 재작성한 것이라 원문 어미가 일부
소실돼 있다(실측: 재작성문 재현율 20.3%). 그래서 표면 규칙을 두 층으로 잰다:
  A층 — 결정문 자체의 표지 (재작성 후에도 남은 표지)
  B층 — 원문에서 내용형태소 포함도>=0.3으로 역추적한 문장들의 표지 (원문 표지)
B층이 원문 기준의 공정한 베이스라인이고, A층과 B층의 차이 자체도 재작성에 의한
표지 소실의 측정치가 된다.

사용: venv/bin/python analyze_weight_semantics.py
"""
import json
import math
from collections import Counter, defaultdict

import korean_syntax as ks
from measure_syntax_grounding import analyze_source, content_set

COMMITS = ["확정", "조건부확정", "의지예정", "제안검토", "당위", "비결정"]

# 각 표지의 언어학적 정설 등급 — 32B가 이걸 재현하는지, 언제 뒤집는지가 관심사다.
CANON = {"GIRO_HADA": "확정", "DOROK": "확정", "GESS": "의지예정",
         "YEJEONG": "의지예정", "LGE": "의지예정",
         "PROPOSITIVE": "제안검토", "NECESSITY": "당위", None: "비결정"}


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def kappa(pairs):
    """Cohen's kappa — (규칙 등급, 32B 등급) 쌍 목록."""
    n = len(pairs)
    if n == 0:
        return float("nan")
    agree = sum(1 for a, b in pairs if a == b) / n
    ca, cb = Counter(a for a, _ in pairs), Counter(b for _, b in pairs)
    expected = sum(ca[c] * cb[c] for c in set(ca) | set(cb)) / (n * n)
    return (agree - expected) / (1 - expected) if expected < 1 else float("nan")


def main():
    corpus = {json.loads(l)["doc_id"]: json.loads(l) for l in open("corpus/corpus.jsonl")}
    rows = [json.loads(l) for l in open("runs/weights_32b.jsonl")]

    # 원문 문장 분석 캐시 (B층용)
    sources = {}

    records = []          # 항목별 전체 기록
    for r in rows:
        doc_id = r["doc_id"]
        if doc_id not in sources:
            sources[doc_id] = analyze_source(corpus[doc_id]["input"])
        src = sources[doc_id]
        for j in r["judgments"]:
            if not j.get("commitment"):
                continue
            text = j["text"]
            # A층 — 결정문 자체의 표지
            m_claim = ks.modality(text, extended=True)
            # B층 — 원문 역추적 문장들의 표지 (하나라도 표지가 있으면 그 표지)
            claim_words = content_set(text)
            m_source = None
            if claim_words:
                for words, is_modal in src:
                    if len(claim_words & words) / len(claim_words) >= 0.3 and is_modal:
                        # 표지 종류까지 얻기 위해 다시 조회하지 않고 존재만 기록
                        m_source = "PRESENT"
                        break
            records.append({
                "doc_id": doc_id, "text": text,
                "commitment": j["commitment"], "salience": j["salience"],
                "marker_claim": m_claim, "marker_source": m_source,
            })

    out = []
    w = out.append
    w("# 표면 어미 규칙 vs 32B 의미 판정 — 상세 집계")
    w("")
    w(f"판정 대상: 문서 {len(rows)}개 · 결정 {len(records)}건 (Gemini 재작성 결정문)")
    w("")

    # ── 1. 분포 ──────────────────────────────────────────
    w("## 1. 32B commitment 분포")
    w("")
    w("| 등급 | 건수 | 비율 | 95% CI |")
    w("|---|---|---|---|")
    cc = Counter(x["commitment"] for x in records)
    n = len(records)
    for c in COMMITS:
        lo, hi = wilson(cc[c], n)
        w(f"| {c} | {cc[c]} | {cc[c]/n:.1%} | [{lo:.1%}, {hi:.1%}] |")
    w("")
    sc = Counter(x["salience"] for x in records)
    w(f"salience: core {sc.get('core',0)} · supporting {sc.get('supporting',0)} · "
      f"peripheral {sc.get('peripheral',0)}")
    w("")

    # ── 2. A층: 결정문 표지 × 32B ────────────────────────
    w("## 2. A층 — 결정문 자체의 표지 × 32B 판정 (교차표)")
    w("")
    cross = defaultdict(Counter)
    for x in records:
        cross[x["marker_claim"]][x["commitment"]] += 1
    markers = ["GIRO_HADA", "DOROK", "GESS", "YEJEONG", "LGE", "PROPOSITIVE",
               "NECESSITY", None]
    w("| 표지 \\ 32B | " + " | ".join(COMMITS) + " | 계 |")
    w("|---" * (len(COMMITS) + 2) + "|")
    for m in markers:
        row = cross.get(m, Counter())
        total = sum(row.values())
        if total == 0:
            continue
        name = m if m else "(표지 없음)"
        w(f"| {name} | " + " | ".join(str(row.get(c, 0)) for c in COMMITS)
          + f" | {total} |")
    w("")

    # 표지별 정설 재현율
    w("### 표지별: 언어학적 정설 등급을 32B가 재현한 비율")
    w("")
    w("| 표지 | 정설 등급 | 재현 | 전체 | 재현율 | 95% CI |")
    w("|---|---|---|---|---|---|")
    for m in markers:
        row = cross.get(m, Counter())
        total = sum(row.values())
        if total == 0:
            continue
        canon = CANON[m]
        hit = row.get(canon, 0)
        lo, hi = wilson(hit, total)
        w(f"| {m if m else '(없음)'} | {canon} | {hit} | {total} | "
          f"{hit/total:.1%} | [{lo:.1%}, {hi:.1%}] |")
    w("")

    # κ
    pairs = [(CANON[x["marker_claim"]], x["commitment"]) for x in records]
    w(f"**규칙(정설 매핑) vs 32B 전체 일치율: "
      f"{sum(1 for a,b in pairs if a==b)/len(pairs):.1%} · Cohen's κ = {kappa(pairs):.3f}**")
    w("")

    # ── 3. 괴리 사례 ─────────────────────────────────────
    w("## 3. 괴리 사례 — 논문의 핵심 증거")
    w("")
    div_marker_but_weak = [x for x in records
                           if x["marker_claim"] in ("GIRO_HADA", "DOROK")
                           and x["commitment"] in ("제안검토", "당위", "비결정")]
    div_none_but_firm = [x for x in records
                         if x["marker_claim"] is None and x["marker_source"] is None
                         and x["commitment"] == "확정"]
    div_none_claim_firm = [x for x in records
                           if x["marker_claim"] is None and x["commitment"] == "확정"]
    k1, n1 = len(div_marker_but_weak), sum(
        1 for x in records if x["marker_claim"] in ("GIRO_HADA", "DOROK"))
    lo1, hi1 = wilson(k1, n1)
    k2 = len(div_none_claim_firm)
    n2 = sum(1 for x in records if x["marker_claim"] is None)
    lo2, hi2 = wilson(k2, n2)
    w(f"- **강표지(기로하다·도록)인데 32B가 약등급(제안검토/당위/비결정)**: "
      f"{k1}/{n1} = {k1/max(n1,1):.1%} [CI {lo1:.1%}–{hi1:.1%}]")
    w(f"- **결정문에 표지가 없는데 32B가 확정**: {k2}/{n2} = {k2/max(n2,1):.1%} "
      f"[CI {lo2:.1%}–{hi2:.1%}]")
    w(f"  - 그중 원문 역추적 문장에도 표지가 없는 것(순수 의미 판단): "
      f"{len(div_none_but_firm)}건")
    w("")
    w("→ 앞 수치는 '표지가 결정을 보증하지 않는다', 뒤 수치는 '표지 없이도 결정은 성립한다'.")
    w("  두 방향 모두에서 0이 아니면 표면 규칙만으로는 가중치를 정할 수 없다는 증거가 된다.")
    w("")
    for title, cases in [("표지 있음 → 약등급 (전수)", div_marker_but_weak[:10]),
                          ("표지 없음 → 확정 (상위 10)", div_none_but_firm[:10])]:
        w(f"### {title}")
        for x in cases:
            w(f"- [{x['commitment']}] {x['text'][:80]}")
        w("")

    # ── 4. 재작성에 의한 표지 소실 (A층 vs B층) ─────────
    w("## 4. 재작성에 의한 표지 소실 측정 (부수 결과)")
    a_has = sum(1 for x in records if x["marker_claim"])
    b_has = sum(1 for x in records if x["marker_source"])
    both = sum(1 for x in records if x["marker_claim"] and x["marker_source"])
    only_b = sum(1 for x in records if not x["marker_claim"] and x["marker_source"])
    w("")
    w(f"- 결정문에 표지 잔존(A층): {a_has}/{n} = {a_has/n:.1%}")
    w(f"- 원문 역추적 문장에 표지(B층): {b_has}/{n} = {b_has/n:.1%}")
    w(f"- 원문엔 있었는데 재작성문에서 사라짐: {only_b}건 "
      f"(B층 보유분의 {only_b/max(b_has,1):.1%})")
    w("")

    # ── 4.5 문서 내 위치 × 중요도 (거리 기반 가중치의 1차 증거) ──
    w("## 4.5 문서 내 위치 × 중요도")
    w("")
    w("결정이 회의의 어느 지점에서 나오는가. 위치가 salience를 예측하면 '거리'가")
    w("가중치 자질이 된다 (시간 거리·간격 재확인은 event_at 붙여 후속 분석).")
    w("")
    pos_bins = {b: Counter() for b in ("전반", "중반", "후반")}
    for x in records:
        src = sources[x["doc_id"]]
        claim_words = content_set(x["text"])
        if not claim_words or not src:
            continue
        best_i, best = -1, 0.0
        for i, (words, _) in enumerate(src):
            ov = len(claim_words & words) / len(claim_words)
            if ov > best:
                best, best_i = ov, i
        if best < 0.3:
            continue
        rel = best_i / max(len(src) - 1, 1)
        bin_ = "전반" if rel < 1/3 else ("중반" if rel < 2/3 else "후반")
        pos_bins[bin_][x["salience"]] += 1
    w("| 위치 | core | supporting | peripheral | core 비율 |")
    w("|---|---|---|---|---|")
    for b in ("전반", "중반", "후반"):
        c = pos_bins[b]
        tot = sum(c.values())
        if tot:
            w(f"| {b} | {c.get('core',0)} | {c.get('supporting',0)} | "
              f"{c.get('peripheral',0)} | {c.get('core',0)/tot:.1%} |")
    w("")

    # ── 5. 캘리브레이션 ─────────────────────────────────
    try:
        calib = [json.loads(l) for l in open("runs/weights_calib.jsonl")]
        w("## 5. 사람 라벨 13건 캘리브레이션")
        w("")
        w("| 항목 | 사람(salience) | 32B(salience) | 32B(commitment) | 일치 |")
        w("|---|---|---|---|---|")
        match = tot = 0
        for r in calib:
            human = r.get("human", {})
            for j in r["judgments"]:
                h = human.get(j["id"])
                if not h or not j.get("salience"):
                    continue
                # 사람 라벨 체계: core/minor/wrong → 32B: core/supporting/peripheral
                mapped = {"core": "core", "minor": "supporting",
                          "wrong": "peripheral"}.get(h)
                ok = "✓" if j["salience"] == mapped else "✗"
                match += j["salience"] == mapped
                tot += 1
                w(f"| {j['text'][:36]} | {h} | {j['salience']} | {j['commitment']} | {ok} |")
        if tot:
            lo, hi = wilson(match, tot)
            w("")
            w(f"**일치 {match}/{tot} = {match/tot:.0%} [CI {lo:.0%}–{hi:.0%}]** "
              f"(n=13이라 참고치 — 방향 확인용)")
        w("")
    except FileNotFoundError:
        w("## 5. 캘리브레이션 — weights_calib.jsonl 없음 (미실행)")
        w("")

    report = "\n".join(out)
    open("runs/weight_semantics_report.md", "w").write(report)
    with open("runs/weight_divergence_cases.jsonl", "w") as f:
        for x in records:
            canon = CANON[x["marker_claim"]]
            if canon != x["commitment"]:
                f.write(json.dumps(x, ensure_ascii=False) + "\n")
    print(report)
    print(f"\n[저장] runs/weight_semantics_report.md · runs/weight_divergence_cases.jsonl")


if __name__ == "__main__":
    main()
