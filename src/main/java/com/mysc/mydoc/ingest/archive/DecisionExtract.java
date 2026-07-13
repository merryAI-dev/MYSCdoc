package com.mysc.mydoc.ingest.archive;

import java.util.List;

/**
 * 조용해진 Slack 스레드에서 뽑아낸 결정 기록.
 * summary: 스레드 전체 요약. decisionPoints: 명시적 의사결정 단위.
 * tacitKnowledge: 대화에 스치듯 드러난 조직의 암묵지 — 온톨로지 트리플로 함께 보존한다.
 */
public record DecisionExtract(
        String title,
        List<String> summary,
        List<DecisionPoint> decisionPoints,
        List<TacitKnowledge> tacitKnowledge
) {
    public record DecisionPoint(
            String decision,
            String rationale,
            List<String> alternatives,
            String owner,
            String condition,
            String topic
    ) {}

    public record TacitKnowledge(String kind, String statement, List<Triple> triples) {}

    public record Triple(String subject, String predicate, String object) {}
}
