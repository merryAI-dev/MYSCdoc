package com.mysc.mydoc.service;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

/**
 * 스파이크: Nori(형태소 분석)가 (1) 결정 modality를 어미로 잡아내고
 * (2) 조사를 분리해 개체 경계를 잡는지 실제 문장으로 확인한다.
 * 통과하면 PredicateVocabulary의 문자열 부분일치를 이 방식으로 교체한다.
 */
class NoriSpikeTest {

    private record Morph(String surface, String pos) {}

    private List<Morph> analyze(String text) throws Exception {
        List<Morph> out = new ArrayList<>();
        try (KoreanTokenizer tokenizer = new KoreanTokenizer()) {
            tokenizer.setReader(new StringReader(text));
            CharTermAttribute term = tokenizer.addAttribute(CharTermAttribute.class);
            PartOfSpeechAttribute pos = tokenizer.addAttribute(PartOfSpeechAttribute.class);
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                out.add(new Morph(term.toString(),
                        pos.getLeftPOS() == null ? "?" : pos.getLeftPOS().name()));
            }
            tokenizer.end();
        }
        return out;
    }

    @Test
    void showMorphemesForRealSentences() throws Exception {
        List<String> samples = List.of(
                // 결정 modality — 어미로 드러나는 것들
                "배포는 화요일에 하기로 했어요.",
                "펀드는 한국이 아닌 일본에 설립해요.",
                "심화 교육 무단 이탈을 방지하도록 공지문을 작성합니다.",
                "다음 주에 자료를 공유하겠습니다.",
                // 결정이 아닌 것 — 단순 서술/의견
                "이 기사 재밌네요.",
                "매출이 증가하고 있습니다.",
                // 개체 경계 — 조사가 붙은 형태
                "MYSC는 투자를 검토한다.",
                "정지연(모모)님이 온보딩을 담당합니다."
        );
        for (String s : samples) {
            System.out.println("\n[" + s + "]");
            StringBuilder sb = new StringBuilder("   ");
            for (Morph m : analyze(s)) {
                sb.append(m.surface()).append("/").append(m.pos()).append("  ");
            }
            System.out.println(sb);
        }
    }
}
