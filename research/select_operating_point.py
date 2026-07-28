#!/usr/bin/env python3
"""
운영점 선택 — 스칼라화 지표(Honesty) 대신 제약 하 인증(certification)으로 고른다.

## 왜 바꾸는가

지금까지 체크포인트를 Honesty = ½(P + (1−C))의 argmax로 골랐다. P는 Prudence,
C는 과잉거절이다. 이를 오류율로 다시 쓰면

    FAR = 1 − P   (거절해야 하는데 답한 비율, failure-to-abstain rate)
    FRR = C       (답할 수 있는데 거절한 비율, false-refusal rate)
    Honesty = 1 − ½(FAR + FRR)

즉 Honesty는 **두 오류에 같은 가중치를 준 선형 스칼라화**다. 이 선택에는 두 가정이
숨어 있고 둘 다 배포 환경에서 성립하지 않는다.

  (가정1) 두 오류의 비용이 같다. — 사내 위키 QA에서 "답이 있는데 거절"은 매번 사용자가
     체감하는 반면 "답이 없는데 답함"은 드물게 발생한다. 비용이 같을 이유가 없다.
  (가정2) 두 오류가 같은 빈도로 노출된다. — Honesty는 두 조건부 오류율의 단순 평균이라
     **평가셋의 클래스 비율과 무관**한 값이다. 그런데 실제 사용자가 겪는 기대 손실은
     유병률 π = P(답 없음)에 따라 달라진다. 우리 평가셋은 π=0.38인데, 실사용에서
     "그래프에 답이 아예 없는 질문"의 비율이 38%일 리 없다.

실측이 이 문제를 그대로 보여줬다(docs/RESEARCH-loop-log.md D-3). v2 테스트에서
Honesty가 고른 step57은 과잉거절 22.9%였는데, 같은 학습의 step342는 과잉거절 8.0%에
확정도 반영 84.6%로 제품 요구에 훨씬 가까웠다. **선택 지표가 제품 목표와 어긋나 있었다.**

## 무엇으로 바꾸는가

Neyman-Pearson 구도로 바꾼다: **한쪽 오류를 제약으로 묶고 다른 쪽을 최소화한다.**

    minimize FAR(λ)   subject to   FRR(λ) ≤ α

문제는 유한 표본이다. 테스트셋에서 FRR̂ = 8.0%라고 해서 모집단 FRR ≤ 10%가 아니다.
게다가 후보 7개 중 최소를 고르므로 승자의 저주(winner's curse)가 붙는다.

그래서 Learn-then-Test(Angelopoulos et al. 2021)를 쓴다. 체크포인트 선택을 **다중가설
검정**으로 놓는다:

    각 후보 λ에 대해  H_λ : FRR(λ) > α   (위험이 기준을 넘는다)
    이를 기각하면 λ는 "인증됨(certified)"
    FWER를 δ로 통제하면, 인증된 집합에서 무엇을 고르든 P(FRR > α) ≤ δ

p-값은 이항 정확검정을 쓴다. 손실이 {0,1} 유계이므로 n·FRR̂ ~ Bin(n, FRR)이고

    p_λ = P(Bin(n, α) ≤ n·FRR̂(λ))

관측 위험이 α보다 충분히 낮을 때만 작아진다. Bonferroni로 FWER를 통제한다(후보가
에폭 순서로 정렬되어 있으므로 fixed-sequence가 더 강력하지만, 순서 가정을 하지
않으려고 Bonferroni를 쓴다 — 보수적인 쪽).

**중요**: LTT의 보증은 **제약된 위험(FRR)에만** 걸린다. 인증된 집합 안에서 FAR 최소를
고르는 2차 선택에는 여전히 승자의 저주가 있다. 그래서 FAR은 점추정과 함께 신뢰구간을
보고하고, "인증된 FRR"과 "추정된 FAR"을 구분해 표기한다.

## 무엇을 함께 보고하는가

1. **파레토 프론티어** — (FAR, FRR) 평면에서 지배당하지 않는 체크포인트. Honesty의
   argmax는 이 프론티어 위의 한 점일 뿐이고, 어느 점인지는 λ=0.5 스칼라화가 정한다.
2. **유병률 민감도** — 기대 손실 π·FAR + (1−π)·c·FRR 을 π에 대해 훑어, 각 체크포인트가
   최적이 되는 π 구간을 낸다. Honesty는 이 곡선 위의 한 점(π=0.5, c=1)에 해당한다.
3. **페어드 부트스트랩** — 같은 재표집을 모든 체크포인트에 적용해 선택 절차 자체의
   안정성을 잰다. "다시 뽑으면 같은 체크포인트를 고를 확률"이 선택의 신뢰도다.

사용:
  python select_operating_point.py --judged runs/xcheck_judged.json runs/chat_test_judged_v3.json \
      --names v2 v3 --alpha 0.10 --delta 0.05
"""
import argparse
import json
import math
import random
from statistics import NormalDist

ND = NormalDist()


def wilson(k, n, conf=0.95):
    """Wilson score 구간. 비율이 0이나 1에 가까울 때 정규근사보다 정확하다."""
    if n == 0:
        return (float("nan"), float("nan"))
    z = ND.inv_cdf(1 - (1 - conf) / 2)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def binom_cdf(k, n, p):
    """P(Bin(n,p) ≤ k). n≤수백이므로 직접 합산으로 충분하다."""
    if k < 0:
        return 0.0
    if k >= n:
        return 1.0
    total = 0.0
    for i in range(int(k) + 1):
        total += math.comb(n, i) * (p ** i) * ((1 - p) ** (n - i))
    return min(1.0, total)


def ltt_pvalue(k, n, alpha):
    """H_0: FRR > alpha 에 대한 이항 정확 p-값. k는 관측된 오류 수."""
    return binom_cdf(k, n, alpha)


def binomial_upper(k, n, tail):
    """One-sided exact upper bound: P(Bin(n, p) <= k) = tail."""
    if n == 0 or k >= n:
        return 1.0
    low, high = k / n, 1.0
    for _ in range(60):
        mid = (low + high) / 2
        if binom_cdf(k, n, mid) > tail:
            low = mid
        else:
            high = mid
    return high


def is_base(name):
    return name.rsplit("/", 1)[-1].startswith("base")


def rates(rows):
    """(FAR, FRR, 카운트). 판정 라벨 기준 — judge_answers.py의 정의와 동일하게 맞춘다."""
    neg = [r for r in rows if r["label"].startswith("unanswerable")]
    pos = [r for r in rows if not r["label"].startswith("unanswerable")]
    # 거절해야 할 때 거절(부분답변 포함) 실패 = FAR
    far_k = sum(r["response"] not in ("거절", "부분답변") for r in neg)
    # 답할 수 있는데 완전 거절 = FRR
    frr_k = sum(r["response"] == "거절" for r in pos)
    return {"far_k": far_k, "n_neg": len(neg), "frr_k": frr_k, "n_pos": len(pos),
            "far": far_k / len(neg) if neg else float("nan"),
            "frr": frr_k / len(pos) if pos else float("nan"),
            "grade": (sum(r["grade_reflected"] for r in pos) / len(pos)
                      if pos else float("nan"))}


def pareto(points):
    """(FAR, FRR) 둘 다 작을수록 좋다. 지배당하지 않는 이름 집합을 낸다."""
    out = []
    for name, m in points:
        dominated = any(o["far"] <= m["far"] and o["frr"] <= m["frr"]
                        and (o["far"] < m["far"] or o["frr"] < m["frr"])
                        for n2, o in points if n2 != name)
        if not dominated:
            out.append(name)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--judged", required=True, nargs="+", help="judge_answers.py 출력들")
    ap.add_argument("--names", required=True, nargs="+", help="각 파일의 실행 이름")
    ap.add_argument("--alpha", type=float, default=0.10, help="FRR 상한(제품 요구)")
    ap.add_argument("--delta", type=float, default=0.05, help="FWER")
    ap.add_argument("--boot", type=int, default=2000)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    assert len(args.judged) == len(args.names)

    runs = {}
    for path, name in zip(args.judged, args.names):
        d = json.load(open(path))
        runs[name] = {k: v["rows"] for k, v in d.items() if k != "_best"}

    report = {"alpha": args.alpha, "delta": args.delta, "runs": {}}
    for run, ckpts in runs.items():
        pts = [(k, rates(v)) for k, v in ckpts.items()]
        n_cand = sum(1 for k, _ in pts if not is_base(k))
        thresh = args.delta / max(n_cand, 1)          # Bonferroni

        print(f"\n{'='*86}\n[{run}]  α(FRR 상한)={args.alpha:.0%} · δ={args.delta} "
              f"· 후보 {n_cand} · Bonferroni 임계 p<{thresh:.4f}\n{'='*86}")
        print(f"{'체크포인트':12s} {'FAR':>15s} {'FRR':>15s} {'p(LTT)':>9s} {'인증':>5s} "
              f"{'확정도':>7s} {'Honesty':>8s}")

        certified = []
        for name, m in sorted(pts, key=lambda x: (x[0].startswith("base") is False, x[0])):
            p = ltt_pvalue(m["frr_k"], m["n_pos"], args.alpha)
            ok = (p < thresh) and not is_base(name)
            fa_lo, fa_hi = wilson(m["far_k"], m["n_neg"])
            fr_lo, fr_hi = wilson(m["frr_k"], m["n_pos"])
            honesty = 1 - 0.5 * (m["far"] + m["frr"])
            print(f"{name:12s} {m['far']:6.1%}[{fa_lo:.2f},{fa_hi:.2f}] "
                  f"{m['frr']:6.1%}[{fr_lo:.2f},{fr_hi:.2f}] {p:9.4f} {'✓' if ok else '·':>5s} "
                  f"{m['grade']:7.1%} {honesty:8.3f}")
            if ok:
                certified.append((name, m))

        h_best = max((x for x in pts if not is_base(x[0])),
                     key=lambda x: 1 - 0.5 * (x[1]["far"] + x[1]["frr"]))
        front = pareto([x for x in pts if not is_base(x[0])])

        print(f"\n  파레토 프론티어: {', '.join(front)}")
        print(f"  Honesty argmax : {h_best[0]} "
              f"(FRR {h_best[1]['frr']:.1%} — 제약 {'만족' if h_best[1]['frr'] <= args.alpha else '위반'})")
        if certified:
            pick = min(certified, key=lambda x: x[1]["far"])
            lo, hi = wilson(pick[1]["far_k"], pick[1]["n_neg"])
            print(f"  LTT 인증 선택   : {pick[0]} · FAR {pick[1]['far']:.1%} [{lo:.1%},{hi:.1%}] "
                  f"· FRR {pick[1]['frr']:.1%} (≤{args.alpha:.0%} 인증됨) "
                  f"· 확정도 {pick[1]['grade']:.1%}")
            print(f"    ※ FRR만 보증된다. FAR은 인증 집합 {len(certified)}개 중 최소를 고른 값이라"
                  f" 승자의 저주가 남아 있다 — 위 구간과 함께 읽을 것")
        else:
            pick = None
            print(f"  LTT 인증 선택   : **없음** — FRR ≤ {args.alpha:.0%}를 유의하게 만족하는 "
                  f"체크포인트가 하나도 없다")

        # 유병률 민감도: 기대 손실 π·FAR + (1−π)·FRR (비용 동일 가정)
        print(f"\n  유병률 π = P(답 없음) 별 기대손실 최소 체크포인트 (비용 동일 가정):")
        prev_pi = None
        for pi in [0.05, 0.10, 0.20, 0.30, 0.377, 0.50]:
            best = min((x for x in pts if not is_base(x[0])),
                       key=lambda x: pi * x[1]["far"] + (1 - pi) * x[1]["frr"])
            mark = "  ← 우리 평가셋" if abs(pi - 0.377) < 1e-6 else ""
            star = "  ← Honesty가 가정하는 지점" if abs(pi - 0.5) < 1e-6 else ""
            print(f"    π={pi:5.1%} → {best[0]:10s} "
                  f"(손실 {pi*best[1]['far']+(1-pi)*best[1]['frr']:.3f}){mark}{star}")
            prev_pi = pi

        # 페어드 부트스트랩: 선택 절차의 안정성
        rng = random.Random(2026)
        names_l = [k for k in ckpts if not is_base(k)]
        n = len(next(iter(ckpts.values())))
        picks = {}
        for _ in range(args.boot):
            idx = [rng.randrange(n) for _ in range(n)]
            best_name, best_far = None, float("inf")
            for nm in names_l:
                rows = [ckpts[nm][i] for i in idx]
                m = rates(rows)
                if m["n_pos"] == 0 or m["n_neg"] == 0:
                    continue
                if ltt_pvalue(m["frr_k"], m["n_pos"], args.alpha) < thresh and m["far"] < best_far:
                    best_name, best_far = nm, m["far"]
            picks[best_name] = picks.get(best_name, 0) + 1
        top = sorted(picks.items(), key=lambda x: -x[1])[:4]
        print(f"\n  선택 안정성 (페어드 부트스트랩 {args.boot}회):")
        for nm, c in top:
            print(f"    {str(nm):10s} {c/args.boot:5.1%}" + ("   ← 인증 없음" if nm is None else ""))

        report["runs"][run] = {
            "checkpoints": {nm: m for nm, m in pts},
            "pareto": front, "honesty_argmax": h_best[0],
            "certified": [nm for nm, _ in certified],
            "ltt_pick": pick[0] if pick else None,
            "bootstrap": {str(k): v / args.boot for k, v in picks.items()},
        }

    # ---- 실행 간 통합 비교: 배포할 단 하나를 고르는 문제 ----
    allpts = [(f"{run}/{nm}", m) for run, cks in runs.items()
              for nm, m in ((k, rates(v)) for k, v in cks.items())
              if not is_base(nm)]
    print(f"\n{'='*86}\n[통합] 모든 실행의 체크포인트를 한 후보 풀로 놓고 비교\n{'='*86}")
    print(f"  파레토 프론티어: {', '.join(sorted(pareto(allpts)))}")
    global_threshold = args.delta / len(allpts)
    global_certified = []
    print(f"\n  12후보 통합 인증 (Bonferroni p<{global_threshold:.5f}):")
    for name, m in sorted(allpts):
        p = ltt_pvalue(m["frr_k"], m["n_pos"], args.alpha)
        upper = binomial_upper(m["frr_k"], m["n_pos"], global_threshold)
        ok = p < global_threshold
        print(f"    {name:24s} FRR {m['frr']:5.1%} · one-sided UCB {upper:5.1%} "
              f"· {'인증' if ok else '미인증'}")
        if ok:
            global_certified.append((name, m))
    global_pick = min(global_certified, key=lambda x: x[1]["far"]) if global_certified else None
    print("  통합 선택: " + (f"{global_pick[0]} (FAR {global_pick[1]['far']:.1%})"
                         if global_pick else "없음 — 봉인셋을 열지 않음"))
    print(f"\n  유병률별 기대손실 최소 (실행 무관, 비용 동일):")
    for pi in [0.05, 0.10, 0.20, 0.30, 0.377, 0.50]:
        b = min(allpts, key=lambda x: pi * x[1]["far"] + (1 - pi) * x[1]["frr"])
        print(f"    π={pi:5.1%} → {b[0]:14s} 손실 {pi*b[1]['far']+(1-pi)*b[1]['frr']:.3f} "
              f"(FAR {b[1]['far']:.1%} · FRR {b[1]['frr']:.1%} · 확정도 {b[1]['grade']:.1%})")

    # ---- 지금 표본으로 인증 가능한 가장 엄격한 α ----
    print(f"\n  현재 표본으로 인증 가능한 가장 낮은 α (δ={args.delta}, Bonferroni):")
    n_all = len(allpts)
    for a in [0.10, 0.15, 0.20, 0.25, 0.30]:
        th = args.delta / n_all
        cert = [(nm, m) for nm, m in allpts
                if ltt_pvalue(m["frr_k"], m["n_pos"], a) < th]
        if cert:
            best = min(cert, key=lambda x: x[1]["far"])
            print(f"    α={a:.0%} → 인증 {len(cert):2d}개 · 최적 {best[0]:14s} "
                  f"FAR {best[1]['far']:.1%} · FRR {best[1]['frr']:.1%}")
        else:
            print(f"    α={a:.0%} → 인증 0개")

    # ---- 검정력 분석: α=10% 인증에 필요한 표본 수 ----
    print(f"\n  검정력 분석 — FRR ≤ {args.alpha:.0%} 인증에 필요한 응답대상 표본 수")
    print(f"    (참 FRR이 아래 값일 때, p < δ/후보수 를 만족하려면)")
    th = args.delta / n_all
    for true_frr in [0.04, 0.06, 0.08]:
        need = None
        for n in range(50, 4001, 10):
            k = int(math.floor(true_frr * n))       # 기대 오류 수
            if ltt_pvalue(k, n, args.alpha) < th:
                need = n
                break
        cur = allpts[0][1]["n_pos"]
        print(f"      참 FRR {true_frr:.0%} → n ≥ {need if need else '>4000'} "
              f"(현재 {cur}) {'✓ 충분' if need and need <= cur else '✗ 부족'}")

    if args.out:
        report["global"] = {
            "candidate_count": len(allpts), "bonferroni_threshold": global_threshold,
            "certified": [name for name, _ in global_certified],
            "pick": global_pick[0] if global_pick else None,
        }
        json.dump(report, open(args.out, "w"), ensure_ascii=False, indent=1)
        print(f"\n[저장] {args.out}")


if __name__ == "__main__":
    main()
