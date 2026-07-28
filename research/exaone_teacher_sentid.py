#!/usr/bin/env python3
"""
교사 데이터 생성 — 인용을 '베껴 쓰기' 대신 '문장 번호 고르기'로.

배경: 베껴 쓰게 하면 6%를 재작성해 원문에서 위치를 찾을 수 없다(실측 37/537). NFC 문제인지
확인했으나 아니었고(코퍼스 180개 전부 NFC) 순수한 재작성이었다. 번호를 고르게 하면 베낄 일이
없어 위치 오류가 사라진다. LongCite(ACL 2025)가 같은 형식을 쓴다.

선행 연구를 읽고 네 가지를 바로잡았다:

1. 번호 범위를 고정한다 (S001~S048)
   청크마다 enum을 다르게 주면 요청마다 문법을 새로 컴파일해 캐시가 전혀 안 듣는다(실제로
   시험 생성이 멈췄다). 고정하면 한 번만 컴파일된다. 대가로 모델이 실제 문장 수를 넘는 번호를
   부를 수 있으나, 범위 밖은 100% 확실히 잡힌다 — 베껴쓰기의 애매한 불일치와 다르다.
   실측: 청크당 문장 수 중앙값 8, 최대 45 → 48이면 충분하다.

2. 근거를 강제하지 않는다
   앞서 minItems=1로 강제했더니 '지어내지 않았다'고 판단했는데, 그 근거였던 원문 대조는
   이 실패를 탐지할 수 없다. 근거가 없을 때 모델은 없는 문장을 만드는 게 아니라 원문의
   엉뚱한 문장을 갖다 붙이고, 대조 검사는 그걸 통과시킨다(문헌에서 '사후 정당화'라 부른다).
   대신 evidence_status로 '근거 없음'을 고를 수 있게 해서 셀 수 있는 값으로 만든다.

3. 근거를 결정보다 먼저 쓰게 한다
   스키마 필드 순서가 곧 생성 순서다. 결정을 먼저 확정하고 근거를 채우면 그 자체가
   사후 정당화 구조다. 근거를 먼저 고르고 그로부터 결정을 쓰게 순서를 뒤집는다.

4. 문장 여러 개를 고를 수 있게 둔다
   회의 결정은 보통 여러 발언에 걸쳐 있다. 하나만 고르라고 하면 없는 근거를 만들게 된다.

사용:
  python exaone_teacher_sentid.py --model ... --corpus ... --split-dir ... \
      --prompts prompts.json --out-dir ../corpus/sft_sentid --tp 2
"""
import argparse
import json
import os
import time

import korean_syntax as ks
from chunking import chunk_paragraphs, parse_json_block
from vllm import LLM, SamplingParams
from vllm.sampling_params import StructuredOutputsParams

MAX_SENTS = 48          # 실측 최대 45 + 여유
SENT_MIN_LEN = 2
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

# 필드 순서 = 생성 순서. 근거를 앞에 두어 결정을 근거로부터 쓰게 한다.
DECISION_POINT = {
    "type": "object",
    "properties": {
        "evidence_ids": {"type": "array", "items": {"type": "string", "enum": SENT_IDS}},
        "evidence_status": {"type": "string", "enum": ["cited", "no_explicit_source"]},
        "decision": {"type": "string"},
        "topic": {"type": "string"},
        "outcome": {"type": "string"},
        "owner": {"type": "string"},
        "rationale": {"type": "string"},
        "condition": {"type": "string"},
        "alternatives": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["evidence_ids", "evidence_status", "decision", "topic"],
}

SCHEMA = {
    "type": "object",
    "properties": {
        "worthRecording": {"type": "boolean"},
        "title": {"type": "string"},
        "summary": {"type": "array", "items": {"type": "string"}},
        "decisionPoints": {"type": "array", "items": DECISION_POINT},
        "tacitKnowledge": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "kind": {"type": "string",
                         "enum": ["policy", "constraint", "workaround", "gotcha",
                                  "convention", "risk"]},
                "statement": {"type": "string"},
                "triples": {"type": "array", "items": TRIPLE}},
            "required": ["kind", "statement", "triples"]}},
    },
    "required": ["worthRecording"],
}


def number_sentences(chunk):
    """청크를 문장으로 나눠 번호와 원문 내 위치를 붙인다."""
    items, cursor = [], 0
    for sent in ks.sentences(chunk):
        if len(sent) < SENT_MIN_LEN or len(items) >= MAX_SENTS:
            continue
        start = chunk.find(sent, cursor)
        if start >= 0:
            cursor = start + len(sent)
        items.append({"id": f"S{len(items) + 1:03d}", "text": sent,
                      "start": start, "end": start + len(sent) if start >= 0 else -1,
                      "modal": ks.modality(sent, extended=True) is not None})
    return items


def render_body(items):
    return "\n".join(f'{it["id"]}{" ◆" if it["modal"] else ""} {it["text"]}' for it in items)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--split-dir", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--chunk-chars", type=int, default=1000)
    ap.add_argument("--max-new-tokens", type=int, default=2048)
    ap.add_argument("--tp", type=int, default=2)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    p = json.load(open(args.prompts))
    system_prompt = p["system"] + PROMPT_NOTE
    user_template, canonical = p["user_template"], p.get("canonical_predicates", "")

    split_of = {}
    for name in ("train", "dev"):
        for line in open(f"{args.split_dir}/{name}.jsonl"):
            split_of[json.loads(line)["doc_id"]] = name
    corpus = {json.loads(l)["doc_id"]: json.loads(l) for l in open(args.corpus)}
    doc_ids = sorted(split_of)
    if args.limit:
        doc_ids = doc_ids[:args.limit]

    jobs = []
    for doc_id in doc_ids:
        for chunk in chunk_paragraphs(corpus[doc_id]["input"], args.chunk_chars):
            items = number_sentences(chunk)
            if items:
                jobs.append({"doc_id": doc_id, "items": items,
                             "user": user_template % (render_body(items), canonical)})
    print(f"[chunk] 문서 {len(doc_ids)} → 청크 {len(jobs)} "
          f"(문장 평균 {sum(len(j['items']) for j in jobs)/max(len(jobs),1):.1f})", flush=True)

    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=32768)
    tok = llm.get_tokenizer()
    prompts_text = [tok.apply_chat_template(
        [{"role": "system", "content": system_prompt}, {"role": "user", "content": j["user"]}],
        tokenize=False, add_generation_prompt=True) for j in jobs]

    # 스키마가 모든 요청에서 동일 → 문법이 한 번만 컴파일되고 재사용된다.
    sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens)
    sampling.structured_outputs = StructuredOutputsParams(json=SCHEMA)

    started = time.time()
    outputs = llm.generate(prompts_text, sampling)
    elapsed = time.time() - started

    os.makedirs(args.out_dir, exist_ok=True)
    files = {n: open(f"{args.out_dir}/{n}.jsonl", "w") for n in ("train", "dev")}
    counts = {"train": 0, "dev": 0}
    parse_fail = decisions = cited = no_source = 0
    resolved = out_of_range = 0

    for j, out in zip(jobs, outputs):
        parsed, _ = parse_json_block(out.outputs[0].text)
        if parsed is None:
            parse_fail += 1
            continue
        by_id = {it["id"]: it for it in j["items"]}
        for d in parsed.get("decisionPoints", []) or []:
            decisions += 1
            status = d.get("evidence_status")
            if status == "no_explicit_source":
                no_source += 1
            else:
                cited += 1
            evs = []
            for sid in (d.get("evidence_ids") or []):
                it = by_id.get(sid)
                if it is None:
                    out_of_range += 1        # 이 청크에 없는 번호 — 확실히 탐지된다
                    continue
                resolved += 1
                evs.append({"text": it["text"], "start": it["start"], "end": it["end"]})
            # 번호는 청크 안에서만 유효한 임시 표식이라 남기지 않는다.
            d["evidence"] = evs
            d.pop("evidence_ids", None)
        split = split_of[j["doc_id"]]
        files[split].write(json.dumps({
            "doc_id": j["doc_id"],
            "prompt": [{"role": "system", "content": system_prompt},
                       {"role": "user", "content": j["user"]}],
            "completion": [{"role": "assistant",
                            "content": json.dumps(parsed, ensure_ascii=False)}],
        }, ensure_ascii=False) + "\n")
        counts[split] += 1

    for f in files.values():
        f.close()
    total_ids = resolved + out_of_range
    print(f"\n[done] {elapsed:.0f}s · train {counts['train']} · dev {counts['dev']} · "
          f"파싱실패 {parse_fail}")
    print(f"[결정] {decisions}건 · 근거 있음 {cited} · 근거 없음 선택 {no_source} "
          f"({no_source/max(decisions,1):.1%})")
    print(f"[번호] 지목 {total_ids} · 해석 성공 {resolved} · 범위 밖 {out_of_range} "
          f"({resolved/max(total_ids,1):.1%})")


if __name__ == "__main__":
    main()
