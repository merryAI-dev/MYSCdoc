#!/usr/bin/env python3
"""
한국어 결정 modality를 형태소로 판정한다 — KoreanSyntax.java의 Python 대응.

Java쪽은 Lucene Nori(mecab-ko-dic), 여기는 Kiwi(세종 태그셋)라 태그 이름이 다르다.
같은 규칙을 각자의 태그셋으로 표현한 것이므로 판정 결과가 일치해야 한다
(KoreanSyntaxTest.java와 같은 문장으로 아래 self-test에서 대조).

핵심 표지 (실제 Kiwi 출력으로 확인):
  -기로 하다 : 기/ETN + 로/JKB + 하    "화요일에 하기로 했어요"
  -도록      : 도록/EC                 "누락되지 않도록 조치합니다"
  -겠-       : 겠/EP                   "공유하겠습니다"

한계(측정으로 확인됨): 평서형 해요체에는 표지가 실리지 않는다.
"더데이원랩은 후보기업에서 제외하는 기조로 결정해요" → 결정/NNG 하/XSV 어요/EF.
진짜 결정인데 표지가 없다. 그래서 하드 필터로 쓰면 안 되고, 커버리지를 재고 쓸 것.

self-test:  python korean_syntax.py
"""
from kiwipiepy import Kiwi

_kiwi = None


def kiwi():
    global _kiwi
    if _kiwi is None:
        _kiwi = Kiwi()
    return _kiwi


# 핵심 3표지 — 문법적으로 결정·의지를 싣는 어미
CORE = ("GIRO_HADA", "DOROK", "GESS")
# 확장 후보 — 회의 발화에 흔하지만 결정성이 약할 수 있어 별도 집계한다
EXTENDED = ("LGE", "PROPOSITIVE", "YEJEONG", "NECESSITY")


def modality(sentence, extended=False):
    """이 문장의 결정 modality 표지 이름, 없으면 None."""
    toks = kiwi().tokenize(sentence)
    forms = [(m.form, m.tag) for m in toks]

    for i, (form, tag) in enumerate(forms):
        # -기로 하다 : 기/ETN + 로/JKB + 하(VV|XSV)
        if form == "기" and tag == "ETN" and i + 2 < len(forms):
            if forms[i + 1] == ("로", "JKB") and forms[i + 2][0] == "하":
                return "GIRO_HADA"
        if form == "도록" and tag == "EC":
            return "DOROK"
        if form == "겠" and tag == "EP":
            return "GESS"

    if not extended:
        return None

    for i, (form, tag) in enumerate(forms):
        if form in ("ᆯ게", "ㄹ게") and tag == "EF":
            return "LGE"              # "할게요" — 약속
        if form == "자" and tag == "EF":
            return "PROPOSITIVE"      # "하자" — 청유
        if form == "예정" and tag == "NNG":
            return "YEJEONG"          # "할 예정이에요" — 계획
        # -아/어야 하다 : 야/EC + 하
        if form == "야" and tag == "EC" and i + 1 < len(forms) and forms[i + 1][0] == "하":
            return "NECESSITY"        # "해야 해요" — 당위
    return None


def sentences(text):
    """Kiwi의 문장 분리 — 정규식보다 구어체 회의록에서 안정적."""
    return [s.text.strip() for s in kiwi().split_into_sents(text) if s.text.strip()]


def has_decision(text, extended=False):
    return any(modality(s, extended) for s in sentences(text))


def _self_test():
    """KoreanSyntaxTest.java와 같은 케이스 — 두 구현이 같은 판정을 내야 한다."""
    positive = [
        ("배포는 화요일에 하기로 했어요.", "GIRO_HADA"),
        ("데이터가 누락되지 않도록 조치합니다.", "DOROK"),
        ("다음 주에 자료를 공유하겠습니다.", "GESS"),
    ]
    negative = [
        "이 기사 재밌네요.",
        "매출이 증가하고 있습니다.",
        "결정 기준이 무엇인지 물어봤어요.",   # '결정'이 있어도 결정 발화가 아님
        "본 사업은 정부 위탁 자금이 아니므로 유효해요.",  # 근거 — 결정 아님
    ]
    failed = 0
    for text, expected in positive:
        got = modality(text)
        if got != expected:
            print(f"  FAIL {text!r} → {got} (기대 {expected})")
            failed += 1
    for text in negative:
        got = modality(text)
        if got is not None:
            print(f"  FAIL {text!r} → {got} (기대 None)")
            failed += 1
    print(f"[self-test] {len(positive) + len(negative) - failed}/"
          f"{len(positive) + len(negative)} 통과")
    return failed == 0


if __name__ == "__main__":
    _self_test()
