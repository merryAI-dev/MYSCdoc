package com.mysc.mydoc.ingest.meet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.NotFoundException;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.MeetIngestLog;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.ingest.JsonThreadSummaryPort;
import com.mysc.mydoc.ingest.SystemMemberInitializer;
import com.mysc.mydoc.ingest.ThreadSummary;
import com.mysc.mydoc.repository.MeetIngestLogRepository;
import com.mysc.mydoc.repository.MemberRepository;
import com.mysc.mydoc.repository.SpaceRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class MeetIngestService {
    private static final Logger log = LoggerFactory.getLogger(MeetIngestService.class);
    private static final int MIN_BODY_CHARS = 200;
    private static final int MAX_BODY_CHARS = 400_000;
    private static final int MAX_FAILURES_PER_CONFERENCE = 10;
    private static final String MEETING_SYSTEM_PROMPT = """
            너는 회의록을 사내 지식 문서로 정리하는 도우미다.
            아래 회의록 텍스트에서 결정 사항, 근거, 후속 작업을 뽑아 반드시 아래 JSON만 출력한다.
            코드펜스 없이 순수 JSON만 출력한다.
            {"title": "...", "sections": [{"heading": "결정 사항", "paragraphs": ["..."]},
            {"heading": "근거", "paragraphs": ["..."]}, {"heading": "후속 작업", "paragraphs": ["..."]}]}
            잡담·인사말은 제외한다. title은 회의 주제를 30자 이내 한국어로.
            """;

    private final ObjectProvider<MeetArtifactGateway> artifacts;
    private final ObjectProvider<MeetDocumentGateway> documentsGateway;
    private final JsonThreadSummaryPort summaries;
    private final DocumentService documents;
    private final SpaceRepository spaces;
    private final MemberRepository members;
    private final MeetIngestLogRepository ingestLogs;
    private final ObjectMapper objectMapper;
    private final String subscribedUser;
    private final String defaultSpaceSlug;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<String, Integer> failures = new ConcurrentHashMap<>();

    public MeetIngestService(
            ObjectProvider<MeetArtifactGateway> artifacts,
            ObjectProvider<MeetDocumentGateway> documentsGateway,
            JsonThreadSummaryPort summaries,
            DocumentService documents,
            SpaceRepository spaces,
            MemberRepository members,
            MeetIngestLogRepository ingestLogs,
            ObjectMapper objectMapper,
            @Value("${mydoc.meet.subscribed-user:}") String subscribedUser,
            @Value("${mydoc.meet.default-space-slug:}") String defaultSpaceSlug,
            PlatformTransactionManager transactionManager
    ) {
        this.artifacts = artifacts;
        this.documentsGateway = documentsGateway;
        this.summaries = summaries;
        this.documents = documents;
        this.spaces = spaces;
        this.members = members;
        this.ingestLogs = ingestLogs;
        this.objectMapper = objectMapper;
        this.subscribedUser = subscribedUser;
        this.defaultSpaceSlug = defaultSpaceSlug;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void onArtifactGenerated(String resourceName, ArtifactKind kind) {
        String conferenceRecord = conferenceRecord(resourceName);
        if (ingestLogs.existsByConferenceRecord(conferenceRecord)) {
            return;
        }
        try {
            MeetArtifact artifact = artifacts.getObject().get(resourceName, kind);
            if (artifact == null || !"FILE_GENERATED".equals(artifact.state())) {
                throw new MeetRetryableException("Meet artifact is not ready");
            }
            String text = documentsGateway.getObject().exportText(artifact.documentId());
            if (!StringUtils.hasText(text) || text.trim().length() < MIN_BODY_CHARS) {
                throw new MeetRetryableException("Meet document body is not ready");
            }
            ingestMeeting(conferenceRecord, artifact.documentId(), text);
            failures.remove(conferenceRecord);
        } catch (MeetRetryableException exception) {
            throwOrPoisonAck(conferenceRecord, exception);
        } catch (RuntimeException exception) {
            throwOrPoisonAck(conferenceRecord, new MeetRetryableException("Meet ingest failed", exception));
        }
    }

    public UUID ingestMeeting(String conferenceRecord, String documentId, String text) {
        String body = text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) : text;
        ThreadSummary summary = summaries.summarize(MEETING_SYSTEM_PROMPT, body);
        return transactions.execute(status -> {
            if (ingestLogs.existsByConferenceRecord(conferenceRecord)) {
                return ingestLogs.findByConferenceRecord(conferenceRecord).orElseThrow().getDocumentId();
            }
            var space = spaces.findBySlug(defaultSpaceSlug)
                    .orElseThrow(() -> new NotFoundException("space not found: " + defaultSpaceSlug));
            Member owner = members.findByEmail(subscribedUser)
                    .orElseGet(() -> members.findByEmail(SystemMemberInitializer.SYSTEM_MEMBER_EMAIL)
                            .orElseThrow(() -> new NotFoundException("system member not found")));
            var document = documents.create(space.getId(), summary.title(), owner.getId());
            documents.replaceBlocks(document.getId(), blocks(summary, conferenceRecord, documentId), owner.getId(), ChangeCause.MEETING_INGEST);
            ingestLogs.save(new MeetIngestLog(conferenceRecord, document.getId()));
            return document.getId();
        });
    }

    private void throwOrPoisonAck(String conferenceRecord, MeetRetryableException exception) {
        int count = failures.merge(conferenceRecord, 1, Integer::sum);
        if (count >= MAX_FAILURES_PER_CONFERENCE) {
            log.error("Meet ingest poison message skipped after {} failures: {}", count, conferenceRecord, exception);
            return;
        }
        throw exception;
    }

    private String conferenceRecord(String resourceName) {
        if (!StringUtils.hasText(resourceName) || !resourceName.startsWith("conferenceRecords/")) {
            throw new ValidationException("invalid Meet resource name");
        }
        int nextSlash = resourceName.indexOf('/', "conferenceRecords/".length());
        if (nextSlash < 0) {
            return resourceName;
        }
        return resourceName.substring(0, nextSlash);
    }

    private List<BlockPayload> blocks(ThreadSummary summary, String conferenceRecord, String documentId) {
        String sourceUrl = "https://docs.google.com/document/d/" + documentId;
        List<BlockPayload> blocks = new ArrayList<>();
        for (ThreadSummary.Section section : summary.sections()) {
            blocks.add(new BlockPayload(BlockType.HEADING2, heading(section.heading()), SourceType.MEETING_INGEST, sourceUrl, conferenceRecord));
            for (String paragraph : section.paragraphs()) {
                blocks.add(new BlockPayload(BlockType.PARAGRAPH, paragraph(paragraph), SourceType.MEETING_INGEST, sourceUrl, conferenceRecord));
            }
        }
        blocks.add(new BlockPayload(BlockType.PARAGRAPH, paragraph("출처: " + sourceUrl), SourceType.MEETING_INGEST, sourceUrl, conferenceRecord));
        return blocks;
    }

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
}
