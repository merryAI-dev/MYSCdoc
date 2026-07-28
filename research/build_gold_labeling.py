#!/usr/bin/env python3
"""
골드 라벨 앱 생성 — syntax_grounding 프록시가 실제 정확도와 상관있는지 검증하기 위한 것.

왜 필요한가: 학생 모델은 Gemini+게이트 출력을 타깃으로 학습했다. modal_grounded가 오르는 게
'진짜 좋아진 것'인지 '지표가 선호하는 모양을 학습한 것'(Goodhart)인지 자동 지표만으로는
영원히 구분할 수 없다. 사람 판단이 한 번은 필요하다.

블라인드 설계:
  - 화면에는 결정문·원문만 나오고 어느 시스템이 뽑았는지는 넣지 않는다
  - id→시스템 매핑은 gold_key.json에 따로 두고 채점할 때만 조인한다
  - 표본은 시스템 간 균등, 순서는 고정 시드로 섞는다 (재현 가능)
  - 원문에 하이라이트를 미리 칠하지 않는다 — 그 자체가 grounding 지표 쪽으로 편향을 만든다

산출물은 로컬 HTML 파일이다. 사내 회의록 본문이 들어가므로 외부 호스팅에 올리지 않는다.

사용:
  venv/bin/python build_gold_labeling.py --corpus corpus/eval80.jsonl \
      --runs runs/gemini_eval_expanded.jsonl runs/student_v4_gate_hint.jsonl --n 50
"""
import argparse
import json
import random

APP = """<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>결정 추출 골드 라벨링</title>
<style>
  :root {
    color-scheme: light dark;
    --ink: #14161a; --bg: #fbfbfa; --muted: #6b7076;
    --line: #e2e2df; --panel: #ffffff;
    --yes: #1f7a4d; --no: #a8332b; --maybe: #8a6212;
    --accent: #2f4858;
  }
  @media (prefers-color-scheme: dark) {
    :root { --ink: #e8e8e6; --bg: #16181a; --muted: #9a9fa5;
            --line: #2c2f33; --panel: #1e2124;
            --yes: #5fbe8c; --no: #e08078; --maybe: #d3a94a; --accent: #8fb0c4; }
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--ink);
         font: 15px/1.65 -apple-system, 'Apple SD Gothic Neo', 'Pretendard', system-ui, sans-serif; }
  header { position: sticky; top: 0; z-index: 5; background: var(--bg);
           border-bottom: 1px solid var(--line); padding: 14px 20px; }
  .wrap { max-width: 900px; margin: 0 auto; }
  .topline { display: flex; align-items: baseline; gap: 14px; }
  h1 { font-size: 15px; font-weight: 650; margin: 0; letter-spacing: -0.01em; }
  .count { font-size: 13px; color: var(--muted); font-variant-numeric: tabular-nums; }
  .spacer { flex: 1; }
  .track { height: 3px; background: var(--line); border-radius: 2px; margin-top: 10px; }
  .fill { height: 100%; background: var(--accent); border-radius: 2px; width: 0;
          transition: width .18s ease; }
  main { max-width: 900px; margin: 0 auto; padding: 28px 20px 140px; }
  .q { font-size: 12px; letter-spacing: .08em; text-transform: uppercase;
       color: var(--muted); margin-bottom: 10px; }
  .claim { font-size: 19px; line-height: 1.5; font-weight: 600; letter-spacing: -0.01em;
           text-wrap: balance; margin: 0 0 18px; }
  .src-head { display: flex; align-items: center; gap: 10px; margin: 26px 0 8px; }
  .src-title { font-size: 13px; color: var(--muted); flex: 1;
               overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  input[type=search] { font: inherit; font-size: 13px; padding: 5px 10px;
        border: 1px solid var(--line); border-radius: 7px; background: var(--panel);
        color: var(--ink); width: 190px; }
  .src { background: var(--panel); border: 1px solid var(--line); border-radius: 10px;
         padding: 16px 18px; max-height: 46vh; overflow: auto;
         white-space: pre-wrap; word-break: break-word; font-size: 14px; line-height: 1.75; }
  mark { background: color-mix(in srgb, var(--maybe) 32%, transparent); color: inherit;
         border-radius: 2px; }
  footer { position: fixed; left: 0; right: 0; bottom: 0; background: var(--bg);
           border-top: 1px solid var(--line); padding: 14px 20px; }
  .acts { display: flex; gap: 10px; align-items: center; }
  button { font: inherit; font-size: 14px; padding: 10px 16px; border-radius: 9px;
           border: 1px solid var(--line); background: var(--panel); color: var(--ink);
           cursor: pointer; transition: background .12s, border-color .12s; }
  button:hover { border-color: var(--muted); }
  button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  .v { font-weight: 600; }
  .v kbd { font: inherit; font-size: 12px; color: var(--muted); margin-right: 6px; }
  .v[data-v=correct][aria-pressed=true] { background: var(--yes); border-color: var(--yes); color: #fff; }
  .v[data-v=wrong][aria-pressed=true]   { background: var(--no);  border-color: var(--no);  color: #fff; }
  .v[data-v=unclear][aria-pressed=true] { background: var(--maybe); border-color: var(--maybe); color: #fff; }
  .v[aria-pressed=true] kbd { color: rgba(255,255,255,.75); }
  .ghost { background: transparent; }
  .done { text-align: center; padding: 60px 20px; }
  .done h2 { font-size: 20px; margin: 0 0 8px; }
  .hint { font-size: 12px; color: var(--muted); margin-top: 10px; }
  @media (max-width: 620px) {
    .acts { flex-wrap: wrap; }
    input[type=search] { width: 130px; }
  }
</style>

<header><div class="wrap">
  <div class="topline">
    <h1>결정 추출 골드 라벨링</h1>
    <span class="count" id="count"></span>
    <span class="spacer"></span>
    <button class="ghost" id="export">내보내기</button>
  </div>
  <div class="track"><div class="fill" id="fill"></div></div>
</div></header>

<main id="main"></main>

<footer><div class="wrap"><div class="acts">
  <button class="ghost" id="prev">← 이전</button>
  <button class="v" data-v="correct"><kbd>1</kbd>맞음</button>
  <button class="v" data-v="wrong"><kbd>2</kbd>아님</button>
  <button class="v" data-v="unclear"><kbd>3</kbd>애매</button>
  <span class="spacer"></span>
  <button class="ghost" id="next">다음 →</button>
</div></div></footer>

<script id="data" type="application/json">__DATA__</script>
<script>
const DATA = JSON.parse(document.getElementById('data').textContent);
const KEY = 'gold_labels_v2';
let labels = JSON.parse(localStorage.getItem(KEY) || '{}');
let i = 0, query = '';

const main = document.getElementById('main');
const esc = s => s.replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));

function highlight(text) {
  // 검색어만 강조한다. 자동 하이라이트는 판단을 유도하므로 넣지 않는다.
  if (!query.trim()) return esc(text);
  const q = query.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&');
  return esc(text).replace(new RegExp(q, 'gi'), m => '<mark>' + m + '</mark>');
}

function render() {
  const it = DATA.items[i];
  const doc = DATA.docs[it.doc];
  main.innerHTML =
    '<div class="q">이 항목이 원문에 실제로 있는 결정인가요?</div>' +
    '<p class="claim">' + esc(it.claim) + '</p>' +
    '<div class="src-head"><span class="src-title">출처 · ' + esc(doc.title) + '</span>' +
    '<input type="search" id="find" placeholder="원문 내 검색 (/)" value="' + esc(query) + '"></div>' +
    '<div class="src">' + highlight(doc.text) + '</div>' +
    '<div class="hint">키보드 1·2·3으로 판단, ← → 로 이동. 진행 상황은 자동 저장돼요.</div>';
  const find = document.getElementById('find');
  find.addEventListener('input', e => { query = e.target.value; const p = e.target.selectionStart;
    render(); const f = document.getElementById('find'); f.focus(); f.setSelectionRange(p, p); });
  document.querySelectorAll('.v').forEach(b =>
    b.setAttribute('aria-pressed', String(labels[it.id] === b.dataset.v)));
  const n = Object.keys(labels).length;
  document.getElementById('count').textContent = (i + 1) + ' / ' + DATA.items.length + ' · 완료 ' + n;
  document.getElementById('fill').style.width = (n / DATA.items.length * 100) + '%';
}

function mark(v) {
  labels[DATA.items[i].id] = v;
  localStorage.setItem(KEY, JSON.stringify(labels));
  if (i < DATA.items.length - 1) { i++; query = ''; }
  render();
}
function go(d) { i = Math.max(0, Math.min(DATA.items.length - 1, i + d)); query = ''; render(); }

document.querySelectorAll('.v').forEach(b => b.addEventListener('click', () => mark(b.dataset.v)));
document.getElementById('prev').addEventListener('click', () => go(-1));
document.getElementById('next').addEventListener('click', () => go(1));
document.getElementById('export').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify(labels, null, 2)], {type: 'application/json'});
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = 'gold_labels.json'; a.click();
});
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') { if (e.key === 'Escape') e.target.blur(); return; }
  if (e.key === '/') { e.preventDefault(); document.getElementById('find').focus(); return; }
  const m = {'1':'correct','2':'wrong','3':'unclear'};
  if (m[e.key]) { mark(m[e.key]); }
  else if (e.key === 'ArrowLeft') go(-1);
  else if (e.key === 'ArrowRight') go(1);
});

// 이어서 하기 — 아직 판단 안 한 첫 항목으로 이동
const firstUndone = DATA.items.findIndex(it => !labels[it.id]);
i = firstUndone === -1 ? 0 : firstUndone;
render();
</script>
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--runs", nargs="+", required=True)
    ap.add_argument("--n", type=int, default=50)
    ap.add_argument("--out-html", default="gold_labeling.html")
    ap.add_argument("--out-key", default="gold_key.json")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    corpus = {json.loads(l)["doc_id"]: json.loads(l) for l in open(args.corpus)}
    rng = random.Random(args.seed)

    per_system = max(1, args.n // len(args.runs))
    items = []
    for path in args.runs:
        name = path.split("/")[-1].replace(".jsonl", "")
        pool = []
        for line in open(path):
            r = json.loads(line)
            if not r.get("output") or r["doc_id"] not in corpus:
                continue
            for d in r["output"].get("decisionPoints", []) or []:
                claim = str(d.get("decision", "")).strip()
                if claim:
                    pool.append((r["doc_id"], claim))
        rng.shuffle(pool)
        for doc_id, claim in pool[:per_system]:
            items.append({"system": name, "doc": doc_id, "claim": claim})

    rng.shuffle(items)
    for n, it in enumerate(items):
        it["id"] = f"item{n:03d}"

    # 문서 본문은 한 번만 담고 참조한다 (한 문서에서 여러 결정이 나오므로 중복이 크다).
    docs = {it["doc"]: {"title": corpus[it["doc"]]["title"], "text": corpus[it["doc"]]["input"]}
            for it in items}
    payload = {"items": [{"id": it["id"], "claim": it["claim"], "doc": it["doc"]} for it in items],
               "docs": docs}
    body = json.dumps(payload, ensure_ascii=False).replace("</script>", "<\\/script>")
    open(args.out_html, "w").write(APP.replace("__DATA__", body))

    # 정답키는 라벨링 화면과 분리 — 채점할 때만 조인한다.
    json.dump({it["id"]: {"system": it["system"], "doc_id": it["doc"], "claim": it["claim"]}
               for it in items}, open(args.out_key, "w"), ensure_ascii=False, indent=2)

    counts = {}
    for it in items:
        counts[it["system"]] = counts.get(it["system"], 0) + 1
    print(f"[gold] {len(items)}항목 · 문서 {len(docs)}개 → {args.out_html}")
    print(f"[gold] 시스템별(블라인드): {counts} · 키: {args.out_key}")


if __name__ == "__main__":
    main()
