package com.mysc.mydoc.ingest.archive;

import com.mysc.mydoc.repository.KnowledgeTripleRepository;
import com.mysc.mydoc.service.EntityNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
class OntologyEntityLinker {
    private static final int MAX_TRIPLES = 2_000;
    private static final int MAX_CANDIDATES = 5;

    private final KnowledgeTripleRepository triples;
    private final EntityNormalizer normalizer;

    OntologyEntityLinker(KnowledgeTripleRepository triples, EntityNormalizer normalizer) {
        this.triples = triples;
        this.normalizer = normalizer;
    }

    Map<String, List<String>> candidates(List<OntologyExtract.CandidateEntity> entities) {
        Set<String> existing = triples.findAll(PageRequest.of(0, MAX_TRIPLES,
                        Sort.by(Sort.Direction.DESC, "createdAt"))).stream()
                .flatMap(triple -> java.util.stream.Stream.of(triple.getSubject(), triple.getObject()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return entities.stream().collect(Collectors.toMap(
                OntologyExtract.CandidateEntity::id,
                entity -> best(entity.name(), existing),
                (left, right) -> left,
                java.util.LinkedHashMap::new
        ));
    }

    private List<String> best(String name, Set<String> existing) {
        String canonical = normalizer.entity(name).orElse(name);
        String needle = key(canonical);
        List<ScoredName> scored = new ArrayList<>();
        for (String candidate : existing) {
            int score = score(needle, key(normalizer.entity(candidate).orElse(candidate)));
            if (score > 0) {
                scored.add(new ScoredName(candidate, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(ScoredName::score).reversed()
                        .thenComparing(ScoredName::name))
                .limit(MAX_CANDIDATES)
                .map(ScoredName::name)
                .toList();
    }

    private static int score(String left, String right) {
        if (left.equals(right)) {
            return 100;
        }
        if (left.length() >= 3 && right.length() >= 3
                && (left.contains(right) || right.contains(left))) {
            return 60;
        }
        Set<String> leftPairs = pairs(left);
        Set<String> rightPairs = pairs(right);
        leftPairs.retainAll(rightPairs);
        return leftPairs.size() * 10;
    }

    private static Set<String> pairs(String value) {
        Set<String> pairs = new LinkedHashSet<>();
        for (int index = 0; index + 1 < value.length(); index++) {
            pairs.add(value.substring(index, index + 2));
        }
        return pairs;
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]", "");
    }

    private record ScoredName(String name, int score) {}
}
