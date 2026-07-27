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
    /**
     * 하나의 의사결정. 그래프에는 단일 엣지가 아니라 '사건 노드'로 분해 저장된다(reification):
     * (결정)-주체->owner, (결정)-대상->topic, (결정)-값->outcome, (결정)-근거->rationale …
     * outcome이 핵심 추가분 — "무엇으로 정했는지"(일본, 화요일, 3회)가 그래프에 올라와야
     * 정책이 질의·이식 가능해진다. 기존엔 topic만 있어 '무엇을'만 알고 '어떻게'를 잃었다.
     */
    public record DecisionPoint(
            String decision,
            String rationale,
            List<String> alternatives,
            String owner,
            String condition,
            String topic,
            String outcome
    ) {}

    public record TacitKnowledge(String kind, String statement, List<Triple> triples) {}

    public record Triple(String subject, String predicate, String object) {}
}
