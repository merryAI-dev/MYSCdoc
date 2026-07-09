package com.mysc.mydoc.service;

import com.mysc.mydoc.domain.KnowledgeTriple;
import com.mysc.mydoc.repository.KnowledgeTripleRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * BM25로 시드 트리플을 찾고, 그 시드의 주어/목적어 개체명이 등장하는 다른 트리플(1홉 이웃)까지
     * 함께 반환한다 — 질문과 어휘가 안 겹쳐도 그래프상 연결된 지식을 놓치지 않기 위함(챗봇 근거용).
     * 시드는 관련도순으로, 이웃은 그 뒤에 이어붙이며 전체는 totalLimit으로 캡한다.
     */
    @Transactional(readOnly = true)
    public List<ScoredTriple> searchExpanded(String q, int seedLimit, int totalLimit) {
        List<KnowledgeTriple> all = triples.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        if (all.isEmpty()) {
            return List.of();
        }
        List<ScoredTriple> seeds = search(q, seedLimit);
        if (seeds.isEmpty()) {
            return List.of();
        }
        Set<String> entities = new LinkedHashSet<>();
        Set<UUID> seedIds = new LinkedHashSet<>();
        for (ScoredTriple seed : seeds) {
            entities.add(seed.subject());
            entities.add(seed.object());
            seedIds.add(seed.id());
        }
        List<ScoredTriple> combined = new ArrayList<>(seeds);
        for (KnowledgeTriple triple : all) {
            if (combined.size() >= totalLimit) {
                break;
            }
            if (seedIds.contains(triple.getId())) {
                continue; // 이미 시드로 포함됨
            }
            if (entities.contains(triple.getSubject()) || entities.contains(triple.getObject())) {
                combined.add(scored(triple, 0)); // 1홉 이웃 — 직접 매치가 아니므로 점수 0
            }
        }
        return combined;
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
