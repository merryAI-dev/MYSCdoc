package com.mysc.mydoc.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 서술어(predicate) 통제 어휘 — 표기가 제각각인 관계를 소수의 표준 관계로 수렴시킨다.
 *
 * 배경: 실측(2026-07-24) predicate 820종 중 67%가 1회성이라 그래프 엣지 타입이 파편화됐다.
 * "유발한다/발생시킨다/초래한다/야기한다"가 다 다른 엣지가 되면 관계 기반 질의·집계가 무의미해진다.
 * 아래 19개 표준 관계 매핑으로 트리플의 약 75%가 수렴한다(나머지 희소 관계는 원형 유지).
 *
 * 두 방향으로 적용: (1) 추출 프롬프트에 표준 목록을 제시해 LLM이 애초에 이들을 쓰게 유도,
 * (2) 저장 시점에 변형을 표준으로 매핑(재동기화로 기존 데이터도 정리). 문자열 규칙이라 토큰 비용 0.
 */
public final class PredicateVocabulary {
    // 표준 관계 → 이 부분문자열이 들어 있으면 그 표준으로 매핑. 위에서부터 먼저 일치하는 것을 쓴다.
    private static final Map<String, List<String>> CANON = new LinkedHashMap<>();
    static {
        CANON.put("결정했다", List.of("결정", "정했다", "합의", "확정"));
        CANON.put("포함한다", List.of("포함", "구성", "가진다", "가지고", "보유", "이루어", "속한다", "담고", "구비"));
        CANON.put("필요로 한다", List.of("필요", "요구", "필수", "요한다", "요청"));
        CANON.put("유발한다", List.of("유발", "발생", "초래", "야기", "일으킨", "원인", "영향", "기여", "이어진"));
        CANON.put("제한한다", List.of("제한", "방지", "차단", "막는다", "금지", "줄인", "축소"));
        CANON.put("해결한다", List.of("해결", "개선", "완화", "보완", "향상", "강화"));
        CANON.put("활용한다", List.of("활용", "사용", "수행", "진행", "이용", "적용", "실행"));
        CANON.put("제공한다", List.of("제공", "지원", "확보", "부여", "공급", "가능하게"));
        CANON.put("고려한다", List.of("고려", "검토", "감안", "고민"));
        CANON.put("목표로 한다", List.of("목표", "지향", "추진", "추구", "계획", "준비"));
        CANON.put("선호한다", List.of("선호", "원한다", "희망"));
        CANON.put("의존한다", List.of("의존", "종속", "기반"));
        CANON.put("담당한다", List.of("담당", "책임", "맡"));
        CANON.put("공유한다", List.of("공유", "전달", "보고", "안내"));
        CANON.put("확인한다", List.of("확인", "참고", "점검", "검증"));
        CANON.put("운영한다", List.of("운영", "관리", "유지", "처리"));
        CANON.put("협력한다", List.of("협력", "조율", "연계", "협업", "소통", "참여"));
        CANON.put("제약된다", List.of("어렵", "불가능", "부족", "겪는", "제외", "미흡", "한계"));
        CANON.put("속성이다", List.of("중요", "특징", "적합", "부합", "해당", "특성"));
    }

    /** 프롬프트에 노출할 표준 관계 목록(쉼표 구분). */
    public static final String CANONICAL_LIST = String.join(", ", CANON.keySet());

    private PredicateVocabulary() {}

    /** 변형 서술어를 표준 관계로 수렴. 일치하는 표준이 없으면 원형(공백 정규화)을 그대로 돌려준다. */
    public static String canonicalize(String predicate) {
        if (!StringUtils.hasText(predicate)) {
            return predicate;
        }
        String value = predicate.strip();
        for (Map.Entry<String, List<String>> entry : CANON.entrySet()) {
            for (String key : entry.getValue()) {
                if (value.contains(key)) {
                    return entry.getKey();
                }
            }
        }
        return value;
    }
}
