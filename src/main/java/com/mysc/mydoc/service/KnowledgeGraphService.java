package com.mysc.mydoc.service;

import com.mysc.mydoc.domain.KnowledgeTriple;
import com.mysc.mydoc.repository.KnowledgeTripleRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeGraphService {
    private final KnowledgeTripleRepository triples;

    public KnowledgeGraphService(KnowledgeTripleRepository triples) {
        this.triples = triples;
    }

    public record ScoredTriple(UUID id, UUID documentId, String kind, String statement,
                               String subject, String predicate, String object, double score) {}

    public record GraphNode(String id, int degree) {}
    public record GraphEdge(String source, String target, String predicate, String kind,
                            UUID documentId, String statement) {}
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {}

    /** q가 비어 있으면 최신순, 있으면 BM25 점수순. */
    @Transactional(readOnly = true)
    public List<ScoredTriple> search(String q, int limit) {
        List<KnowledgeTriple> all = triples.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        if (!StringUtils.hasText(q)) {
            return all.stream().limit(limit)
                    .map(triple -> scored(triple, 0))
                    .toList();
        }
        Bm25 bm25 = new Bm25(all.stream().map(KnowledgeGraphService::corpusText).toList());
        double[] scores = bm25.scores(q);
        List<ScoredTriple> ranked = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (scores[i] > 0) {
                ranked.add(scored(all.get(i), scores[i]));
            }
        }
        ranked.sort(Comparator.comparingDouble(ScoredTriple::score).reversed());
        return ranked.stream().limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public Graph graph(String q, int limit) {
        List<ScoredTriple> hits = search(q, limit);
        Map<String, Integer> degree = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (ScoredTriple hit : hits) {
            degree.merge(hit.subject(), 1, Integer::sum);
            degree.merge(hit.object(), 1, Integer::sum);
            edges.add(new GraphEdge(hit.subject(), hit.object(), hit.predicate(),
                    hit.kind(), hit.documentId(), hit.statement()));
        }
        List<GraphNode> nodes = degree.entrySet().stream()
                .map(entry -> new GraphNode(entry.getKey(), entry.getValue()))
                .toList();
        return new Graph(nodes, edges);
    }

    private static ScoredTriple scored(KnowledgeTriple triple, double score) {
        return new ScoredTriple(triple.getId(), triple.getDocumentId(), triple.getKind(), triple.getStatement(),
                triple.getSubject(), triple.getPredicate(), triple.getObject(), score);
    }

    // BM25 문서 텍스트: 주어/서술어/목적어에 statement와 kind까지 합쳐 부분 일치를 넓게 잡는다.
    private static String corpusText(KnowledgeTriple triple) {
        return triple.getSubject() + " " + triple.getPredicate() + " " + triple.getObject()
                + " " + triple.getStatement() + " " + triple.getKind();
    }
}
