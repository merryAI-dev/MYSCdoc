package com.mysc.mydoc.service;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실측: syntax 기반 결정 판정이 실제 우리 데이터에서 얼마나 맞는지.
 * 로컬 개발 DB를 읽으므로 MEASURE_LOCAL_DB=1 일 때만 돈다(CI 제외).
 *
 * 비교 기준: Gemini가 decision으로 뽑은 statement(양성) vs 암묵지로 뽑은 statement(음성).
 * 완벽한 정답은 아니지만, 어휘 매칭 대비 어미 판정이 나은지 방향은 볼 수 있다.
 */
@EnabledIfEnvironmentVariable(named = "MEASURE_LOCAL_DB", matches = "1")
class DecisionSyntaxMeasureTest {

    private record Morph(String surface, String pos) {}

    private List<Morph> analyze(String text) throws Exception {
        List<Morph> out = new ArrayList<>();
        try (KoreanTokenizer tokenizer = new KoreanTokenizer()) {
            tokenizer.setReader(new StringReader(text));
            CharTermAttribute term = tokenizer.addAttribute(CharTermAttribute.class);
            PartOfSpeechAttribute pos = tokenizer.addAttribute(PartOfSpeechAttribute.class);
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                out.add(new Morph(term.toString(), pos.getLeftPOS() == null ? "?" : pos.getLeftPOS().name()));
            }
            tokenizer.end();
        }
        return out;
    }

    /** 어미·형태소 배열에서 결정 modality를 찾는다 (어휘가 아니라 문법). */
    private boolean hasDecisionSyntax(List<Morph> ms) {
        for (int i = 0; i < ms.size(); i++) {
            Morph m = ms.get(i);
            // -기로 하다: ... 기/E + 로/J + 하/VV
            if ("기".equals(m.surface()) && m.pos().startsWith("E")
                    && i + 2 < ms.size()
                    && "로".equals(ms.get(i + 1).surface())
                    && "하".equals(ms.get(i + 2).surface())) {
                return true;
            }
            // -도록 (지시·목적)
            if ("도록".equals(m.surface()) && m.pos().startsWith("E")) {
                return true;
            }
            // -겠- (의지)
            if ("겠".equals(m.surface()) && m.pos().startsWith("E")) {
                return true;
            }
        }
        return false;
    }

    /** 비교군: 기존 어휘 부분일치 방식. */
    private boolean hasDecisionKeyword(String text) {
        for (String k : List.of("결정", "정했", "합의", "확정", "하기로")) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void measureOnRealCorpus() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/mydoc", "mydoc", "changeme")) {
            List<String> positives = fetch(conn,
                    "SELECT DISTINCT statement FROM knowledge_triple WHERE kind='decision' LIMIT 400");
            List<String> negatives = fetch(conn,
                    "SELECT DISTINCT statement FROM knowledge_triple WHERE kind<>'decision' LIMIT 400");
            System.out.println("\n[A] Gemini가 다시 쓴 statement 기준 (원문 어미 소실됨)");
            report("syntax(어미)", positives, negatives, s -> {
                try {
                    return hasDecisionSyntax(analyze(s));
                } catch (Exception e) {
                    return false;
                }
            });
            report("keyword(어휘)", positives, negatives, this::hasDecisionKeyword);

            // [B] 원문 회의록 문장 — syntax를 적용해야 할 진짜 자리
            List<String> rawSentences = new ArrayList<>();
            for (String block : fetch(conn,
                    "SELECT content->'content'->0->>'text' FROM block WHERE type='PARAGRAPH' "
                    + "AND content->'content'->0->>'text' IS NOT NULL LIMIT 4000")) {
                for (String sentence : block.split("(?<=[.!?。])\\s+|\\n")) {
                    if (sentence.strip().length() > 10) {
                        rawSentences.add(sentence.strip());
                    }
                }
            }
            int synHits = 0;
            int kwHits = 0;
            List<String> examples = new ArrayList<>();
            for (String sentence : rawSentences) {
                boolean syn = hasDecisionSyntax(analyze(sentence));
                if (syn) {
                    synHits++;
                    if (examples.size() < 6) {
                        examples.add(sentence);
                    }
                }
                if (hasDecisionKeyword(sentence)) {
                    kwHits++;
                }
            }
            System.out.printf("%n[B] 원문 문장 %d개 기준%n", rawSentences.size());
            System.out.printf("   syntax 적중: %d (%.1f%%) · keyword 적중: %d (%.1f%%)%n",
                    synHits, 100.0 * synHits / Math.max(rawSentences.size(), 1),
                    kwHits, 100.0 * kwHits / Math.max(rawSentences.size(), 1));
            System.out.println("   syntax가 잡은 원문 예시:");
            for (String e : examples) {
                System.out.println("     - " + e.substring(0, Math.min(90, e.length())));
            }
        }
    }

    private interface Detector {
        boolean test(String s);
    }

    private void report(String label, List<String> pos, List<String> neg, Detector d) {
        long tp = pos.stream().filter(d::test).count();
        long fp = neg.stream().filter(d::test).count();
        double recall = 100.0 * tp / Math.max(pos.size(), 1);
        double precision = 100.0 * tp / Math.max(tp + fp, 1);
        System.out.printf("%-14s 재현율 %.1f%% (%d/%d) · 정밀도 %.1f%% (오탐 %d/%d)%n",
                label, recall, tp, pos.size(), precision, fp, neg.size());
    }

    private List<String> fetch(Connection conn, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
