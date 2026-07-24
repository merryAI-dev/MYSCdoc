package com.mysc.mydoc.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PredicateVocabularyTest {

    @Test
    void convergesVariantsToCanonical() {
        // 실데이터의 파편화 클러스터가 하나로 수렴하는지
        assertThat(PredicateVocabulary.canonicalize("발생시킨다")).isEqualTo("유발한다");
        assertThat(PredicateVocabulary.canonicalize("초래한다")).isEqualTo("유발한다");
        assertThat(PredicateVocabulary.canonicalize("야기한다")).isEqualTo("유발한다");
        assertThat(PredicateVocabulary.canonicalize("요구한다")).isEqualTo("필요로 한다");
        assertThat(PredicateVocabulary.canonicalize("필수적이다")).isEqualTo("필요로 한다");
        assertThat(PredicateVocabulary.canonicalize("방지한다")).isEqualTo("제한한다");
        assertThat(PredicateVocabulary.canonicalize("책임진다")).isEqualTo("담당한다");
    }

    @Test
    void keepsUnknownPredicatesAsIs() {
        // 표준에 없는 희소 관계는 원형 유지(잘못 뭉개지 않음)
        assertThat(PredicateVocabulary.canonicalize("투자한다")).isEqualTo("투자한다");
        assertThat(PredicateVocabulary.canonicalize("피처링한다")).isEqualTo("피처링한다");
    }

    @Test
    void handlesBlankAndTrims() {
        assertThat(PredicateVocabulary.canonicalize("  포함한다  ")).isEqualTo("포함한다");
        assertThat(PredicateVocabulary.canonicalize("")).isEmpty();
        assertThat(PredicateVocabulary.canonicalize(null)).isNull();
    }

    @Test
    void exposesCanonicalListForPrompt() {
        assertThat(PredicateVocabulary.CANONICAL_LIST).contains("유발한다").contains("담당한다").contains("결정했다");
    }
}
