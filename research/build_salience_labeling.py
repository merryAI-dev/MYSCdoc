#!/usr/bin/env python3
"""
중요도(salience) 라벨 앱 — "틀린 걸 뽑는가"가 아니라 "중요한 걸 뽑는가"를 묻는다.

왜 설계를 바꿨나: 골드 라벨 50건에서 정확도는 Gemini 96% / 학생 92%로 이미 천장이었다.
반면 오답 3건은 전부 '틀렸다'기보다 '핵심이 아니다'였다. 즉 남은 품질 문제는 정확도가
아니라 중요도 판별이고, 지금 어떤 지표도 그걸 재지 않는다.

표본이 작으므로(문서 2개·13건) 시스템 비교는 목표가 아니다. 대신 자동 지표로는 절대
얻을 수 없는 두 가지를 얻는다:
  1. 뽑힌 결정의 핵심/부수 분포
  2. **아무도 안 뽑은 핵심 결정** — 누락(recall) 신호. 추출 결과만 봐서는 영원히 안 보인다.

블라인드: 어느 시스템이 뽑았는지는 화면에 없다. 매핑은 salience_key.json에만 둔다.

사용:
  venv/bin/python build_salience_labeling.py --corpus corpus/eval80.jsonl \
      --runs runs/gemini_eval_expanded.jsonl runs/student_v4_gate_hint.jsonl \
      --docs <doc_id> <doc_id>
"""
import argparse
import json
import random

APP = """<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>결정 중요도 라벨링</title>
<style>
  :root { color-scheme: light dark;
    --ink:#14161a; --bg:#fbfbfa; --muted:#6b7076; --line:#e2e2df; --panel:#fff;
    --core:#1f7a4d; --minor:#8a6212; --bad:#a8332b; --accent:#2f4858; }
  @media (prefers-color-scheme: dark) { :root {
    --ink:#e8e8e6; --bg:#16181a; --muted:#9a9fa5; --line:#2c2f33; --panel:#1e2124;
    --core:#5fbe8c; --minor:#d3a94a; --bad:#e08078; --accent:#8fb0c4; } }
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--ink);
    font:15px/1.65 -apple-system,'Apple SD Gothic Neo','Pretendard',system-ui,sans-serif}
  header{position:sticky;top:0;z-index:5;background:var(--bg);
    border-bottom:1px solid var(--line);padding:14px 20px}
  .wrap{max-width:920px;margin:0 auto}
  .topline{display:flex;align-items:baseline;gap:14px}
  h1{font-size:15px;font-weight:650;margin:0;letter-spacing:-.01em}
  .count{font-size:13px;color:var(--muted);font-variant-numeric:tabular-nums}
  .spacer{flex:1}
  main{max-width:920px;margin:0 auto;padding:26px 20px 90px}
  .doc-title{font-size:13px;color:var(--muted);margin-bottom:8px}
  .src{background:var(--panel);border:1px solid var(--line);border-radius:10px;
    padding:16px 18px;max-height:40vh;overflow:auto;white-space:pre-wrap;
    word-break:break-word;font-size:14px;line-height:1.75}
  h2{font-size:13px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);
    margin:30px 0 12px;font-weight:600}
  .item{border:1px solid var(--line);border-radius:10px;padding:14px 16px;margin-bottom:10px;
    background:var(--panel)}
  .claim{font-size:15px;line-height:1.55;margin:0 0 12px}
  .btns{display:flex;gap:8px;flex-wrap:wrap}
  button{font:inherit;font-size:13px;padding:7px 13px;border-radius:8px;
    border:1px solid var(--line);background:transparent;color:var(--ink);cursor:pointer}
  button:hover{border-color:var(--muted)}
  button:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
  .v[data-v=core][aria-pressed=true]{background:var(--core);border-color:var(--core);color:#fff}
  .v[data-v=minor][aria-pressed=true]{background:var(--minor);border-color:var(--minor);color:#fff}
  .v[data-v=wrong][aria-pressed=true]{background:var(--bad);border-color:var(--bad);color:#fff}
  textarea{width:100%;min-height:96px;font:inherit;font-size:14px;padding:12px 14px;
    border:1px solid var(--line);border-radius:10px;background:var(--panel);
    color:var(--ink);resize:vertical}
  .ask{font-size:14px;margin:30px 0 8px;font-weight:600}
  .why{font-size:13px;color:var(--muted);margin-bottom:10px}
  footer{position:fixed;left:0;right:0;bottom:0;background:var(--bg);
    border-top:1px solid var(--line);padding:12px 20px}
  .navs{display:flex;gap:10px;align-items:center}
  hr{border:0;border-top:1px solid var(--line);margin:34px 0}
</style>

<header><div class="wrap"><div class="topline">
  <h1>결정 중요도 라벨링</h1><span class="count" id="count"></span>
  <span class="spacer"></span><button id="export">내보내기</button>
</div></div></header>
<main id="main"></main>
<footer><div class="wrap"><div class="navs">
  <button id="prev">← 이전 문서</button><span class="spacer"></span>
  <button id="next">다음 문서 →</button>
</div></div></footer>

<script id="data" type="application/json">__DATA__</script>
<script>
const DATA = JSON.parse(document.getElementById('data').textContent);
const KEY = 'salience_v1';
let state = JSON.parse(localStorage.getItem(KEY) || '{"marks":{},"missed":{}}');
let d = 0;
const esc = s => s.replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
const save = () => localStorage.setItem(KEY, JSON.stringify(state));

function render() {
  const doc = DATA.docs[d];
  const items = doc.items.map(it =>
    '<div class="item" data-id="' + it.id + '">' +
    '<p class="claim">' + esc(it.claim) + '</p><div class="btns">' +
    '<button class="v" data-v="core">핵심 결정</button>' +
    '<button class="v" data-v="minor">부수적</button>' +
    '<button class="v" data-v="wrong">틀림</button></div></div>').join('');
  document.getElementById('main').innerHTML =
    '<div class="doc-title">회의록 ' + (d+1) + ' / ' + DATA.docs.length + ' · ' + esc(doc.title) + '</div>' +
    '<div class="src">' + esc(doc.text) + '</div>' +
    '<h2>추출된 결정 ' + doc.items.length + '건</h2>' + items +
    '<hr><div class="ask">이 회의의 핵심 결정인데 위 목록에 <u>없는</u> 것이 있나요?</div>' +
    '<div class="why">가장 중요한 질문이에요. 추출 결과만 봐서는 무엇을 놓쳤는지 알 수 없어요. ' +
    '없으면 비워두셔도 돼요.</div>' +
    '<textarea id="missed" placeholder="한 줄에 하나씩 적어주세요">' +
    esc(state.missed[doc.id] || '') + '</textarea>';

  document.querySelectorAll('.item').forEach(el => {
    const id = el.dataset.id;
    el.querySelectorAll('.v').forEach(b => {
      b.setAttribute('aria-pressed', String(state.marks[id] === b.dataset.v));
      b.addEventListener('click', () => {
        state.marks[id] = b.dataset.v; save(); render();
      });
    });
  });
  const ta = document.getElementById('missed');
  ta.addEventListener('input', e => { state.missed[doc.id] = e.target.value; save(); });

  const total = DATA.docs.reduce((a, x) => a + x.items.length, 0);
  document.getElementById('count').textContent =
    '판정 ' + Object.keys(state.marks).length + ' / ' + total;
}
document.getElementById('prev').addEventListener('click', () => {
  d = Math.max(0, d - 1); render(); scrollTo(0, 0); });
document.getElementById('next').addEventListener('click', () => {
  d = Math.min(DATA.docs.length - 1, d + 1); render(); scrollTo(0, 0); });
document.getElementById('export').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify(state, null, 2)], {type:'application/json'});
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = 'salience_labels.json'; a.click();
});
render();
</script>
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--runs", nargs="+", required=True)
    ap.add_argument("--docs", nargs="+", required=True)
    ap.add_argument("--out-html", default="salience_labeling.html")
    ap.add_argument("--out-key", default="salience_key.json")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    corpus = {json.loads(l)["doc_id"]: json.loads(l) for l in open(args.corpus)}
    rng = random.Random(args.seed)

    by_doc = {doc_id: [] for doc_id in args.docs}
    for path in args.runs:
        name = path.split("/")[-1].replace(".jsonl", "")
        for line in open(path):
            r = json.loads(line)
            if r["doc_id"] not in by_doc or not r.get("output"):
                continue
            for dp in r["output"].get("decisionPoints", []) or []:
                claim = str(dp.get("decision", "")).strip()
                if claim:
                    by_doc[r["doc_id"]].append({"system": name, "claim": claim})

    docs, key = [], {}
    n = 0
    for doc_id in args.docs:
        items = by_doc[doc_id]
        rng.shuffle(items)          # 시스템이 섞이도록 — 순서로 유추 못 하게
        packed = []
        for it in items:
            item_id = f"s{n:03d}"
            n += 1
            key[item_id] = {"system": it["system"], "doc_id": doc_id, "claim": it["claim"]}
            packed.append({"id": item_id, "claim": it["claim"]})
        docs.append({"id": doc_id, "title": corpus[doc_id]["title"],
                     "text": corpus[doc_id]["input"], "items": packed})

    body = json.dumps({"docs": docs}, ensure_ascii=False).replace("</script>", "<\\/script>")
    open(args.out_html, "w").write(APP.replace("__DATA__", body))
    json.dump(key, open(args.out_key, "w"), ensure_ascii=False, indent=2)

    counts = {}
    for v in key.values():
        counts[v["system"]] = counts.get(v["system"], 0) + 1
    print(f"[salience] 문서 {len(docs)} · 결정 {n}건 → {args.out_html}")
    print(f"[salience] 시스템별(블라인드): {counts} · 키: {args.out_key}")


if __name__ == "__main__":
    main()
