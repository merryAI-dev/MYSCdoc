#!/usr/bin/env python3
"""
같은 입력·같은 프롬프트로 로컬 학생과 Gemini를 대조 추출한다 (모델만 교체).

논문에서 쓸 수 있는 형태로 만들려면 파이프라인 전체가 아니라 '모델'만 달라야 한다.
그래서 청킹·문장 번호 매기기·스키마를 양쪽에 동일하게 적용하고, 백엔드만 바꾼다.
  --backend vllm   : GPU의 로컬 모델 (학생 v5b / 32B)
  --backend gemini : 클라우드 (로컬에서만 실행 — API 키가 로컬에 있다)

입력은 {"input": "...", ...} 형태의 JSONL이면 무엇이든 된다 (슬랙 스레드/회의록 공용).
식별자는 corpus의 나머지 필드를 그대로 실어 나른다.

사용:
  # GPU
  python extract_sentid_any.py --backend vllm --model <dir> --corpus slack_corpus.jsonl \
      --prompts prompts.json --out runs/slack_student.jsonl --tp 1
  # 로컬
  venv/bin/python extract_sentid_any.py --backend gemini --corpus slack_corpus.jsonl \
      --prompts prompts.json --out runs/slack_gemini.jsonl
"""
import argparse
import json
import time
from concurrent.futures import ThreadPoolExecutor

import korean_syntax as ks
from chunking import chunk_paragraphs, merge, parse_json_block

MAX_SENTS = 48
SENT_IDS = [f"S{i:03d}" for i in range(1, MAX_SENTS + 1)]

PROMPT_NOTE = """

                ※ 본문의 각 문장에는 S001 같은 번호가 붙어 있습니다. ◆ 는 결정·의지 어미가
                감지된 문장이니 결정 후보로 먼저 보세요.
                decisionPoints의 각 항목은 아래 순서로 쓰세요:
                  1) evidence_ids — 그 결정의 근거가 된 문장 번호. 여러 개여도 되고,
                     연속된 발언이면 다 넣으세요. 문장을 옮겨 적지 말고 번호만 쓰세요.
                  2) evidence_status — 근거 문장을 특정할 수 있으면 "cited",
                     본문에 명시적 근거가 없으면 "no_explicit_source".
                     억지로 아무 번호나 고르지 마세요. 근거가 없다고 답해도 괜찮습니다.
                  3) 나머지 필드(decision, topic 등)를 고른 근거에 맞춰 쓰세요."""

TRIPLE = {"type": "object",
          "properties": {"subject": {"type": "string"}, "predicate": {"type": "string"},
                         "object": {"type": "string"}},
          "required": ["subject", "predicate", "object"]}
DECISION_POINT = {
    "type": "object",
    "properties": {
        "evidence_ids": {"type": "array", "items": {"type": "string", "enum": SENT_IDS}},
        "evidence_status": {"type": "string", "enum": ["cited", "no_explicit_source"]},
        "decision": {"type": "string"}, "topic": {"type": "string"},
        "outcome": {"type": "string"}, "owner": {"type": "string"},
        "rationale": {"type": "string"}, "condition": {"type": "string"},
        "alternatives": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["evidence_ids", "evidence_status", "decision", "topic"]}
SCHEMA = {
    "type": "object",
    "properties": {
        "worthRecording": {"type": "boolean"}, "title": {"type": "string"},
        "summary": {"type": "array", "items": {"type": "string"}},
        "decisionPoints": {"type": "array", "items": DECISION_POINT},
        "tacitKnowledge": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "kind": {"type": "string", "enum": ["policy", "constraint", "workaround",
                                                     "gotcha", "convention", "risk"]},
                "statement": {"type": "string"},
                "triples": {"type": "array", "items": TRIPLE}},
            "required": ["kind", "statement", "triples"]}},
    },
    "required": ["worthRecording"]}


def gemini_schema():
    """Gemini responseSchema — OpenAPI 방언(대문자 type, enum은 STRING에만).

    두 가지를 명시해야 한다. 안 하면 실측상 title에서 같은 문장을 무한 반복하다 토큰 상한에
    걸려 JSON이 안 닫힌다(스모크 17청크 중 10개 실패):
      · propertyOrdering — Gemini는 필드 순서를 보장하지 않는다. 근거를 먼저 고르게 하려면
        명시해야 하고, 이건 vLLM이 스키마 순서대로 생성하는 것과 조건을 맞추는 일이기도 하다.
      · required 확대 — worthRecording만 필수로 두면 나머지를 어떻게 끝낼지 몰라 폭주한다.

    스키마를 아예 안 넘기면 evidence_ids가 통째로 빠진다(그래서 근거가 0건이 됐다).
    한쪽 백엔드만 강제하면 '모델 차이'가 아니라 '강제 유무 차이'를 재게 된다.
    """
    triple = {"type": "OBJECT",
              "properties": {"subject": {"type": "STRING"},
                             "predicate": {"type": "STRING"},
                             "object": {"type": "STRING"}},
              "propertyOrdering": ["subject", "predicate", "object"],
              "required": ["subject", "predicate", "object"]}
    decision = {"type": "OBJECT",
                "properties": {
                    "evidence_ids": {"type": "ARRAY",
                                     "items": {"type": "STRING", "enum": SENT_IDS}},
                    "evidence_status": {"type": "STRING",
                                        "enum": ["cited", "no_explicit_source"]},
                    "decision": {"type": "STRING"}, "topic": {"type": "STRING"},
                    "outcome": {"type": "STRING"}, "owner": {"type": "STRING"},
                    "rationale": {"type": "STRING"}, "condition": {"type": "STRING"},
                    "alternatives": {"type": "ARRAY", "items": {"type": "STRING"}}},
                # vLLM 스키마와 같은 순서 — 근거를 먼저 고르고 결정을 쓴다.
                "propertyOrdering": ["evidence_ids", "evidence_status", "decision", "topic",
                                     "outcome", "owner", "rationale", "condition",
                                     "alternatives"],
                "required": ["evidence_ids", "evidence_status", "decision", "topic"]}
    tacit = {"type": "OBJECT",
             "properties": {
                 "kind": {"type": "STRING",
                          "enum": ["policy", "constraint", "workaround",
                                   "gotcha", "convention", "risk"]},
                 "statement": {"type": "STRING"},
                 "triples": {"type": "ARRAY", "items": triple}},
             "propertyOrdering": ["kind", "statement", "triples"],
             "required": ["kind", "statement", "triples"]}
    return {"type": "OBJECT",
            "properties": {
                "worthRecording": {"type": "BOOLEAN"}, "title": {"type": "STRING"},
                "summary": {"type": "ARRAY", "items": {"type": "STRING"}},
                "decisionPoints": {"type": "ARRAY", "items": decision},
                "tacitKnowledge": {"type": "ARRAY", "items": tacit}},
            "propertyOrdering": ["worthRecording", "title", "summary",
                                 "decisionPoints", "tacitKnowledge"],
            "required": ["worthRecording", "title", "decisionPoints", "tacitKnowledge"]}


def number_sentences(chunk):
    items, cursor = [], 0
    for sent in ks.sentences(chunk):
        if len(sent) < 2 or len(items) >= MAX_SENTS:
            continue
        start = chunk.find(sent, cursor)
        if start >= 0:
            cursor = start + len(sent)
        items.append({"id": f"S{len(items) + 1:03d}", "text": sent,
                      "start": start, "end": start + len(sent) if start >= 0 else -1,
                      "modal": ks.modality(sent, extended=True) is not None})
    return items


def render(items):
    return "\n".join(f'{it["id"]}{" ◆" if it["modal"] else ""} {it["text"]}' for it in items)


def build_jobs(rows, user_template, canonical, chunk_chars):
    jobs = []
    for i, row in enumerate(rows):
        for chunk in chunk_paragraphs(row["input"], chunk_chars):
            items = number_sentences(chunk)
            if items:
                jobs.append({"row": i, "items": items,
                             "user": user_template % (render(items), canonical)})
    return jobs


def resolve(parsed, items):
    """번호 → 실제 문장·오프셋. 범위 밖 번호 수를 함께 돌려준다."""
    by_id = {it["id"]: it for it in items}
    out_of_range = 0
    for d in parsed.get("decisionPoints", []) or []:
        evs = []
        for sid in (d.get("evidence_ids") or []):
            it = by_id.get(sid)
            if it is None:
                out_of_range += 1
                continue
            evs.append({"text": it["text"], "start": it["start"], "end": it["end"]})
        d["evidence"] = evs
        d.pop("evidence_ids", None)
    return out_of_range


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", choices=["vllm", "gemini"], required=True)
    ap.add_argument("--model", default=None, help="vllm 백엔드용 모델 경로")
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--chunk-chars", type=int, default=1000)
    ap.add_argument("--max-new-tokens", type=int, default=2048)
    ap.add_argument("--tp", type=int, default=1)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--env", default="../.env")
    ap.add_argument("--gemini-model", default="gemini-2.5-flash")
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    p = json.load(open(args.prompts))
    system_prompt = p["system"] + PROMPT_NOTE
    rows = [json.loads(l) for l in open(args.corpus)]
    if args.limit:
        rows = rows[:args.limit]
    jobs = build_jobs(rows, p["user_template"], p.get("canonical_predicates", ""),
                      args.chunk_chars)
    print(f"[{args.backend}] 문서 {len(rows)} → 청크 {len(jobs)}", flush=True)

    started = time.time()
    if args.backend == "vllm":
        from vllm import LLM, SamplingParams
        from vllm.sampling_params import StructuredOutputsParams
        llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
                  dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=32768)
        tok = llm.get_tokenizer()
        prompts = [tok.apply_chat_template(
            [{"role": "system", "content": system_prompt}, {"role": "user", "content": j["user"]}],
            tokenize=False, add_generation_prompt=True) for j in jobs]
        sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens)
        sampling.structured_outputs = StructuredOutputsParams(json=SCHEMA)
        texts = [o.outputs[0].text for o in llm.generate(prompts, sampling)]
    else:
        from gemini_extract_chunked import call_gemini, load_api_key
        api_key = load_api_key(args.env)
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            results = list(pool.map(
                lambda j: call_gemini(args.gemini_model, api_key, system_prompt,
                                      j["user"], args.max_new_tokens,
                                      schema=gemini_schema()), jobs))
        texts = [t if t else "" for t, _ in results]
    elapsed = time.time() - started

    per_row = [[] for _ in rows]
    ok = fail = oor = decisions = cited = no_src = 0
    for j, text in zip(jobs, texts):
        parsed, _ = parse_json_block(text)
        if parsed is None:
            fail += 1
            continue
        ok += 1
        oor += resolve(parsed, j["items"])
        for d in parsed.get("decisionPoints", []) or []:
            decisions += 1
            if d.get("evidence_status") == "no_explicit_source":
                no_src += 1
            else:
                cited += 1
        per_row[j["row"]].append(parsed)

    with open(args.out, "w") as out:
        for row, parts in zip(rows, per_row):
            merged = merge(parts) if parts else None
            record = {k: v for k, v in row.items() if k != "input"}
            record["output"] = merged
            out.write(json.dumps(record, ensure_ascii=False) + "\n")

    print(f"[done] {elapsed:.0f}s · 청크 파싱 {ok}/{ok+fail} · 결정 {decisions} "
          f"(근거있음 {cited} · 근거없음 {no_src}) · 번호 범위밖 {oor}")


if __name__ == "__main__":
    main()
