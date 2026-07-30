#!/usr/bin/env python3
"""Select a deterministic, filtered Korean instruction replay corpus."""
import argparse
import hashlib
import heapq
import json
import re
from pathlib import Path


PII = re.compile(r"(?:[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|01[016789][- ]?\d{3,4}[- ]?\d{4}|\d{6}[- ]?[1-4]\d{6})")
BROKEN = re.compile(r"\[(?:forbidden_words|ender)\]|(?:^|\s)None(?:$|\s)", re.I)
KOREAN = re.compile(r"[가-힣]")
QUESTION = re.compile(r"(?:^|\n)질문:\s*(.+?)(?:\n\s*\n|$)", re.S)
GENERAL_SYSTEM = "당신은 사용자의 요청을 정확하고 자연스러운 한국어로 돕는 유용한 어시스턴트입니다."


def normalized(messages, source, source_id):
    clean = [{"role": str(m.get("role", "")), "content": str(m.get("content", "")).strip()}
             for m in messages]
    assistants = [i for i, m in enumerate(clean) if m["role"] == "assistant" and m["content"]]
    if not assistants:
        return None
    cut = assistants[-1]
    history, answer = clean[:cut], clean[cut]["content"]
    if not history or not any(m["role"] == "user" for m in history):
        return None
    text = "\n".join(m["content"] for m in clean)
    if len(text) < 40 or len(text) > 5000 or not KOREAN.search(text) or PII.search(text) or BROKEN.search(text):
        return None
    history = [m for m in history if m["role"] in {"user", "assistant"}]
    expected = ["user" if i % 2 == 0 else "assistant" for i in range(len(history))]
    if [m["role"] for m in history] != expected or history[-1]["role"] != "user":
        return None
    user_content = history[-1]["content"]
    match = QUESTION.search(user_content)
    question = match.group(1).strip() if match else user_content
    if len(question) < 4 or len(answer) < 8:
        return None
    prompt = [{"role": "system", "content": GENERAL_SYSTEM}, *history]
    return {"prompt": prompt, "completion": [{"role": "assistant", "content": answer}],
            "source": source, "source_id": str(source_id), "question": question}


def select(dataset, source, count, convert, seed):
    heap, seen = [], set()
    for index, raw in enumerate(dataset):
        row = convert(raw, index)
        if row is None:
            continue
        dedup = re.sub(r"\s+", " ", row["question"].casefold()).strip()
        if dedup in seen:
            continue
        seen.add(dedup)
        rank = hashlib.sha256(f"{seed}:{source}:{dedup}".encode()).hexdigest()
        item = (-int(rank, 16), rank, row)
        if len(heap) < count:
            heapq.heappush(heap, item)
        elif item > heap[0]:
            heapq.heapreplace(heap, item)
    if len(heap) < count:
        raise SystemExit(f"{source}: filtered rows {len(heap)} < requested {count}")
    return [item[2] for item in sorted(heap, key=lambda value: value[1])]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out")
    parser.add_argument("--smol", type=int, default=21000)
    parser.add_argument("--seed", type=int, default=20260729)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        good = normalized([{"role": "user", "content": "중요한 회의를 빠짐없이 잘 준비하는 방법은 무엇인가요?"},
                           {"role": "assistant", "content": "회의 목표와 안건, 참석자별 준비사항을 먼저 정리해요."}], "x", 1)
        assert good and [m["role"] for m in good["prompt"]] == ["system", "user"]
        nested = normalized([{"role": "user", "content": "두 문장으로 답하세요.\n\n질문: 핵심은 무엇인가요?"},
                             {"role": "assistant", "content": "핵심을 짧고 정확하게 설명해요."}], "x", 3)
        assert nested["question"] == "핵심은 무엇인가요?" and "두 문장" in nested["prompt"][1]["content"]
        multi = normalized([{"role": "user", "content": "회의는 언제예요?"},
                            {"role": "assistant", "content": "금요일 오후예요."},
                            {"role": "user", "content": "장소도 알려줘요."},
                            {"role": "assistant", "content": "회의실 A에서 열려요."}], "x", 5)
        assert [m["role"] for m in multi["prompt"]] == ["system", "user", "assistant", "user"]
        assert normalized([{"role": "user", "content": "가" * 5001},
                           {"role": "assistant", "content": "짧은 답변입니다."}], "x", 4) is None
        assert normalized([{"role": "user", "content": "전화 010-1234-5678"},
                                    {"role": "assistant", "content": "개인정보예요."}], "x", 2) is None
        print("SELF_CHECK_OK")
        return
    if not args.out:
        parser.error("--out is required")

    from datasets import load_dataset

    smol = load_dataset("lemon-mint/smol-koreantalk", split="train", streaming=True)
    rows = select(smol, "smol-koreantalk", args.smol,
                  lambda raw, i: normalized(raw.get("messages") or [], "smol-koreantalk",
                                            raw.get("custom_id", i)), args.seed)
    rows.sort(key=lambda row: hashlib.sha256(
        f"{args.seed}:{row['source']}:{row['source_id']}".encode()).hexdigest())
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))
    print(json.dumps({"rows": len(rows), "smol": args.smol,
                      "sha256": hashlib.sha256(out.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    main()
