package com.mysc.mydoc.ingest.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.GoogleDriveIngestLog;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.archive.DecisionExtract;
import com.mysc.mydoc.ingest.archive.DecisionExtractPort;
import com.mysc.mydoc.repository.GoogleDriveIngestLogRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import com.mysc.mydoc.service.KnowledgeTripleWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 회사 Google Drive 회의록 폴더를 일회성으로 mydoc에 마이그레이션한다(원문 보존, Tiro와 같은 원칙).
 * 가져온 문서도 Slack과 같은 의사결정/암묵지 추출 파이프라인을 타서 지식그래프에 반영된다.
 */
@Service
public class GoogleDriveIngestService {
    private static final Logger log = LoggerFactory.getLogger(GoogleDriveIngestService.class);
    // Gemini 입력 폭주 방지 — M7 Meet 아티팩트와 같은 400,000자 절단 정책을 재사용한다.
    private static final int MAX_EXTRACT_CHARS = 400_000;

    private final ObjectProvider<GoogleDriveGateway> drive;
    private final DocumentService documents;
    private final GoogleDriveIngestLogRepository ingestLogs;
    private final DecisionExtractPort extractor;
    private final KnowledgeTripleWriter tripleWriter;
    private final ObjectMapper objectMapper;

    public GoogleDriveIngestService(
            ObjectProvider<GoogleDriveGateway> drive,
            DocumentService documents,
            GoogleDriveIngestLogRepository ingestLogs,
            DecisionExtractPort extractor,
            KnowledgeTripleWriter tripleWriter,
            ObjectMapper objectMapper
    ) {
        this.drive = drive;
        this.documents = documents;
        this.ingestLogs = ingestLogs;
        this.extractor = extractor;
        this.tripleWriter = tripleWriter;
        this.objectMapper = objectMapper;
    }

    public record ImportSummary(int found, int imported, int skippedDuplicate, int failed, int documented) {}

    public List<GoogleDriveGateway.DriveDoc> browse(String folderId) {
        return gateway().listGoogleDocs(folderId);
    }

    /** 폴더의 모든 Google Docs를 순회 임포트한다. 한 문서 실패가 나머지를 막지 않는다. */
    public ImportSummary importFolder(String folderId, UUID spaceId, UUID memberId) {
        List<GoogleDriveGateway.DriveDoc> found = browse(folderId);
        int imported = 0, skipped = 0, failed = 0, documented = 0;
        for (GoogleDriveGateway.DriveDoc doc : found) {
            try {
                ImportOutcome outcome = importDoc(doc, spaceId, memberId);
                if (outcome == ImportOutcome.SKIPPED) {
                    skipped++;
                } else {
                    imported++;
                    if (outcome == ImportOutcome.IMPORTED_AND_DOCUMENTED) {
                        documented++;
                    }
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("Google Drive 문서 가져오기 실패: {} ({})", doc.name(), doc.fileId(), exception);
            }
        }
        return new ImportSummary(found.size(), imported, skipped, failed, documented);
    }

    private enum ImportOutcome { SKIPPED, IMPORTED, IMPORTED_AND_DOCUMENTED }

    private ImportOutcome importDoc(GoogleDriveGateway.DriveDoc doc, UUID spaceId, UUID memberId) {
        if (ingestLogs.findByDriveFileId(doc.fileId()).isPresent()) {
            return ImportOutcome.SKIPPED;
        }
        String text = gateway().exportText(doc.fileId());
        if (!StringUtils.hasText(text)) {
            throw new ValidationException("빈 문서예요: " + doc.name());
        }
        UUID documentId = createDocument(doc, text, spaceId, memberId);
        boolean documented = extractKnowledge(documentId, doc, text);
        return documented ? ImportOutcome.IMPORTED_AND_DOCUMENTED : ImportOutcome.IMPORTED;
    }

    // DocumentService.create/replaceBlocks는 각자 자체 트랜잭션이라(Spring Data 프록시),
    // 이 메서드에 @Transactional을 붙여도 같은 클래스 내부 호출이라 적용되지 않는다 — 붙이지 않는다.
    private UUID createDocument(GoogleDriveGateway.DriveDoc doc, String text, UUID spaceId, UUID memberId) {
        var document = documents.create(spaceId, doc.name(), memberId);
        documents.replaceBlocks(document.getId(), blocks(text, doc), memberId, ChangeCause.IMPORT);
        ingestLogs.save(new GoogleDriveIngestLog(doc.fileId(), document.getId()));
        return document.getId();
    }

    // LLM 호출은 트랜잭션 밖에서 (M2 rechunk와 같은 원칙). 실패해도 원본 문서 임포트 자체는 이미 커밋돼 있다.
    private boolean extractKnowledge(UUID documentId, GoogleDriveGateway.DriveDoc doc, String text) {
        String truncated = text.length() > MAX_EXTRACT_CHARS ? text.substring(0, MAX_EXTRACT_CHARS) : text;
        List<SlackMessage> pseudoThread = List.of(new SlackMessage("drive", doc.name(), truncated, doc.fileId()));
        Optional<DecisionExtract> extract;
        try {
            extract = extractor.extract(pseudoThread);
        } catch (RuntimeException exception) {
            log.warn("Google Drive 지식 추출 실패: {}", doc.name(), exception);
            return false;
        }
        extract.ifPresent(value -> tripleWriter.replace(documentId, value, "drive", doc.fileId()));
        return extract.isPresent();
    }

    private List<BlockPayload> blocks(String text, GoogleDriveGateway.DriveDoc doc) {
        List<BlockPayload> blocks = new ArrayList<>();
        for (String paragraph : text.split("\n+")) {
            String trimmed = paragraph.trim();
            if (StringUtils.hasText(trimmed)) {
                blocks.add(new BlockPayload(BlockType.PARAGRAPH, paragraph(trimmed), SourceType.IMPORT,
                        "https://docs.google.com/document/d/" + doc.fileId(), doc.fileId()));
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(new BlockPayload(BlockType.PARAGRAPH, paragraph("(빈 문서)"), SourceType.IMPORT,
                    "https://docs.google.com/document/d/" + doc.fileId(), doc.fileId()));
        }
        return blocks;
    }

    private JsonNode paragraph(String text) {
        return objectMapper.valueToTree(Map.of(
                "type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", text))
        ));
    }

    private GoogleDriveGateway gateway() {
        GoogleDriveGateway g = drive.getIfAvailable();
        if (g == null) {
            throw new ValidationException("Google Drive is not configured");
        }
        return g;
    }
}
