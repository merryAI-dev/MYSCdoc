package com.mysc.mydoc.ingest.tiro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.domain.TiroIngestLog;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.SystemMemberInitializer;
import com.mysc.mydoc.ingest.archive.DecisionExtract;
import com.mysc.mydoc.ingest.archive.DecisionExtractPort;
import com.mysc.mydoc.repository.MemberRepository;
import com.mysc.mydoc.repository.TiroIngestLogRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import com.mysc.mydoc.service.EventDates;
import com.mysc.mydoc.service.KnowledgeTripleWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class TiroIngestService {
    private static final Logger log = LoggerFactory.getLogger(TiroIngestService.class);
    // Drive 임포트와 같은 원칙: Gemini 입력 폭주 방지 절단 상한.
    private static final int MAX_EXTRACT_CHARS = 400_000;

    private final ObjectProvider<TiroPort> client;
    private final DocumentService documents;
    private final TiroIngestLogRepository ingestLogs;
    private final MemberRepository members;
    private final DecisionExtractPort extractor;
    private final KnowledgeTripleWriter tripleWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public TiroIngestService(
            ObjectProvider<TiroPort> client,
            DocumentService documents,
            TiroIngestLogRepository ingestLogs,
            MemberRepository members,
            DecisionExtractPort extractor,
            KnowledgeTripleWriter tripleWriter,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.client = client;
        this.documents = documents;
        this.ingestLogs = ingestLogs;
        this.members = members;
        this.extractor = extractor;
        this.tripleWriter = tripleWriter;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<TiroNoteSummary> browse(String keyword) {
        return client().listNotes(keyword);
    }

    public UUID importNote(String noteGuid, UUID spaceId, UUID memberId) {
        var existing = ingestLogs.findByNoteGuid(noteGuid);
        if (existing.isPresent()) {
            return existing.get().getDocumentId();
        }

        TiroNoteSummary note = client().getNote(noteGuid);
        if (note == null) {
            throw new ValidationException("Tiro 노트를 찾을 수 없어요.");
        }
        List<TiroTranscriptParagraph> transcript = client().getTranscriptParagraphs(noteGuid);
        if (transcript.stream().noneMatch(paragraph -> StringUtils.hasText(paragraph.transcript()))) {
            throw new ValidationException("Tiro 노트에 전사 내용이 없어요.");
        }

        // 문서 생성·블록·dedup 로그는 한 트랜잭션으로 (Drive와 동일 — 고아 문서 방지),
        // LLM 호출은 트랜잭션 밖에서 — 추출이 실패해도 원문 임포트는 이미 커밋돼 있다.
        UUID ownerId = ownerId(note, memberId);
        UUID documentId;
        try {
            documentId = tx.execute(status -> {
                var document = documents.create(spaceId, note.title(), ownerId);
                // 녹음 시작 시각을 사건 시각으로 — 시간축 기준(트리플로 복사됨).
                EventDates.fromIsoish(note.recordingStartAt()).ifPresent(document::setEventAt);
                documents.replaceBlocks(document.getId(), blocks(note, transcript), ownerId, ChangeCause.IMPORT);
                ingestLogs.save(new TiroIngestLog(noteGuid, document.getId()));
                return document.getId();
            });
        } catch (DataIntegrityViolationException race) {
            // 동시 임포트(버튼 연타·재시도)로 note_guid UNIQUE가 먼저 커밋됐다 — 멱등하게 기존 문서를 돌려준다.
            return ingestLogs.findByNoteGuid(noteGuid)
                    .map(TiroIngestLog::getDocumentId)
                    .orElseThrow(() -> race);
        }
        extractKnowledge(documentId, note.title(), noteGuid, transcriptText(transcript));
        return documentId;
    }

    /** Slack·Drive와 같은 추출 파이프라인으로 회의록 지식을 그래프에 반영한다. */
    private void extractKnowledge(UUID documentId, String title, String noteGuid, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String truncated = text.length() > MAX_EXTRACT_CHARS ? text.substring(0, MAX_EXTRACT_CHARS) : text;
        List<SlackMessage> pseudoThread = List.of(new SlackMessage("tiro", title, truncated, noteGuid));
        Optional<DecisionExtract> extract;
        try {
            extract = extractor.extract(pseudoThread);
        } catch (RuntimeException exception) {
            log.warn("Tiro 지식 추출 실패 (원문 임포트는 완료): {}", title, exception);
            return;
        }
        extract.ifPresent(value ->
                tx.executeWithoutResult(status -> tripleWriter.replace(documentId, value, "tiro", noteGuid)));
    }

    private String transcriptText(List<TiroTranscriptParagraph> transcript) {
        return transcript.stream()
                .map(TiroTranscriptParagraph::transcript)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private UUID ownerId(TiroNoteSummary note, UUID importerId) {
        String ownerEmail = ownerEmail(note);
        if (StringUtils.hasText(ownerEmail)) {
            var owner = members.findByEmail(ownerEmail);
            if (owner.isPresent()) {
                return owner.get().getId();
            }
        }
        if (importerId != null && members.existsById(importerId)) {
            return importerId;
        }
        return members.findByEmail(SystemMemberInitializer.SYSTEM_MEMBER_EMAIL)
                .map(Member::getId)
                .orElseThrow(() -> new ValidationException("document owner is not available"));
    }

    private String ownerEmail(TiroNoteSummary note) {
        if (note.collaborators() == null) {
            return null;
        }
        return note.collaborators().stream()
                .filter(collaborator -> "OWNER".equals(collaborator.role()))
                .map(TiroNoteSummary.Collaborator::email)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private List<BlockPayload> blocks(TiroNoteSummary note, List<TiroTranscriptParagraph> transcript) {
        List<TempBlock> blocks = new ArrayList<>();
        blocks.add(new TempBlock(BlockType.HEADING2, heading("회의 정보")));
        addLine(blocks, "작성자", collaborators(note));
        addLine(blocks, "참석자", participants(note));
        addLine(blocks, "녹음 시작", note.recordingStartAt());
        addLine(blocks, "길이", duration(note.recordingDurationSeconds()));
        addLine(blocks, "출처", note.webUrl());

        blocks.add(new TempBlock(BlockType.HEADING2, heading("전사 원문")));
        for (TiroTranscriptParagraph paragraph : transcript) {
            if (StringUtils.hasText(paragraph.transcript())) {
                blocks.add(new TempBlock(BlockType.PARAGRAPH, paragraph(timeRange(paragraph) + " " + paragraph.transcript().trim())));
            }
        }

        List<String> summaries = transcript.stream()
                .map(TiroTranscriptParagraph::summary)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (!summaries.isEmpty()) {
            blocks.add(new TempBlock(BlockType.HEADING2, heading("Tiro 제공 요약")));
            summaries.forEach(summary -> blocks.add(new TempBlock(BlockType.PARAGRAPH, paragraph(summary))));
        }
        return blocks.stream()
                .map(block -> new BlockPayload(block.type(), block.content(), SourceType.IMPORT, note.webUrl(), note.guid()))
                .toList();
    }

    private void addLine(List<TempBlock> blocks, String label, String value) {
        if (StringUtils.hasText(value)) {
            blocks.add(new TempBlock(BlockType.PARAGRAPH, paragraph(label + ": " + value)));
        }
    }

    private String collaborators(TiroNoteSummary note) {
        if (note.collaborators() == null || note.collaborators().isEmpty()) {
            return "";
        }
        return note.collaborators().stream()
                .map(collaborator -> person(collaborator.name(), collaborator.email()))
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String participants(TiroNoteSummary note) {
        if (note.participants() == null || note.participants().isEmpty()) {
            return "";
        }
        return note.participants().stream()
                .map(participant -> person(participant.name(), participant.email()))
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String person(String name, String email) {
        if (StringUtils.hasText(name) && StringUtils.hasText(email)) {
            return name + " <" + email + ">";
        }
        return StringUtils.hasText(name) ? name : email;
    }

    private String duration(Integer seconds) {
        if (seconds == null) {
            return "";
        }
        int minutes = Math.max(1, Math.round(seconds / 60.0f));
        return minutes + "분";
    }

    private String timeRange(TiroTranscriptParagraph paragraph) {
        if (StringUtils.hasText(paragraph.timeFrom()) && StringUtils.hasText(paragraph.timeTo())) {
            return "[" + paragraph.timeFrom() + " - " + paragraph.timeTo() + "]";
        }
        if (StringUtils.hasText(paragraph.timeFrom())) {
            return "[" + paragraph.timeFrom() + "]";
        }
        return "";
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
