package com.mysc.mydoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysc.mydoc.ai.EmbeddingPort;
import com.mysc.mydoc.domain.Block;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.Chunk;
import com.mysc.mydoc.domain.Document;
import com.mysc.mydoc.repository.BlockRepository;
import com.mysc.mydoc.repository.ChunkRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ChunkingService {
    private static final int CHUNK_SIZE = 1500; // 07-ai-pipeline.md

    private final DocumentService documents;
    private final BlockRepository blocks;
    private final ChunkRepository chunks;
    private final ObjectProvider<EmbeddingPort> embeddings;

    public ChunkingService(
            DocumentService documents,
            BlockRepository blocks,
            ChunkRepository chunks,
            ObjectProvider<EmbeddingPort> embeddings
    ) {
        this.documents = documents;
        this.blocks = blocks;
        this.chunks = chunks;
        this.embeddings = embeddings;
    }

    @Transactional
    public void rechunk(UUID docId) {
        // Lock the document so concurrent rechunks for the same doc serialize instead of
        // interleaving delete/embed/save and leaving stale or duplicate chunks behind.
        // ponytail: embedAll below still runs inside this transaction (holds a DB connection
        // during the embedding call). Fine while embeddings are dormant; if the OpenAI path is
        // enabled under load, move embedding computation before the write transaction.
        Document document = documents.getLocked(docId);
        chunks.deleteByDocumentId(docId);
        List<Section> sections = sections(document, blocks.findByDocumentIdOrderByPosition(docId));
        List<Section> pieces = new ArrayList<>();
        for (Section section : sections) {
            for (int start = 0; start < section.text().length(); start += CHUNK_SIZE) {
                pieces.add(new Section(section.headingPath(), section.text().substring(start, Math.min(start + CHUNK_SIZE, section.text().length()))));
            }
        }
        if (pieces.isEmpty()) {
            return;
        }

        EmbeddingPort embeddingPort = embeddings.getIfAvailable();
        if (embeddingPort == null) {
            return;
        }

        List<float[]> vectors = embeddingPort.embedAll(pieces.stream().map(Section::text).toList());
        List<Chunk> saved = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            saved.add(new Chunk(docId, pieces.get(i).headingPath(), pieces.get(i).text(), vectors.get(i)));
        }
        chunks.saveAll(saved);
    }

    private List<Section> sections(Document document, List<Block> orderedBlocks) {
        List<Section> sections = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentPath = document.getTitle();

        for (Block block : orderedBlocks) {
            String text = text(block.getContent());
            if (isHeading(block.getType())) {
                addSection(sections, currentPath, current);
                if (block.getType() == BlockType.HEADING1) {
                    headings.clear();
                    headings.add(text);
                } else if (block.getType() == BlockType.HEADING2) {
                    while (headings.size() > 1) {
                        headings.remove(headings.size() - 1);
                    }
                    headings.add(text);
                }
                currentPath = headings.isEmpty() ? document.getTitle() : document.getTitle() + " > " + String.join(" > ", headings);
            } else if (StringUtils.hasText(text)) {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(text);
            }
        }
        addSection(sections, currentPath, current);
        return sections;
    }

    private void addSection(List<Section> sections, String headingPath, StringBuilder text) {
        if (StringUtils.hasText(text)) {
            sections.add(new Section(headingPath, text.toString()));
            text.setLength(0);
        }
    }

    private boolean isHeading(BlockType type) {
        return type == BlockType.HEADING1 || type == BlockType.HEADING2 || type == BlockType.HEADING3;
    }

    private String text(JsonNode node) {
        StringBuilder builder = new StringBuilder();
        collectText(node, builder);
        return builder.toString();
    }

    private void collectText(JsonNode node, StringBuilder builder) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode text = node.get("text");
            if (text != null && text.isTextual()) {
                builder.append(text.asText());
            }
            node.fields().forEachRemaining(entry -> collectText(entry.getValue(), builder));
        } else if (node.isArray()) {
            node.forEach(child -> collectText(child, builder));
        }
    }

    private record Section(String headingPath, String text) {}
}
