#!/usr/bin/env python3
"""
추출 프롬프트에 evidence(원문 그대로 인용) 필드를 추가한다 → prompts_evidence.json

왜: 그래프 DB에서는 부수적 노드보다 '어디서 나왔는지 모르는 노드'가 문제다. 결정마다
근거 원문을 그대로 들고 오면 문장 단위 출처가 공짜로 생기고, 덤으로 syntax modality를
퍼지 매칭이 아니라 원문 문장에 정확히 계산할 수 있다.

설계상 가장 위험한 지점: 기존 프롬프트는 "해요체로 바꿔라 / 대명사 해소 / 이름 통일 /
3인칭화"를 지시한다. evidence는 그 정반대여야 한다. 명시하지 않으면 모델이 evidence까지
다듬어 원문 대조가 불가능해진다(실제로 재작성 때문에 원문 어미가 소실되는 현상을 확인했다).
그래서 '유일한 예외'라고 못박는다.

원본은 건드리지 않고 새 파일로 낸다 — 조건 비교를 위해 기존 프롬프트가 그대로 남아야 한다.

사용: venv/bin/python make_evidence_prompt.py
"""
import json

SYSTEM_RULE = """
            - 【단 하나의 예외】 "evidence" 필드는 원문에서 **그대로 복사**합니다.
              위의 재작성 규칙(해요체 변환, 대명사 해소, 이름 통일, 3인칭 정리, 요약)을
              evidence에는 적용하지 마세요. 띄어쓰기·조사·어미·오탈자까지 원문과 한 글자도
              달라서는 안 됩니다. 원문에 없는 문장을 evidence에 넣으면 안 됩니다."""

EVIDENCE_FIELD = """,
                      "evidence": ["이 결정의 근거가 된 문장을 <thread>에서 그대로 복사한 것. 1~2개. 재작성 금지 — 원문 문자열과 정확히 일치해야 합니다. 근거 문장을 특정할 수 없으면 빈 배열."]"""

ANCHOR_SYSTEM = "            - 반드시 지정된 JSON 형식으로만"
ANCHOR_FIELD = '"outcome": "그 대상을'


def main():
    p = json.load(open("prompts.json"))

    # 1) system — 재작성 규칙 바로 뒤, 출력 형식 지시 앞에 예외 조항을 끼운다.
    if ANCHOR_SYSTEM not in p["system"]:
        raise SystemExit("system 프롬프트에서 삽입 위치를 찾지 못했습니다 — 프롬프트가 바뀌었는지 확인하세요")
    p["system"] = p["system"].replace(ANCHOR_SYSTEM, SYSTEM_RULE.rstrip() + "\n" + ANCHOR_SYSTEM, 1)

    # 2) user_template — decisionPoints 스키마의 outcome 뒤에 evidence를 붙인다.
    idx = p["user_template"].find(ANCHOR_FIELD)
    if idx < 0:
        raise SystemExit("user_template에서 outcome 필드를 찾지 못했습니다")
    end = p["user_template"].find('"\n', idx)          # outcome 설명 문자열의 끝
    if end < 0:
        raise SystemExit("outcome 필드의 끝을 찾지 못했습니다")
    end += 1                                            # 닫는 따옴표까지 포함
    p["user_template"] = p["user_template"][:end] + EVIDENCE_FIELD + p["user_template"][end:]

    json.dump(p, open("prompts_evidence.json", "w"), ensure_ascii=False, indent=1)
    print("[ok] prompts_evidence.json 생성")
    print("\n--- system 삽입 확인 ---")
    s = p["system"]
    print(s[s.find("【단 하나의 예외】") - 14:s.find("【단 하나의 예외】") + 300])
    print("\n--- decisionPoints 스키마 확인 ---")
    u = p["user_template"]
    print(u[u.find(ANCHOR_FIELD) - 60:u.find('"evidence"') + 260])


if __name__ == "__main__":
    main()
