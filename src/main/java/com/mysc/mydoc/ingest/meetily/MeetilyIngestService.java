package com.mysc.mydoc.ingest.meetily;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.MeetilyIngestLog;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.archive.DecisionExtract;
import com.mysc.mydoc.ingest.archive.DecisionExtractPort;
import com.mysc.mydoc.repository.MeetilyIngestLogRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Meetily 회의를 mydoc 문서로 가져온다 — Tiro(M8/M16)와 같은 원칙:
 * 원문 보존(전사를 그대로 블록화), dedup 장부(meetily_ingest_log), 문서 생성은 한 트랜잭션,
 * LLM 지식 추출은 트랜잭션 밖(실패해도 원문 임포트는 커밋 유지), 트리플은 KnowledgeTripleWriter 공유.
 */
@Service
public class MeetilyIngestService {
    private static final Logger log = LoggerFactory.getLogger(MeetilyIngestService.class);
    private static final int MAX_EXTRACT_CHARS = 400_000;

    private final ObjectProvider<MeetilyPort> meetily;
    private final DocumentService documents;
    private final MeetilyIngestLogRepository ingestLogs;
    private final DecisionExtractPort extractor;
    private final KnowledgeTripleWriter tripleWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public MeetilyIngestService(
            ObjectProvider<MeetilyPort> meetily,
            DocumentService documents,
            MeetilyIngestLogRepository ingestLogs,
            DecisionExtractPort extractor,
            KnowledgeTripleWriter tripleWriter,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.meetily = meetily;
        this.documents = documents;
        this.ingestLogs = ingestLogs;
        this.extractor = extractor;
        this.tripleWriter = tripleWriter;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<MeetilyMeeting> browse() {
        return port().listMeetings();
    }

    public UUID importMeeting(String meetingId, UUID spaceId, UUID memberId) {
        // meeting_id는 DB varchar(64) — 초과분은 여기서 400으로 끊는다
        // (안 끊으면 INSERT의 DataIntegrityViolation이 동시성 복구 catch로 흘러 무한 재귀성 오류가 된다).
        if (!StringUtils.hasText(meetingId) || meetingId.length() > 64) {
            throw new ValidationException("잘못된 Meetily 회의 ID예요.");
        }
        var existing = ingestLogs.findByMeetingId(meetingId);
        if (existing.isPresent()) {
            return existing.get().getDocumentId();
        }
        MeetilyMeetingDetail meeting = port().getMeeting(meetingId);
        if (meeting == null) {
            throw new ValidationException("Meetily 회의를 찾을 수 없어요.");
        }
        String transcript = transcriptText(meeting);
        if (!StringUtils.hasText(transcript)) {
            throw new ValidationException("Meetily 회의에 전사 내용이 없어요.");
        }
        String title = StringUtils.hasText(meeting.title()) ? meeting.title() : "Meetily 회의 " + meetingId;

        UUID documentId;
        try {
            documentId = tx.execute(status -> {
                var document = documents.create(spaceId, title, memberId);
                documents.replaceBlocks(document.getId(), blocks(meeting), memberId, ChangeCause.IMPORT);
                ingestLogs.save(new MeetilyIngestLog(meetingId, document.getId()));
                return document.getId();
            });
        } catch (DataIntegrityViolationException race) {
            // 동시 임포트(버튼 연타) — meeting_id UNIQUE가 먼저 커밋됐다면 멱등하게 기존 문서를 돌려준다.
            return ingestLogs.findByMeetingId(meetingId)
                    .map(MeetilyIngestLog::getDocumentId)
                    .orElseThrow(() -> race);
        }
        extractKnowledge(documentId, title, meetingId, transcript);
        return documentId;
    }

    /** LLM 추출은 트랜잭션 밖 — 실패해도 원문 문서는 이미 커밋돼 있고, 수동 동기화로 따라잡을 수 있다. */
    private void extractKnowledge(UUID documentId, String title, String meetingId, String transcript) {
        String truncated = transcript.length() > MAX_EXTRACT_CHARS
                ? transcript.substring(0, MAX_EXTRACT_CHARS) : transcript;
        List<SlackMessage> pseudoThread = List.of(new SlackMessage("meetily", title, truncated, meetingId));
        Optional<DecisionExtract> extract;
        try {
            extract = extractor.extract(pseudoThread);
        } catch (RuntimeException exception) {
            log.warn("Meetily 지식 추출 실패: {}", title, exception);
            return;
        }
        extract.ifPresent(value ->
                tx.executeWithoutResult(status -> tripleWriter.replace(documentId, value, "meetily", meetingId)));
    }

    /** 추출 입력 = 순수 전사 텍스트만 (수동 동기화의 블록 재구성 필터와 일치). */
    private String transcriptText(MeetilyMeetingDetail meeting) {
        return meeting.segments().stream()
                .map(MeetilyMeetingDetail.Segment::text)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private List<BlockPayload> blocks(MeetilyMeetingDetail meeting) {
        List<TempBlock> blocks = new ArrayList<>();
        blocks.add(new TempBlock(BlockType.HEADING2, heading("회의 정보")));
        // 메타 라벨은 지식 동기화의 재구성 필터(작성자|참석자|녹음 시작|길이|출처)와 맞춘다.
        if (StringUtils.hasText(meeting.createdAt())) {
            blocks.add(new TempBlock(BlockType.PARAGRAPH, paragraph("녹음 시작: " + meeting.createdAt())));
        }
        blocks.add(new TempBlock(BlockType.PARAGRAPH, paragraph("출처: Meetily (" + meeting.id() + ")")));

        blocks.add(new TempBlock(BlockType.HEADING2, heading("전사 원문")));
        for (MeetilyMeetingDetail.Segment segment : meeting.segments()) {
            if (StringUtils.hasText(segment.text())) {
                blocks.add(new TempBlock(BlockType.PARAGRAPH,
                        paragraph(timeRange(segment) + segment.text().trim())));
            }
        }
        return blocks.stream()
                .map(block -> new BlockPayload(block.type(), block.content(),
                        SourceType.IMPORT, null, meeting.id()))
                .toList();
    }

    private String timeRange(MeetilyMeetingDetail.Segment segment) {
        if (segment.audioStartTime() == null || segment.audioEndTime() == null) {
            return "";
        }
        return "[%.0fs - %.0fs] ".formatted(segment.audioStartTime(), segment.audioEndTime());
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

    private MeetilyPort port() {
        MeetilyPort port = meetily.getIfAvailable();
        if (port == null) {
            // 사용자 입력 오류(400)가 아니라 서버 설정 부재 — 503으로 구분해 내려준다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Meetily가 설정되지 않았어요 (MEETILY_BASE_URL).");
        }
        return port;
    }
}
