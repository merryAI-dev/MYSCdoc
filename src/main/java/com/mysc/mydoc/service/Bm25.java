package com.mysc.mydoc.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Okapi BM25를 수식 그대로 구현한 랭커.
 *
 * score(D,Q) = Σ_{qᵢ∈Q} IDF(qᵢ) · ( f(qᵢ,D) · (k₁+1) ) / ( f(qᵢ,D) + k₁ · (1 − b + b·|D|/avgdl) )
 * IDF(qᵢ)   = ln( 1 + (N − n(qᵢ) + 0.5) / (n(qᵢ) + 0.5) )   — Robertson/Spärck Jones의 Lucene 변형(항상 ≥ 0)
 *
 *   f(qᵢ,D): 문서 D 안의 질의 토큰 qᵢ 빈도, |D|: 문서 토큰 수, avgdl: 평균 문서 길이,
 *   N: 전체 문서 수, n(qᵢ): qᵢ가 등장하는 문서 수. k₁=1.2, b=0.75 (표준 기본값).
 *
 * 한국어는 형태소 분석기 없이 CJK 문자 bigram으로 토큰화한다(Elasticsearch CJK analyzer와
 * 같은 접근) — "배포요일" → [배포, 포요, 요일]. 라틴 문자·숫자는 단어 단위.
 * 코퍼스는 인덱스 시점에 통계(df, avgdl)를 만들어 두고 질의마다 전 문서를 스코어링한다.
 * 트리플 수천 건 규모까지는 충분하고, 그 이상이 되면 역색인으로 바꾼다.
 */
public final class Bm25 {
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<Map<String, Integer>> termFrequencies = new ArrayList<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private double averageDocLength;

    public Bm25(List<String> corpus) {
        long totalLength = 0;
        for (String text : corpus) {
            List<String> tokens = tokenize(text);
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            for (String term : tf.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
            termFrequencies.add(tf);
            docLengths.add(tokens.size());
            totalLength += tokens.size();
        }
        this.averageDocLength = corpus.isEmpty() ? 0 : (double) totalLength / corpus.size();
    }

    /** 코퍼스 문서 index별 BM25 점수. 질의와 겹치는 토큰이 없으면 0. */
    public double[] scores(String query) {
        int n = termFrequencies.size();
        double[] scores = new double[n];
        if (n == 0 || averageDocLength == 0) {
            return scores;
        }
        for (String term : tokenize(query)) {
            Integer df = documentFrequency.get(term);
            if (df == null) {
                continue;
            }
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < n; i++) {
                Integer tf = termFrequencies.get(i).get(term);
                if (tf == null) {
                    continue;
                }
                double norm = tf + K1 * (1 - B + B * docLengths.get(i) / averageDocLength);
                scores[i] += idf * (tf * (K1 + 1)) / norm;
            }
        }
        return scores;
    }

    /** 라틴/숫자는 단어 토큰, CJK(한글·한자·가나)는 문자 bigram. */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                flushWord(tokens, word);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(tokens, cjk);
                word.append(c);
            } else {
                flushWord(tokens, word);
                flushCjk(tokens, cjk);
            }
        }
        flushWord(tokens, word);
        flushCjk(tokens, cjk);
        return tokens;
    }

    private static void flushWord(List<String> tokens, StringBuilder word) {
        if (word.length() > 0) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static void flushCjk(List<String> tokens, StringBuilder cjk) {
        if (cjk.length() == 1) {
            tokens.add(cjk.toString());
        } else {
            for (int i = 0; i + 1 < cjk.length(); i++) {
                tokens.add(cjk.substring(i, i + 2));
            }
        }
        cjk.setLength(0);
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }
}
