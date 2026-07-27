package com.mysc.mydoc.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysc.mydoc.service.KoreanSyntax.Modality;
import org.junit.jupiter.api.Test;

class KoreanSyntaxTest {
    private final KoreanSyntax syntax = new KoreanSyntax();

    @Test
    void detectsDecisionModalityByEnding() {
        assertThat(syntax.decisionModality("배포는 화요일에 하기로 했어요.")).contains(Modality.GIRO_HADA);
        assertThat(syntax.decisionModality("데이터가 누락되지 않도록 조치합니다.")).contains(Modality.DOROK);
        assertThat(syntax.decisionModality("다음 주에 자료를 공유하겠습니다.")).contains(Modality.GESS);
    }

    @Test
    void ignoresNonDecisionSentences() {
        // 단순 서술·감상 — 어휘 매칭이면 놓치거나 오탐할 자리
        assertThat(syntax.decisionModality("이 기사 재밌네요.")).isEmpty();
        assertThat(syntax.decisionModality("매출이 증가하고 있습니다.")).isEmpty();
        // '결정'이라는 단어가 있어도 결정 발화가 아니면 안 잡는다 (어휘 매칭 대비 강점)
        assertThat(syntax.decisionModality("결정 기준이 무엇인지 물어봤어요.")).isEmpty();
    }

    @Test
    void picksDecisionSentencesFromRawText() {
        String raw = """
                오늘 회의를 시작합니다.
                매출 추이를 공유드렸어요.
                배포 요일은 화요일로 하기로 했습니다.
                다들 수고하셨습니다.
                """;
        assertThat(syntax.decisionSentences(raw))
                .hasSize(1)
                .allSatisfy(s -> assertThat(s).contains("화요일"));
    }

    @Test
    void stripsJosaForEntityBoundary() {
        // 조사가 붙은 표면형에서 개체만 남긴다 — 문자열 규칙으로는 불가능
        assertThat(syntax.nounPhrase("MYSC는")).isEqualTo("MYSC");
        assertThat(syntax.nounPhrase("투자를")).isEqualTo("투자");
    }
}
