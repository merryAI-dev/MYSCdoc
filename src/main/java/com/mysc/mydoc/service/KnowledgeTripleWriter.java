package com.mysc.mydoc.service;

import com.mysc.mydoc.domain.KnowledgeTriple;
import com.mysc.mydoc.ingest.archive.DecisionExtract;
import com.mysc.mydoc.repository.KnowledgeTripleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * DecisionExtract(의사결정+암묵지) 결과를 knowledge_triple로 저장한다.
 * Slack 의사결정 추출(DecisionExtractionJob)과 Google Drive 회의록 가져오기 양쪽에서 공유한다.
 * sourceRef/sourceLabel은 트리플이 어디서 왔는지 추적용 태그(Slack 채널:스레드, Drive fileId 등).
 */
@Service
public class KnowledgeTripleWriter {
    private final KnowledgeTripleRepository triples;

    public KnowledgeTripleWriter(KnowledgeTripleRepository triples) {
        this.triples = triples;
    }

    /** 이 문서에서 이미 트리플을 뽑아낸 적이 있는지 — 재동기화 대상 선별용. */
    public boolean hasTriples(UUID documentId) {
        return triples.existsByDocumentId(documentId);
    }

    /** 문서 블록과 항상 같은 내용을 가리키도록, 재추출 시 트리플도 통째로 교체한다. */
    public void replace(UUID documentId, DecisionExtract extract, String sourceLabel, String sourceRef) {
        triples.deleteByDocumentId(documentId);
        List<DecisionExtract.DecisionPoint> decisions =
                extract.decisionPoints() == null ? List.of() : extract.decisionPoints();
        for (DecisionExtract.DecisionPoint point : decisions) {
            String subject = StringUtils.hasText(point.owner()) ? point.owner() : "팀";
            String statement = StringUtils.hasText(point.rationale())
                    ? point.decision() + " — " + point.rationale()
                    : point.decision();
            triples.save(new KnowledgeTriple(documentId, "decision", truncate(statement, 1000),
                    truncate(subject, 200), "결정했다", truncate(point.decision(), 500), sourceLabel, sourceRef));
        }
        if (extract.tacitKnowledge() != null) {
            for (DecisionExtract.TacitKnowledge item : extract.tacitKnowledge()) {
                if (item.triples() == null) {
                    continue;
                }
                for (DecisionExtract.Triple triple : item.triples()) {
                    triples.save(new KnowledgeTriple(documentId, item.kind(), truncate(item.statement(), 1000),
                            truncate(triple.subject(), 200), truncate(triple.predicate(), 200), truncate(triple.object(), 500),
                            sourceLabel, sourceRef));
                }
            }
        }
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
