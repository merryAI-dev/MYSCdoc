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

    /**
     * hop: 시드에서 몇 번 개체를 건너 도달했는가(시드=0). via: 이 트리플에 도달하게 한 직전 트리플 id(시드는 null).
     * 멀티홉 답변이 근거의 연결 순서를 밝힐 수 있게 경로를 보존한다.
     */
    public record ScoredTriple(UUID id, UUID documentId, String kind, String statement,
                               String subject, String predicate, String object, java.time.Instant eventAt,
                               Double weight, double score, int hop, UUID via) {
        public ScoredTriple(UUID id, UUID documentId, String kind, String statement,
                            String subject, String predicate, String object, java.time.Instant eventAt,
                            Double weight, double score) {
            this(id, documentId, kind, statement, subject, predicate, object, eventAt, weight, score, 0, null);
        }
    }

    public record GraphNode(String id, int degree) {}
    public record GraphEdge(String source, String target, String predicate, String kind,
                            UUID documentId, String statement, java.time.Instant eventAt,
                            Double weight) {}
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {}
    public enum RetrievalMode { BM25, GRAPH, MULTIHOP }

    /**
     * 의미 가중치는 덧셈식으로만 쓴다: score/max + LAMBDA*weight.
     * 곱셈식은 실측(known-item 927질의)에서 hit@1 95.3→91.9%로 정답 자체를 눌러 폐기했고,
     * 덧셈식은 후보 집합을 바꾸지 않아 top10 상실 0을 확인했다(λ=0.1에서 hit@1 +0.3pt).
     */
    private static final double LAMBDA = 0.1;
    private static final double NEUTRAL_WEIGHT = 0.7; // 미판정 노드
    private static final double SEED_SCORE_RATIO = 0.7;
    static final int MAX_HOPS = 2;
    /**
     * 이보다 많은 트리플에 등장하는 개체는 경유지로 쓰지 않는다 — 회사명 같은 허브를 건너면
     * 한 홉 만에 그래프 대부분이 딸려 와 근거가 무관한 사실로 희석된다.
     * ponytail: 고정값. 코퍼스 규모에 맞춘 상대 기준(차수 상위 n%)으로 바꿀 여지가 있다.
     */
    static final int HUB_DEGREE = 25;

    /** q가 비어 있으면 최신순, 있으면 BM25 점수순. */
    @Transactional(readOnly = true)
    public List<ScoredTriple> search(String q, int limit) {
        return search(q, limit, false);
    }

    @Transactional(readOnly = true)
    public List<ScoredTriple> search(String q, int limit, boolean weighted) {
        List<KnowledgeTriple> all = triples.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        if (!StringUtils.hasText(q)) {
            return all.stream().limit(limit)
                    .map(triple -> scored(triple, 0))
                    .toList();
        }
        Bm25 bm25 = new Bm25(all.stream().map(KnowledgeGraphService::corpusText).toList());
        double[] scores = bm25.scores(q);
        double max = 0;
        for (double s : scores) {
            max = Math.max(max, s);
        }
        List<ScoredTriple> ranked = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (scores[i] > 0) {
                double score = scores[i];
                if (weighted && max > 0) {
                    KnowledgeTriple t = all.get(i);
                    double w = t.getWeight() == null ? NEUTRAL_WEIGHT : t.getWeight();
                    score = scores[i] / max + LAMBDA * w;
                }
                ranked.add(scored(all.get(i), score));
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
        List<ScoredTriple> seeds = strongSeeds(search(q, seedLimit));
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

    /** 실험 토글: 기본 BM25+1홉과 엔티티 우선 graph-aware 검색을 같은 입력으로 비교한다. */
    @Transactional(readOnly = true)
    public List<ScoredTriple> searchContext(String q, int seedLimit, int totalLimit, RetrievalMode mode) {
        return switch (mode) {
            case GRAPH -> searchGraphAware(q, seedLimit, totalLimit);
            case MULTIHOP -> searchMultiHop(q, seedLimit, totalLimit, MAX_HOPS);
            case BM25 -> searchExpanded(q, seedLimit, totalLimit);
        };
    }

    /**
     * BM25 시드에서 출발해 개체를 따라 maxHops까지 너비 우선으로 넓힌다. searchExpanded(1홉)와 달리
     * 홉마다 새로 닿은 개체만 다음 경유지로 삼고, 각 트리플에 hop/via를 남겨 답변이 경로를 밝힐 수 있게 한다.
     * 홉별 예산을 나눠 1홉 이웃이 한도를 다 채워 2홉이 굶는 것을 막고, 홉 안에서는 질문과의 어휘 유사도순으로 고른다.
     */
    @Transactional(readOnly = true)
    public List<ScoredTriple> searchMultiHop(String q, int seedLimit, int totalLimit, int maxHops) {
        List<KnowledgeTriple> all = triples.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        if (all.isEmpty()) {
            return List.of();
        }
        List<ScoredTriple> seeds = strongSeeds(search(q, seedLimit));
        if (seeds.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> degree = new java.util.HashMap<>();
        for (KnowledgeTriple triple : all) {
            degree.merge(triple.getSubject(), 1, Integer::sum);
            degree.merge(triple.getObject(), 1, Integer::sum);
        }
        double[] lexical = new Bm25(all.stream().map(KnowledgeGraphService::corpusText).toList()).scores(q);
        double bestLexical = java.util.Arrays.stream(lexical).max().orElse(0);

        List<ScoredTriple> combined = new ArrayList<>(seeds);
        Set<UUID> included = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        // 경유지 개체 → 그 개체를 드러낸 트리플 id (via 기록용)
        Map<String, UUID> frontier = new LinkedHashMap<>();
        for (ScoredTriple seed : seeds) {
            included.add(seed.id());
            frontier.putIfAbsent(seed.subject(), seed.id());
            frontier.putIfAbsent(seed.object(), seed.id());
        }

        for (int hop = 1; hop <= maxHops && combined.size() < totalLimit; hop++) {
            frontier.keySet().removeIf(entity -> degree.getOrDefault(entity, 0) > HUB_DEGREE);
            visited.addAll(frontier.keySet());
            int budget = Math.max(1, (totalLimit - combined.size()) / (maxHops - hop + 1));

            List<ScoredTriple> candidates = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                KnowledgeTriple triple = all.get(i);
                if (included.contains(triple.getId())) {
                    continue;
                }
                UUID via = frontier.containsKey(triple.getSubject())
                        ? frontier.get(triple.getSubject())
                        : frontier.get(triple.getObject());
                if (via == null) {
                    continue;
                }
                double score = bestLexical == 0 ? 0 : lexical[i] / bestLexical;
                candidates.add(scored(triple, score, hop, via));
            }
            // 안정 정렬 — 점수가 같으면 최신순(원래 순서)을 유지한다
            candidates.sort(Comparator.comparingDouble(ScoredTriple::score).reversed());

            Map<String, UUID> next = new LinkedHashMap<>();
            for (ScoredTriple candidate : candidates.subList(0, Math.min(budget, candidates.size()))) {
                if (combined.size() >= totalLimit) {
                    break;
                }
                combined.add(candidate);
                included.add(candidate.id());
                for (String entity : List.of(candidate.subject(), candidate.object())) {
                    if (!visited.contains(entity)) {
                        next.putIfAbsent(entity, candidate.id());
                    }
                }
            }
            frontier = next;
        }
        return combined;
    }

    /**
     * 질문과 가까운 엔티티를 먼저 고른 뒤, 해당 엔티티에 직접 연결된 엣지만 반환한다.
     * ponytail: 문자열 엔티티 기반 1홉 탐색. canonical node id가 생기면 DB traversal로 교체한다.
     */
    @Transactional(readOnly = true)
    public List<ScoredTriple> searchGraphAware(String q, int entityLimit, int totalLimit) {
        List<KnowledgeTriple> all = triples.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        if (all.isEmpty() || !StringUtils.hasText(q)) {
            return List.of();
        }

        List<String> entities = all.stream()
                .flatMap(triple -> java.util.stream.Stream.of(triple.getSubject(), triple.getObject()))
                .distinct()
                .toList();
        double[] entityScores = new Bm25(entities).scores(q);
        double bestEntityScore = java.util.Arrays.stream(entityScores).max().orElse(0);
        if (bestEntityScore <= 0) {
            return searchExpanded(q, entityLimit, totalLimit);
        }

        Map<String, Double> anchors = new LinkedHashMap<>();
        java.util.stream.IntStream.range(0, entities.size()).boxed()
                .filter(i -> entityScores[i] >= bestEntityScore * SEED_SCORE_RATIO)
                .sorted(Comparator.comparingDouble((Integer i) -> entityScores[i]).reversed())
                .limit(entityLimit)
                .forEach(i -> anchors.put(entities.get(i), entityScores[i] / bestEntityScore));

        double[] lexicalScores = new Bm25(all.stream().map(KnowledgeGraphService::corpusText).toList()).scores(q);
        double bestLexicalScore = java.util.Arrays.stream(lexicalScores).max().orElse(0);
        List<ScoredTriple> connected = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            KnowledgeTriple triple = all.get(i);
            double entityScore = Math.max(
                    anchors.getOrDefault(triple.getSubject(), 0.0),
                    anchors.getOrDefault(triple.getObject(), 0.0));
            if (entityScore == 0) {
                continue;
            }
            double lexicalScore = bestLexicalScore == 0 ? 0 : lexicalScores[i] / bestLexicalScore;
            connected.add(scored(triple, entityScore * 2 + lexicalScore));
        }
        connected.sort(Comparator.comparingDouble(ScoredTriple::score).reversed());
        return connected.stream().limit(totalLimit).toList();
    }

    static List<ScoredTriple> strongSeeds(List<ScoredTriple> seeds) {
        if (seeds.isEmpty()) {
            return seeds;
        }
        double threshold = seeds.stream().mapToDouble(ScoredTriple::score).max().orElse(0)
                * SEED_SCORE_RATIO;
        return seeds.stream().filter(seed -> seed.score() >= threshold).toList();
    }

    @Transactional(readOnly = true)
    public Graph graph(String q, int limit) {
        return graph(q, limit, false);
    }

    @Transactional(readOnly = true)
    public Graph graph(String q, int limit, boolean weighted) {
        List<ScoredTriple> hits = search(q, limit, weighted);
        Map<String, Integer> degree = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (ScoredTriple hit : hits) {
            degree.merge(hit.subject(), 1, Integer::sum);
            degree.merge(hit.object(), 1, Integer::sum);
            edges.add(new GraphEdge(hit.subject(), hit.object(), hit.predicate(),
                    hit.kind(), hit.documentId(), hit.statement(), hit.eventAt(),
                    hit.weight()));
        }
        List<GraphNode> nodes = degree.entrySet().stream()
                .map(entry -> new GraphNode(entry.getKey(), entry.getValue()))
                .toList();
        return new Graph(nodes, edges);
    }

    private static ScoredTriple scored(KnowledgeTriple triple, double score) {
        return scored(triple, score, 0, null);
    }

    private static ScoredTriple scored(KnowledgeTriple triple, double score, int hop, UUID via) {
        return new ScoredTriple(triple.getId(), triple.getDocumentId(), triple.getKind(), triple.getStatement(),
                triple.getSubject(), triple.getPredicate(), triple.getObject(), triple.getEventAt(),
                triple.getWeight(), score, hop, via);
    }

    // BM25 문서 텍스트: 주어/서술어/목적어에 statement와 kind까지 합쳐 부분 일치를 넓게 잡는다.
    private static String corpusText(KnowledgeTriple triple) {
        return triple.getSubject() + " " + triple.getPredicate() + " " + triple.getObject()
                + " " + triple.getStatement() + " " + triple.getKind();
    }
}
