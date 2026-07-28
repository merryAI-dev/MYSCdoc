#!/usr/bin/env python3
"""
조건 간 공용 전처리 — 청킹·병합·JSON 파싱.

조건 A′(Gemini)와 B/C(EXAONE)가 문자 그대로 같은 청크를 봐야 게이트 효과를 분리할 수 있다.
각 스크립트가 제 나름의 청킹을 들고 있으면 조용히 갈라져 비교가 무효가 된다 — 그래서 한 곳에 둔다.
"""
import json


def parse_json_block(text):
    """모델 출력에서 JSON 하나를 건져낸다 (코드펜스·서두 설명 허용)."""
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None, "no_json"
    try:
        return json.loads(text[start:end + 1]), None
    except json.JSONDecodeError as exc:
        return None, f"invalid_json:{exc.msg}"


def chunk_paragraphs(text, budget):
    """문단(줄) 경계를 지키며 budget 글자 이하로 묶는다. 문단 자체가 budget을 넘으면 단독 청크."""
    chunks, current = [], []
    size = 0
    for line in text.split("\n"):
        if not line.strip():
            continue
        # 현재 청크가 이미 차 있고 이 줄을 더하면 넘칠 때만 끊는다 (문단 중간 절단 없음).
        if current and size + len(line) > budget:
            chunks.append("\n".join(current))
            current, size = [], 0
        current.append(line)
        size += len(line)
    if current:
        chunks.append("\n".join(current))
    return chunks


def merge(parsed_chunks):
    """청크별 추출 결과를 문서 하나로 합친다 — 표층 중복만 제거."""
    decisions, tacit = [], []
    seen_d, seen_t = set(), set()
    for out in parsed_chunks:
        for d in out.get("decisionPoints", []) or []:
            key = (str(d.get("topic", "")).strip(), str(d.get("decision", ""))[:40].strip())
            if key in seen_d:
                continue
            seen_d.add(key)
            decisions.append(d)
        for t in out.get("tacitKnowledge", []) or []:
            key = str(t.get("statement", ""))[:60].strip()
            if key in seen_t:
                continue
            seen_t.add(key)
            tacit.append(t)
    return {"decisionPoints": decisions, "tacitKnowledge": tacit}


def build_body(chunk, gate, syntax):
    """게이트 모드에 따라 청크 본문을 만든다. filter면 버릴 청크에 None을 준다."""
    marked = []
    if gate != "off":
        # 확장 표지 사용 — 핵심 3표지만으로는 청크의 25%만 걸려 문서가 통째로 날아간다.
        marked = [s for s in syntax.sentences(chunk) if syntax.modality(s, extended=True)]
        if gate == "filter" and not marked:
            return None
    if gate == "hint" and marked:
        return (chunk + "\n\n[결정·의지 어미가 감지된 문장 — 결정 후보로 우선 검토]\n"
                + "\n".join(f"- {s}" for s in marked))
    return chunk
