package com.mysc.mydoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.ai.CorrectionClient;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.Block;
import com.mysc.mydoc.domain.Document;
import com.mysc.mydoc.repository.BlockRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CorrectionService {
    private static final Set<String> CATEGORIES = Set.of("SPELLING", "TERMINOLOGY", "PASSIVE_VOICE", "STRUCTURE");
    private static final String SYSTEM_PROMPT = """
            당신은 사내 문서의 교정 편집자입니다. 아래 채점 기준으로 문서를 평가하고 개선안을 제시하세요.
            - SPELLING: 맞춤법·띄어쓰기 오류
            - TERMINOLOGY: 직역투, 사내에서 안 쓰는 용어, 독자가 모를 약어
            - PASSIVE_VOICE: 수동 표현 ("~되어진다", "~라고 사료된다" 등)
            - STRUCTURE: 문단이 너무 길거나, 제목 위계가 어긋나거나, 결론이 없는 구조
            score는 0~100 (90+: 수정 불필요, 70~89: 소폭 수정, 70 미만: 구조적 수정 필요).
            finding은 확실한 것만, 최대 10개. 반드시 지정된 JSON 형식으로만 응답하세요.
            """;

    private final DocumentService documents;
    private final BlockRepository blocks;
    private final ObjectProvider<CorrectionClient> clients;
    private final ObjectMapper objectMapper;

    public CorrectionService(
            DocumentService documents,
            BlockRepository blocks,
            ObjectProvider<CorrectionClient> clients,
            ObjectMapper objectMapper
    ) {
        this.documents = documents;
        this.blocks = blocks;
        this.clients = clients;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CorrectionResult review(java.util.UUID documentId) {
        CorrectionClient client = clients.getIfAvailable();
        if (client == null) {
            throw new ValidationException("correction client is not configured");
        }
        Document document = documents.get(documentId);
        List<Block> orderedBlocks = blocks.findByDocumentIdOrderByPosition(documentId);
        CorrectionResult result = parse(client.review(SYSTEM_PROMPT, userPrompt(document, orderedBlocks)));
        Set<Integer> validPositions = orderedBlocks.stream().map(Block::getPosition).collect(Collectors.toSet());
        List<CorrectionFinding> findings = result.findings() == null ? List.of() : result.findings();
        return new CorrectionResult(
                Math.max(0, Math.min(100, result.score())),
                findings.stream()
                        .filter(finding -> CATEGORIES.contains(finding.category()) && validPositions.contains(finding.blockPosition()))
                        .limit(10)
                        .toList()
        );
    }

    private CorrectionResult parse(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new ValidationException("correction response is not JSON");
        }
        try {
            return objectMapper.readValue(raw.substring(start, end + 1), CorrectionResult.class);
        } catch (Exception exception) {
            throw new ValidationException("correction response is not JSON");
        }
    }

    private String userPrompt(Document document, List<Block> orderedBlocks) {
        String numberedBlocks = orderedBlocks.stream()
                .map(block -> block.getPosition() + ". " + text(block.getContent()))
                .collect(Collectors.joining("\n"));
        return """
                다음 문서를 교정해 주세요. 각 줄 앞의 숫자는 블록 위치(blockPosition)입니다.

                <document title="%s">
                %s
                </document>

                JSON 형식:
                {"score": 85, "findings": [{"category": "TERMINOLOGY", "blockPosition": 3,
                "original": "원문 그대로", "suggestion": "수정안", "reason": "이유 (한 문장, 해요체)"}]}
                """.formatted(document.getTitle(), numberedBlocks);
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
}
