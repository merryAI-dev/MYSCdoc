package com.mysc.mydoc.ingest.tiro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.domain.TiroIngestLog;
import com.mysc.mydoc.ingest.ThreadSummary;
import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.repository.TiroIngestLogRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TiroIngestService {
    private static final int MAX_JSON_PARSE_ATTEMPTS = 2; // 06-ingest-slack.md와 동일 재시도 규칙
    private static final String SYSTEM_PROMPT = """
            당신은 사내 문서 플랫폼의 기록 담당자입니다. Tiro 회의록 전사를 읽고, 나중에 다른 팀원이나
            AI가 참고할 수 있는 결정 기록 문서를 만듭니다. 전사에 없는 내용을 지어내지 마세요.
            결정이 명확하지 않으면 "결정 사항"에 "명시적 결정 없음 — 논의 요약"이라고 쓰세요.
            반드시 지정된 JSON 형식으로만, 코드펜스 없이 순수 JSON만 출력하세요.
            """;

    private final ObjectProvider<TiroPort> client;
    private final ObjectProvider<ThreadSummaryClient> summaryClient;
    private final DocumentService documents;
    private final TiroIngestLogRepository ingestLogs;
    private final ObjectMapper objectMapper;

    public TiroIngestService(
            ObjectProvider<TiroPort> client,
            ObjectProvider<ThreadSummaryClient> summaryClient,
            DocumentService documents,
            TiroIngestLogRepository ingestLogs,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.summaryClient = summaryClient;
        this.documents = documents;
        this.ingestLogs = ingestLogs;
        this.objectMapper = objectMapper;
    }

    public List<TiroNoteSummary> browse(String keyword) {
        return client().listNotes(keyword);
    }

    @Transactional
    public UUID importNote(String noteGuid, UUID spaceId, UUID memberId) {
        var existing = ingestLogs.findByNoteGuid(noteGuid);
        if (existing.isPresent()) {
            return existing.get().getDocumentId();
        }

        TiroNoteSummary note = client().getNote(noteGuid);
        String transcript = client().getTranscriptText(noteGuid);
        if (!StringUtils.hasText(transcript)) {
            throw new ValidationException("Tiro 노트에 전사 내용이 없어요.");
        }

        ThreadSummary summary = summarize(transcript);
        var document = documents.create(spaceId, summary.title(), memberId);
        documents.replaceBlocks(document.getId(), blocks(summary, note), memberId, ChangeCause.IMPORT);
        ingestLogs.save(new TiroIngestLog(noteGuid, document.getId()));
        return document.getId();
    }

    private ThreadSummary summarize(String transcript) {
        ThreadSummaryClient chatClient = summaryClient.getIfAvailable();
        if (chatClient == null) {
            throw new ValidationException("summary client is not configured");
        }
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_JSON_PARSE_ATTEMPTS; attempt++) {
            try {
                return parse(chatClient.summarize(SYSTEM_PROMPT, userPrompt(transcript)));
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    private String userPrompt(String transcript) {
        return """
                다음 회의 전사를 결정 기록 문서로 요약해 주세요.

                <transcript>
                %s
                </transcript>

                다음 JSON 형식으로만 응답하세요:
                {"title": "문서 제목 (60자 이내)", "sections": [{"heading": "결정 사항", "paragraphs": ["..."]},
                {"heading": "근거", "paragraphs": ["..."]}, {"heading": "후속 작업", "paragraphs": ["..."]}]}

                규칙: 후속 작업이 없으면 해당 섹션을 빼세요. paragraphs 항목은 각각 1~3문장의 완결된 한국어 문장.
                존댓말(해요체)로 쓰세요.
                """.formatted(transcript);
    }

    private ThreadSummary parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new ValidationException("summary response is not JSON");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new ValidationException("summary response is not JSON");
        }
        try {
            ThreadSummary summary = objectMapper.readValue(raw.substring(start, end + 1), ThreadSummary.class);
            validate(summary);
            return summary;
        } catch (Exception exception) {
            throw new ValidationException("summary response is not JSON");
        }
    }

    private void validate(ThreadSummary summary) {
        if (summary == null || !StringUtils.hasText(summary.title()) || summary.sections() == null || summary.sections().isEmpty()) {
            throw new ValidationException("summary response is not JSON");
        }
        for (ThreadSummary.Section section : summary.sections()) {
            if (section == null
                    || !StringUtils.hasText(section.heading())
                    || section.paragraphs() == null
                    || section.paragraphs().isEmpty()
                    || section.paragraphs().stream().anyMatch(paragraph -> !StringUtils.hasText(paragraph))) {
                throw new ValidationException("summary response is not JSON");
            }
        }
    }

    private List<BlockPayload> blocks(ThreadSummary summary, TiroNoteSummary note) {
        List<TempBlock> blocks = new ArrayList<>();
        for (ThreadSummary.Section section : summary.sections()) {
            blocks.add(new TempBlock(BlockType.HEADING2, heading(section.heading())));
            for (String paragraph : section.paragraphs()) {
                blocks.add(new TempBlock(BlockType.PARAGRAPH, paragraph(paragraph)));
            }
        }
        String sourceUrl = note.webUrl();
        blocks.add(new TempBlock(BlockType.PARAGRAPH, paragraph("출처: " + sourceUrl)));
        return blocks.stream()
                .map(block -> new BlockPayload(block.type(), block.content(), SourceType.IMPORT, sourceUrl, note.guid()))
                .toList();
    }

    private record TempBlock(BlockType type, JsonNode content) {}

    private JsonNode heading(String text) {
        return objectMapper.valueToTree(Map.of(
                "type", "heading",
                "attrs", Map.of("level", 2),
                "content", List.of(Map.of("type", "text", "text", text))
        ));
    }

    private JsonNode paragraph(String text) {
        return objectMapper.valueToTree(Map.of(
                "type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", text))
        ));
    }

    private TiroPort client() {
        TiroPort tiroClient = client.getIfAvailable();
        if (tiroClient == null) {
            throw new ValidationException("Tiro is not configured");
        }
        return tiroClient;
    }
}
