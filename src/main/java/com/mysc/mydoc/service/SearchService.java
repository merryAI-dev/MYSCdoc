package com.mysc.mydoc.service;

import com.mysc.mydoc.ai.EmbeddingPort;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.repository.ChunkRepository;
import com.mysc.mydoc.repository.ChunkSearchRow;
import com.mysc.mydoc.repository.DocumentRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SearchService {
    private static final int RRF_K = 60; // 07-ai-pipeline.md
    private static final int MAX_HITS_PER_STRATEGY = 20; // 07-ai-pipeline.md
    private static final int MAX_SEARCH_LIMIT = 50; // 03-api-spec.md
    private static final int SNIPPET_LENGTH = 200; // 03-api-spec.md

    private final ChunkRepository chunks;
    private final DocumentRepository documents;
    private final ObjectProvider<EmbeddingPort> embeddings;

    public SearchService(ChunkRepository chunks, DocumentRepository documents, ObjectProvider<EmbeddingPort> embeddings) {
        this.chunks = chunks;
        this.documents = documents;
        this.embeddings = embeddings;
    }

    @Transactional(readOnly = true)
    public List<SearchHit> hybridSearch(String query, UUID spaceIdOrNull, int limit) {
        if (!StringUtils.hasText(query)) {
            throw new ValidationException("q is required");
        }
        EmbeddingPort embeddingPort = embeddings.getIfAvailable();
        if (embeddingPort == null) {
            throw new ValidationException("embedding is not configured");
        }
        int cappedLimit = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        Map<UUID, RankedChunk> ranked = new LinkedHashMap<>();
        merge(ranked, chunks.vectorHits(vector(embeddingPort.embed(query)), spaceIdOrNull));
        merge(ranked, chunks.keywordHits(query, spaceIdOrNull));

        return ranked.values().stream()
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed())
                .collect(LinkedHashMap<UUID, RankedChunk>::new, (map, item) -> map.putIfAbsent(item.row().getDocumentId(), item), Map::putAll)
                .values().stream()
                .limit(cappedLimit)
                .map(item -> {
                    var document = documents.findById(item.row().getDocumentId()).orElseThrow();
                    return new SearchHit(
                            item.row().getDocumentId(),
                            document.getTitle(),
                            item.row().getHeadingPath(),
                            item.row().getText().substring(0, Math.min(SNIPPET_LENGTH, item.row().getText().length())),
                            item.score()
                    );
                })
                .toList();
    }

    private void merge(Map<UUID, RankedChunk> ranked, List<ChunkSearchRow> rows) {
        for (int i = 0; i < Math.min(rows.size(), MAX_HITS_PER_STRATEGY); i++) {
            ChunkSearchRow row = rows.get(i);
            double score = 1.0 / (RRF_K + i + 1);
            ranked.compute(row.getId(), (id, existing) -> existing == null
                    ? new RankedChunk(row, score)
                    : new RankedChunk(row, existing.score() + score));
        }
    }

    private String vector(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }

    private record RankedChunk(ChunkSearchRow row, double score) {}
}
