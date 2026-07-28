#!/usr/bin/env python3
"""
추출 출력의 JSON Schema — vLLM structured_outputs로 문법 자체를 강제하기 위한 것.

왜: 32B 교사가 청크의 13.3%에서 깨진 JSON을 뱉어 학습 데이터가 그만큼 통째로 버려졌다
(Gemini는 647청크에서 0건). 토큰 상한 문제가 아니라 모델 특성이라 프롬프트로는 안 풀린다.
디코딩 단계에서 스키마를 강제하면 파싱 실패가 원리적으로 사라진다.

설계상 주의 두 가지:
  1. 프롬프트는 기록 가치가 없을 때 {"worthRecording": false} 하나만 출력하게 한다.
     따라서 worthRecording 외의 필드는 전부 optional이어야 두 형태가 모두 통과한다.
  2. additionalProperties를 막지 않는다. 막으면 모델이 스키마에 없는 필드를 시도하다
     막혔을 때 생성이 이상해질 수 있다 — 목표는 파싱 보장이지 필드 단속이 아니다.
     (필드 단속은 이미 Java쪽 EntityNormalizer가 한다)
"""

TRIPLE = {
    "type": "object",
    "properties": {
        "subject": {"type": "string"},
        "predicate": {"type": "string"},
        "object": {"type": "string"},
    },
    "required": ["subject", "predicate", "object"],
}

DECISION_POINT = {
    "type": "object",
    "properties": {
        "decision": {"type": "string"},
        "rationale": {"type": "string"},
        "alternatives": {"type": "array", "items": {"type": "string"}},
        "owner": {"type": "string"},
        "condition": {"type": "string"},
        "topic": {"type": "string"},
        "outcome": {"type": "string"},
        # 원문 그대로 인용. 문법으로 '원문과 일치'까지는 강제할 수 없어서 생성 후 대조 검사로 확인한다.
        # optional로 뒀더니 형식 강제 상태에서 모델이 생략하는 쪽으로 도망가 결정당 0.81→0.65로
        # 줄었다. 출처가 이번 재생성의 목적이므로 최소 1개를 강제한다.
        # 반대급부: 근거를 못 찾을 때 지어낼 위험. verbatim 대조율로 감시한다.
        "evidence": {"type": "array", "items": {"type": "string"}, "minItems": 1},
    },
    "required": ["decision", "topic", "evidence"],
}

TACIT = {
    "type": "object",
    "properties": {
        "kind": {"type": "string",
                 "enum": ["policy", "constraint", "workaround", "gotcha", "convention", "risk"]},
        "statement": {"type": "string"},
        "triples": {"type": "array", "items": TRIPLE},
    },
    "required": ["kind", "statement", "triples"],
}

EXTRACTION_SCHEMA = {
    "type": "object",
    "properties": {
        "worthRecording": {"type": "boolean"},
        "title": {"type": "string"},
        "summary": {"type": "array", "items": {"type": "string"}},
        "decisionPoints": {"type": "array", "items": DECISION_POINT},
        "tacitKnowledge": {"type": "array", "items": TACIT},
    },
    # worthRecording만 필수 — 기록 가치 없음 응답도 스키마를 통과해야 한다.
    "required": ["worthRecording"],
}
