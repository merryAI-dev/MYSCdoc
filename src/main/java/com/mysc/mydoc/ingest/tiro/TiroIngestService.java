package com.mysc.mydoc.ingest.tiro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.domain.TiroIngestLog;
import com.mysc.mydoc.ingest.SystemMemberInitializer;
import com.mysc.mydoc.repository.MemberRepository;
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
    private final ObjectProvider<TiroPort> client;
    private final DocumentService documents;
    private final TiroIngestLogRepository ingestLogs;
    private final MemberRepository members;
    private final ObjectMapper objectMapper;

    public TiroIngestService(
            ObjectProvider<TiroPort> client,
            DocumentService documents,
            TiroIngestLogRepository ingestLogs,
            MemberRepository members,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.documents = documents;
        this.ingestLogs = ingestLogs;
        this.members = members;
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
        if (note == null) {
            throw new ValidationException("Tiro 노트를 찾을 수 없어요.");
        }
        List<TiroTranscriptParagraph> transcript = client().getTranscriptParagraphs(noteGuid);
        if (transcript.stream().noneMatch(paragraph -> StringUtils.hasText(paragraph.transcript()))) {
            throw new ValidationException("Tiro 노트에 전사 내용이 없어요.");
        }

        UUID ownerId = ownerId(note, memberId);
        var document = documents.create(spaceId, note.title(), ownerId);
        documents.replaceBlocks(document.getId(), blocks(note, transcript), ownerId, ChangeCause.IMPORT);
        ingestLogs.save(new TiroIngestLog(noteGuid, document.getId()));
        return document.getId();
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
