#!/usr/bin/env python3
"""Build a local, blinded review page for chat answerability gold labels.

The source may contain old labels and 32B judgments. They are deliberately omitted
from the HTML: reviewers only see the question, supplied facts, and Codex's proposal.

Usage:
  python research/build_chat_gold_review.py \
    --judged research/runs/xcheck_judged.json --target step342 \
    --proposals research/runs/chat_gold_proposals.jsonl \
    --out research/runs/chat_gold_review.html
"""
import argparse
import hashlib
import html
import json
import re
from pathlib import Path


VERDICTS = {"answerable", "partial", "unanswerable", "ambiguous"}
CONFIDENCE = {"high", "medium", "low"}
FACT_RE = re.compile(r"^(\d+)\.\s+(.+)$")


def case_id(index, question, facts):
    digest = hashlib.sha256(f"{question}\n{facts}".encode()).hexdigest()[:10]
    return f"case-{index:03d}-{digest}"


def fact_rows(facts):
    rows = []
    for line in facts.splitlines():
        match = FACT_RE.match(line.strip())
        if match:
            rows.append({"number": int(match.group(1)), "text": match.group(2)})
    return rows


def load_cases(path, target):
    data = json.loads(Path(path).read_text())
    rows = data[target]["rows"]
    return [{
        "id": case_id(i, row["question"], row["facts"]),
        "question": row["question"],
        "facts": fact_rows(row["facts"]),
    } for i, row in enumerate(rows)]


def load_jsonl_cases(path):
    rows = [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]
    cases = []
    for i, row in enumerate(rows):
        user = next(message["content"] for message in row["prompt"] if message["role"] == "user")
        cases.append({"id": case_id(i, row["question"], user), "question": row["question"],
                      "facts": fact_rows(user)})
    return cases


def load_proposals(path):
    if not path or not Path(path).exists():
        return {}
    proposals = {}
    for line_no, line in enumerate(Path(path).read_text().splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        if row["id"] in proposals:
            raise ValueError(f"duplicate proposal id at line {line_no}: {row['id']}")
        proposals[row["id"]] = row
    return proposals


def validate(cases, proposals):
    known = {case["id"]: case for case in cases}
    errors = []
    for proposal_id, proposal in proposals.items():
        case = known.get(proposal_id)
        if not case:
            errors.append(f"unknown case id: {proposal_id}")
            continue
        if proposal.get("verdict") not in VERDICTS:
            errors.append(f"{proposal_id}: invalid verdict")
        if proposal.get("confidence") not in CONFIDENCE:
            errors.append(f"{proposal_id}: invalid confidence")
        if not str(proposal.get("rationale", "")).strip():
            errors.append(f"{proposal_id}: rationale is required")
        valid_numbers = {row["number"] for row in case["facts"]}
        evidence = proposal.get("evidence", [])
        if any(not isinstance(number, int) or number not in valid_numbers for number in evidence):
            errors.append(f"{proposal_id}: evidence outside supplied fact numbers")
        if proposal.get("verdict") == "answerable" and not evidence:
            errors.append(f"{proposal_id}: answerable requires evidence")
    if errors:
        raise ValueError("\n".join(errors))


PAGE = r"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>챗 정답 노드 검증</title>
<style>
:root{--ink:#17202a;--paper:#f5f7f8;--card:#fff;--line:#d9e0e4;--muted:#68757e;
--verify:#176b87;--hold:#a96520;--danger:#b33a3a;--wash:#eaf3f6;--shadow:0 12px 34px #17202a12}
*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);
font:15px/1.6 -apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo",sans-serif}
button,input,select,textarea{font:inherit}button{cursor:pointer}.top{position:sticky;top:0;z-index:10;
background:#f5f7f8ed;backdrop-filter:blur(12px);border-bottom:1px solid var(--line)}
.bar{max-width:1320px;margin:auto;padding:14px 22px;display:flex;gap:14px;align-items:center}
h1{font-size:16px;margin:0}.count{color:var(--muted);font-variant-numeric:tabular-nums}.spacer{flex:1}
.export{border:0;border-radius:8px;background:var(--ink);color:#fff;padding:9px 14px}.track{height:3px;background:var(--line)}
.fill{height:100%;width:0;background:var(--verify);transition:width .15s}.layout{max-width:1320px;margin:auto;
display:grid;grid-template-columns:minmax(320px,430px) minmax(0,1fr);gap:18px;padding:22px}
.panel{background:var(--card);border:1px solid var(--line);box-shadow:var(--shadow);border-radius:14px}
.decision{position:sticky;top:86px;align-self:start;padding:22px}.eyebrow{font:12px/1.4 ui-monospace,SFMono-Regular,monospace;
color:var(--verify);letter-spacing:.06em;text-transform:uppercase}.question{font-size:21px;line-height:1.45;
font-weight:700;letter-spacing:-.02em;margin:10px 0 22px}.label{display:block;font-size:12px;color:var(--muted);margin:17px 0 7px}
.choices{display:grid;grid-template-columns:1fr 1fr;gap:8px}.choice{border:1px solid var(--line);background:#fff;
padding:10px;border-radius:9px;color:var(--ink)}.choice[aria-pressed=true]{border-color:var(--verify);background:var(--wash);
color:var(--verify);font-weight:700}.field{width:100%;border:1px solid var(--line);border-radius:9px;background:#fff;
padding:10px;color:var(--ink)}textarea{min-height:92px;resize:vertical}.proposal{border-left:3px solid var(--verify);
background:var(--wash);padding:12px 14px;margin:18px 0;border-radius:0 9px 9px 0}.proposal strong{display:block;margin-bottom:4px}
.verify{width:100%;border:0;border-radius:9px;background:var(--verify);color:#fff;padding:12px;font-weight:700;margin-top:18px}
.verify.done{background:#2f7d5a}.nav{display:flex;gap:8px;margin-top:10px}.nav button{flex:1;border:1px solid var(--line);
background:#fff;border-radius:8px;padding:9px}.facts{padding:8px}.fact{display:grid;grid-template-columns:44px 1fr;gap:10px;
padding:14px 12px;border-bottom:1px solid var(--line);border-radius:8px}.fact:last-child{border-bottom:0}.fact.evidence{
background:var(--wash);box-shadow:inset 3px 0 var(--verify)}.num{font:700 13px/1.7 ui-monospace,SFMono-Regular,monospace;
color:var(--muted)}.fact.evidence .num{color:var(--verify)}.text{word-break:keep-all}.empty{color:var(--danger)}
.hint{font-size:12px;color:var(--muted);margin-top:12px}.low{color:var(--danger)}
@media(max-width:820px){.layout{grid-template-columns:1fr;padding:12px}.decision{position:static}.bar{padding:12px}.question{font-size:19px}}
@media(prefers-reduced-motion:reduce){*{transition:none!important}}
:focus-visible{outline:3px solid #4f9cb5;outline-offset:2px}
</style></head><body>
<header class="top"><div class="bar"><h1>챗 정답 노드 검증</h1><span class="count" id="count"></span>
<span class="spacer"></span><button class="export" id="export">검증본 내보내기</button></div>
<div class="track"><div class="fill" id="fill"></div></div></header>
<main class="layout"><section class="panel decision" id="decision"></section><section class="panel facts" id="facts"></section></main>
<script id="payload" type="application/json">__PAYLOAD__</script>
<script>
const DATA=JSON.parse(document.getElementById('payload').textContent),KEY='__STORAGE_KEY__';
let state=JSON.parse(localStorage.getItem(KEY)||'{}'),i=0;
const labels={answerable:'답변 가능',partial:'부분 답변',unanswerable:'답변 불가',ambiguous:'모호'};
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function current(){const item=DATA[i],saved=state[item.id]||{};return {...item,review:{...item.proposal,...saved}}}
function evidence(review){return new Set(String(review.evidence??'').split(',').map(x=>Number(x.trim())).filter(Number.isInteger))}
function save(patch){const item=DATA[i];state[item.id]={...(state[item.id]||item.proposal),...patch};localStorage.setItem(KEY,JSON.stringify(state));render()}
function render(){const item=current(),r=item.review,e=evidence(r),done=!!r.verified;
 document.getElementById('decision').innerHTML=`<div class="eyebrow">${esc(item.id)}</div><div class="question">${esc(item.question)}</div>
 <span class="label">판정</span><div class="choices">${Object.entries(labels).map(([k,v],n)=>`<button class="choice" data-v="${k}" aria-pressed="${r.verdict===k}">${n+1}. ${v}</button>`).join('')}</div>
 <div class="proposal"><strong>Codex 제안 · ${esc(r.confidence||'미작성')}</strong>${esc(r.rationale||'아직 제안이 없습니다.')}</div>
 <label class="label" for="evidence">근거 사실 번호</label><input class="field" id="evidence" value="${esc(Array.isArray(r.evidence)?r.evidence.join(', '):r.evidence||'')}" placeholder="예: 2, 7">
 <label class="label" for="confidence">확신도</label><select class="field" id="confidence"><option value="high" ${r.confidence==='high'?'selected':''}>높음</option><option value="medium" ${r.confidence==='medium'?'selected':''}>중간</option><option value="low" ${r.confidence==='low'?'selected':''}>낮음</option></select>
 <label class="label" for="rationale">판정 이유</label><textarea class="field" id="rationale">${esc(r.rationale||'')}</textarea>
 <button class="verify ${done?'done':''}" id="verify">${done?'검증 완료됨':'이 판정 검증 완료'}</button><div class="nav"><button id="prev">← 이전</button><button id="next">다음 →</button></div>
 <div class="hint">단축키 1–4 판정 · ← → 이동 · 제안 근거는 파란 행으로 표시돼요.</div>`;
 document.getElementById('facts').innerHTML=item.facts.length?item.facts.map(f=>`<article class="fact ${e.has(f.number)?'evidence':''}"><div class="num">#${f.number}</div><div class="text">${esc(f.text)}</div></article>`).join(''):'<p class="empty">번호가 붙은 사실이 없습니다.</p>';
 document.querySelectorAll('.choice').forEach(b=>b.onclick=()=>save({verdict:b.dataset.v,verified:false}));
 document.getElementById('evidence').onchange=e=>save({evidence:e.target.value,verified:false});
 document.getElementById('confidence').onchange=e=>save({confidence:e.target.value,verified:false});
 document.getElementById('rationale').onchange=e=>save({rationale:e.target.value,verified:false});
 document.getElementById('verify').onclick=()=>save({verified:!done});document.getElementById('prev').onclick=()=>go(-1);document.getElementById('next').onclick=()=>go(1);
 const completed=Object.values(state).filter(x=>x.verified).length;document.getElementById('count').textContent=`${i+1} / ${DATA.length} · 검증 ${completed}`;document.getElementById('fill').style.width=`${completed/DATA.length*100}%`}
function go(d){i=Math.max(0,Math.min(DATA.length-1,i+d));render();scrollTo({top:0,behavior:'smooth'})}
document.getElementById('export').onclick=()=>{const rows=DATA.map(item=>{const r={...item.proposal,...(state[item.id]||{})};return {id:item.id,verdict:r.verdict||'',evidence:Array.isArray(r.evidence)?r.evidence:String(r.evidence||'').split(',').map(x=>Number(x.trim())).filter(Number.isInteger),rationale:r.rationale||'',confidence:r.confidence||'',verified:!!r.verified}});const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(rows,null,2)],{type:'application/json'}));a.download='__EXPORT_NAME__';a.click()};
document.addEventListener('keydown',e=>{if(['INPUT','TEXTAREA','SELECT'].includes(e.target.tagName))return;const keys={'1':'answerable','2':'partial','3':'unanswerable','4':'ambiguous'};if(keys[e.key])save({verdict:keys[e.key],verified:false});else if(e.key==='ArrowLeft')go(-1);else if(e.key==='ArrowRight')go(1)});render();
</script></body></html>"""


def build_page(cases, proposals, export_name="chat_gold_verified.json",
               storage_key="chat-gold-review-v1"):
    payload = [{**case, "proposal": proposals.get(case["id"], {})} for case in cases]
    body = json.dumps(payload, ensure_ascii=False).replace("</script>", "<\\/script>")
    return (PAGE.replace("__PAYLOAD__", body).replace("__EXPORT_NAME__", export_name)
            .replace("__STORAGE_KEY__", storage_key))


def main():
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--judged")
    source.add_argument("--jsonl")
    parser.add_argument("--target", default="step342")
    parser.add_argument("--proposals")
    parser.add_argument("--out")
    parser.add_argument("--export-name", default="chat_gold_verified.json")
    parser.add_argument("--storage-key", default="chat-gold-review-v1")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()

    cases = load_cases(args.judged, args.target) if args.judged else load_jsonl_cases(args.jsonl)
    proposals = load_proposals(args.proposals)
    validate(cases, proposals)
    print(f"[check] cases={len(cases)} proposals={len(proposals)} blinded=yes")
    if args.check_only:
        return
    if not args.out:
        raise SystemExit("--out is required unless --check-only is used")
    if Path(args.export_name).name != args.export_name or not args.export_name.endswith(".json"):
        raise SystemExit("--export-name must be a JSON basename")
    Path(args.out).write_text(build_page(cases, proposals, args.export_name, args.storage_key))
    print(f"[done] {args.out}")


if __name__ == "__main__":
    main()
