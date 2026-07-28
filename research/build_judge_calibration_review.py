#!/usr/bin/env python3
"""Build a blinded 100-response review page for calibrating the 32B judge."""
import argparse
import hashlib
import json
import random
from collections import defaultdict
from pathlib import Path


def sample(judged, n, seed):
    groups = defaultdict(list)
    for target, result in judged.items():
        if target == "_best":
            continue
        arm = target.split("/", 1)[0]
        for index, row in enumerate(result["rows"]):
            label = "unanswerable" if row["label"].startswith("unanswerable") else "answerable"
            groups[(arm, label, row["response"])].append((target, index, row))
    if n < len(groups):
        raise ValueError(f"n={n} is smaller than {len(groups)} strata")
    rng = random.Random(seed)
    selected, remaining = [], []
    for group in groups.values():
        rng.shuffle(group)
        selected.append(group[0])
        remaining.extend(group[1:])
    rng.shuffle(remaining)
    selected += remaining[:n - len(selected)]
    rng.shuffle(selected)
    return selected, groups


PAGE = r'''<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>32B 판정기 블라인드 검증</title>
<style>:root{--bg:#f4f6f7;--card:#fff;--ink:#17202a;--line:#d8dee2;--accent:#176b87;--muted:#68757e}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.6 -apple-system,"Apple SD Gothic Neo",sans-serif}
header{position:sticky;top:0;background:#f4f6f7ee;border-bottom:1px solid var(--line);padding:14px 20px;z-index:2}
.bar,.wrap{max-width:1080px;margin:auto}.bar{display:flex;gap:14px;align-items:center}.bar b{font-size:16px}.spacer{flex:1}
button,textarea{font:inherit}.export,.choice,.nav{border:1px solid var(--line);border-radius:8px;background:#fff;padding:9px 12px;cursor:pointer}
.export{background:var(--ink);color:#fff;border:0}.wrap{padding:22px;display:grid;grid-template-columns:1fr 1fr;gap:16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:20px}.question{font-size:19px;font-weight:700;margin-bottom:18px}
.facts,.answer{white-space:pre-wrap;max-height:45vh;overflow:auto}.answer{border-left:3px solid var(--accent);padding-left:14px}
.label{font-size:12px;color:var(--muted);display:block;margin:16px 0 7px}.choices{display:flex;flex-wrap:wrap;gap:7px}
.choice[aria-pressed=true]{background:#e8f2f5;border-color:var(--accent);color:var(--accent);font-weight:700}textarea{width:100%;min-height:72px;border:1px solid var(--line);border-radius:8px;padding:9px}
.navs{display:flex;gap:8px;margin-top:18px}.nav{flex:1}.progress{color:var(--muted)}
@media(max-width:760px){.wrap{grid-template-columns:1fr;padding:12px}}</style></head><body>
<header><div class="bar"><b>32B 판정기 블라인드 검증</b><span class="progress" id="progress"></span><span class="spacer"></span><button class="export" id="export">내보내기</button></div></header>
<main class="wrap"><section class="card" id="case"></section><section class="card"><span class="label">모델에게 주어진 사실</span><div class="facts" id="facts"></div><span class="label">모델 답변</span><div class="answer" id="answer"></div></section></main>
<script id="payload" type="application/json">__PAYLOAD__</script><script>
const D=JSON.parse(document.getElementById('payload').textContent),K='v4-judge-calibration-v1';let S=JSON.parse(localStorage.getItem(K)||'{}'),i=0;
const esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function save(p){S[D[i].audit_id]={...(S[D[i].audit_id]||{}),...p};localStorage.setItem(K,JSON.stringify(S));render()}
function buttons(name,values){let v=(S[D[i].audit_id]||{})[name];return `<div class="choices">${values.map(x=>`<button class="choice" data-name="${name}" data-value="${x}" aria-pressed="${v===x}">${x}</button>`).join('')}</div>`}
function render(){let x=D[i],s=S[x.audit_id]||{};document.getElementById('case').innerHTML=`<div class="question">${esc(x.question)}</div><span class="label">답변 성격</span>${buttons('response',['거절','부분답변','답변'])}<span class="label">사실에 없는 구체 내용을 만들었나?</span>${buttons('fabricated',['예','아니오'])}<span class="label">확정도를 어조·설명으로 반영했나?</span>${buttons('grade_reflected',['예','아니오'])}<span class="label">메모</span><textarea id="notes">${esc(s.notes||'')}</textarea><div class="navs"><button class="nav" id="prev">← 이전</button><button class="nav" id="next">다음 →</button></div>`;document.getElementById('facts').textContent=x.facts;document.getElementById('answer').textContent=x.answer;document.querySelectorAll('.choice').forEach(b=>b.onclick=()=>save({[b.dataset.name]:b.dataset.value}));document.getElementById('notes').onchange=e=>save({notes:e.target.value});document.getElementById('prev').onclick=()=>go(-1);document.getElementById('next').onclick=()=>go(1);let done=Object.values(S).filter(x=>x.response&&x.fabricated&&x.grade_reflected).length;document.getElementById('progress').textContent=`${i+1}/${D.length} · 완료 ${done}`}
function go(d){i=Math.max(0,Math.min(D.length-1,i+d));render();scrollTo(0,0)}
document.getElementById('export').onclick=()=>{let rows=D.map(x=>({audit_id:x.audit_id,...(S[x.audit_id]||{})}));let a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(rows,null,2)],{type:'application/json'}));a.download='v4_judge_human_labels.json';a.click()};render();
</script></body></html>'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--judged", required=True)
    ap.add_argument("--html-out", required=True)
    ap.add_argument("--key-out", required=True)
    ap.add_argument("--n", type=int, default=100)
    ap.add_argument("--seed", type=int, default=20260728)
    args = ap.parse_args()
    source = Path(args.judged)
    selected, groups = sample(json.loads(source.read_text()), args.n, args.seed)
    blind, key = [], []
    for number, (target, index, row) in enumerate(selected, 1):
        audit_id = f"judge-{number:03d}"
        blind.append({"audit_id": audit_id, "question": row["question"],
                      "facts": row["facts"], "answer": row["answer"]})
        key.append({"audit_id": audit_id, "target": target, "row_index": index,
                    "source_label": row["label"], "judge_response": row["response"],
                    "judge_fabricated": row["fabricated"],
                    "judge_grade_reflected": row["grade_reflected"]})
    payload = json.dumps(blind, ensure_ascii=False).replace("</script>", "<\\/script>")
    Path(args.html_out).write_text(PAGE.replace("__PAYLOAD__", payload))
    Path(args.key_out).write_text(json.dumps({
        "protocol": "v4-judge-human-calibration-v1", "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "seed": args.seed, "n": len(blind), "strata": len(groups), "rows": key,
    }, ensure_ascii=False, indent=2) + "\n")
    print(f"[done] blinded={len(blind)} strata={len(groups)} → {args.html_out}")


if __name__ == "__main__":
    main()
