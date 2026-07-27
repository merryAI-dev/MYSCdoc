package com.mysc.mydoc.service;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 한국어 문장을 형태소로 갈라 '결정 modality'를 문법으로 판정한다 (어휘 매칭이 아니라).
 *
 * 왜: 한국어는 교착어라 "무엇을 하기로 했는가"가 어미에 실린다. "결정"이라는 단어가
 * 없어도 결정이고("…누락되지 않도록 조치합니다"), 반대로 "결정"이 들어가도 결정이 아닐 수
 * 있다("결정 기준을 물어봤어요"). 실측(원문 4,587문장)에서 어미 게이트는 어휘 매칭이
 * 놓치는 결정문 218건을 잡았고 정밀도도 높았다(92% vs 86%).
 *
 * 중요: 게이트는 <b>원문</b>에 적용해야 한다. LLM이 재작성한 문장은 해요체 평서문으로
 * 평탄화되며 원문의 어미가 소실된다(실측 재현율 20% vs 원문 적용).
 */
@Service
public class KoreanSyntax {

    /** 형태소 하나 — 표면형과 품사(Nori 좌측 POS). */
    public record Morph(String surface, String pos) {}

    /** 결정 modality 유형 — 어떤 문법 장치로 잡혔는지 (실험·디버깅에서 구분해 본다). */
    public enum Modality {
        /** -기로 하다: "화요일에 하기로 했어요" — 가장 전형적인 합의·결정 */
        GIRO_HADA,
        /** -도록: "누락되지 않도록 조치합니다" — 지시·목적 */
        DOROK,
        /** -겠-: "다음 주에 공유하겠습니다" — 화자 의지 */
        GESS
    }

    /** 문장을 형태소로 분해한다. */
    public List<Morph> morphemes(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
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
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return out;
    }

    /** 이 문장이 결정·의지 modality를 담고 있으면 그 유형, 아니면 empty. */
    public java.util.Optional<Modality> decisionModality(String sentence) {
        List<Morph> ms = morphemes(sentence);
        for (int i = 0; i < ms.size(); i++) {
            Morph m = ms.get(i);
            boolean ending = m.pos().startsWith("E"); // 어미(E*)
            // -기로 하다 : 기/E + 로/J + 하
            if (ending && "기".equals(m.surface()) && i + 2 < ms.size()
                    && "로".equals(ms.get(i + 1).surface())
                    && "하".equals(ms.get(i + 2).surface())) {
                return java.util.Optional.of(Modality.GIRO_HADA);
            }
            if (ending && "도록".equals(m.surface())) {
                return java.util.Optional.of(Modality.DOROK);
            }
            if (ending && "겠".equals(m.surface())) {
                return java.util.Optional.of(Modality.GESS);
            }
        }
        return java.util.Optional.empty();
    }

    /** 원문에서 결정 modality를 담은 문장만 골라낸다 — 추출 후보·충실성 검증의 기준. */
    public List<String> decisionSentences(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        for (String sentence : text.split("(?<=[.!?。])\\s+|\\n")) {
            String trimmed = sentence.strip();
            if (trimmed.length() > 10 && decisionModality(trimmed).isPresent()) {
                hits.add(trimmed);
            }
        }
        return hits;
    }

    /**
     * 조사·어미를 떼고 체언(명사류)만 남긴다 — 개체 경계 정확화.
     * "MYSC는" → "MYSC", "투자를" → "투자". 문자열 규칙으로는 불가능한 처리.
     */
    public String nounPhrase(String text) {
        StringBuilder sb = new StringBuilder();
        for (Morph m : morphemes(text)) {
            // 체언(N*)·외국어(SL)·숫자(SN)만 남기고 조사(J*)·어미(E*)·접사는 버린다.
            if (m.pos().startsWith("N") || "SL".equals(m.pos()) || "SN".equals(m.pos())) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(m.surface());
            }
        }
        return sb.length() == 0 ? text.strip() : sb.toString();
    }
}
