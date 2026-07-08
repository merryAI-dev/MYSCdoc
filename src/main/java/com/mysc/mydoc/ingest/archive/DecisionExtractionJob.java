package com.mysc.mydoc.ingest.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.NotFoundException;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.DocStatus;
import com.mysc.mydoc.domain.KnowledgeTriple;
import com.mysc.mydoc.domain.SlackDecisionLog;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.SystemMemberInitializer;
import com.mysc.mydoc.repository.DocumentRepository;
import com.mysc.mydoc.repository.KnowledgeTripleRepository;
import com.mysc.mydoc.repository.MemberRepository;
import com.mysc.mydoc.repository.SlackArchiveMessageRepository;
import com.mysc.mydoc.repository.SlackArchiveMessageRepository.QuietThread;
import com.mysc.mydoc.repository.SlackDecisionLogRepository;
import com.mysc.mydoc.repository.SpaceRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
public class DecisionExtractionJob {
    private static final Logger log = LoggerFactory.getLogger(DecisionExtractionJob.class);

    // 스레드가 이 시간 동안 조용하면 "논의가 끝났다"고 보고 판별을 시작한다.
    static final Duration QUIET_PERIOD = Duration.ofMinutes(30);
    // 1~2개짜리 스레드는 의사결정일 가능성이 낮아 LLM 호출을 아낀다.
    static final int MIN_MESSAGES = 3;

    private final SlackArchiveMessageRepository archives;
    private final SlackDecisionLogRepository decisions;
    private final DecisionExtractPort extractor;
    private final DocumentService documents;
    private final DocumentRepository documentRepository;
    private final KnowledgeTripleRepository triples;
    private final SpaceRepository spaces;
    private final MemberRepository members;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final String defaultSpaceSlug;

    public DecisionExtractionJob(
            SlackArchiveMessageRepository archives,
            SlackDecisionLogRepository decisions,
            DecisionExtractPort extractor,
            DocumentService documents,
            DocumentRepository documentRepository,
            KnowledgeTripleRepository triples,
            SpaceRepository spaces,
            MemberRepository members,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${mydoc.slack.default-space-slug}") String defaultSpaceSlug
    ) {
        this.archives = archives;
        this.decisions = decisions;
        this.extractor = extractor;
        this.documents = documents;
        this.documentRepository = documentRepository;
        this.triples = triples;
        this.spaces = spaces;
        this.members = members;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.defaultSpaceSlug = defaultSpaceSlug;
    }

    @Scheduled(
            fixedDelayString = "${mydoc.slack.decision-job-delay-ms:300000}",
            initialDelayString = "${mydoc.slack.decision-job-initial-delay-ms:60000}")
    public void run() {
        if (!StringUtils.hasText(defaultSpaceSlug)) {
            return;
        }
        for (QuietThread thread : archives.findQuietThreads(MIN_MESSAGES, Instant.now().minus(QUIET_PERIOD))) {
            try {
                process(thread);
            } catch (RuntimeException exception) {
                log.warn("Decision extraction failed for {}:{}", thread.getChannelId(), thread.getThreadTs(), exception);
            }
        }
    }

    void process(QuietThread thread) {
        var existing = decisions.findByChannelIdAndThreadTs(thread.getChannelId(), thread.getThreadTs());
        if (existing.isPresent() && existing.get().getLastTs().equals(thread.getLastTs())) {
            return; // 마지막 판별 이후 새 메시지 없음 — LLM 호출 생략
        }
        List<SlackMessage> messages = archives
                .findByChannelIdAndThreadTsOrderByTs(thread.getChannelId(), thread.getThreadTs())
                .stream()
                .map(message -> new SlackMessage(message.getUserId(), message.getUserId(), message.getText(), message.getTs()))
                .toList();
        // LLM 호출은 트랜잭션 밖에서 (M2 rechunk와 같은 원칙)
        Optional<DecisionExtract> decision = extractor.extract(messages);
        tx.executeWithoutResult(status -> persist(thread, decision));
    }

    private void persist(QuietThread thread, Optional<DecisionExtract> decision) {
        SlackDecisionLog decisionLog = decisions.findByChannelIdAndThreadTs(thread.getChannelId(), thread.getThreadTs())
                .orElseGet(() -> new SlackDecisionLog(thread.getChannelId(), thread.getThreadTs(), thread.getLastTs()));
        if (decision.isPresent()) {
            UUID systemMemberId = systemMemberId();
            if (decisionLog.getDocumentId() == null) {
                var space = spaces.findBySlug(defaultSpaceSlug)
                        .orElseThrow(() -> new NotFoundException("space not found: " + defaultSpaceSlug));
                var document = documents.create(space.getId(), decision.get().title(), systemMemberId);
                documents.replaceBlocks(document.getId(), blocks(decision.get(), thread), systemMemberId, ChangeCause.SLACK_INGEST);
                decisionLog.linkDocument(document.getId());
                replaceTriples(document.getId(), decision.get(), thread);
            } else {
                // 스레드가 이어지면 DRAFT 문서만 갱신한다. 사람이 이미 검증한(ACTIVE) 문서는 덮어쓰지 않는다.
                UUID documentId = decisionLog.getDocumentId();
                documentRepository.findById(documentId)
                        .filter(document -> document.getStatus() == DocStatus.DRAFT)
                        .ifPresent(document -> {
                            documents.replaceBlocks(documentId, blocks(decision.get(), thread), systemMemberId, ChangeCause.SLACK_INGEST);
                            replaceTriples(documentId, decision.get(), thread);
                        });
            }
        }
        decisionLog.examined(thread.getLastTs());
        decisions.save(decisionLog);
    }

    // 문서 블록과 항상 같은 내용을 가리키도록, 재추출 시 트리플도 통째로 교체한다.
    private void replaceTriples(UUID documentId, DecisionExtract extract, QuietThread thread) {
        triples.deleteByDocumentId(documentId);
        for (DecisionExtract.DecisionPoint point : extract.decisionPoints()) {
            String subject = StringUtils.hasText(point.owner()) ? point.owner() : "팀";
            String statement = StringUtils.hasText(point.rationale())
                    ? point.decision() + " — " + point.rationale()
                    : point.decision();
            triples.save(new KnowledgeTriple(documentId, "decision", truncate(statement, 1000),
                    truncate(subject, 200), "결정했다", truncate(point.decision(), 500),
                    thread.getChannelId(), thread.getThreadTs()));
        }
        if (extract.tacitKnowledge() != null) {
            for (DecisionExtract.TacitKnowledge item : extract.tacitKnowledge()) {
                if (item.triples() == null) {
                    continue;
                }
                for (DecisionExtract.Triple triple : item.triples()) {
                    triples.save(new KnowledgeTriple(documentId, item.kind(), truncate(item.statement(), 1000),
                            truncate(triple.subject(), 200), truncate(triple.predicate(), 200), truncate(triple.object(), 500),
                            thread.getChannelId(), thread.getThreadTs()));
                }
            }
        }
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private UUID systemMemberId() {
        return members.findByEmail(SystemMemberInitializer.SYSTEM_MEMBER_EMAIL)
                .orElseThrow(() -> new NotFoundException("system member not found"))
                .getId();
    }

    private List<BlockPayload> blocks(DecisionExtract extract, QuietThread thread) {
        List<BlockPayload> blocks = new ArrayList<>();

        blocks.add(payload(BlockType.HEADING2, heading("요약"), thread));
        for (String line : extract.summary()) {
            blocks.add(payload(BlockType.PARAGRAPH, paragraph(line), thread));
        }

        blocks.add(payload(BlockType.HEADING2, heading("의사결정"), thread));
        for (DecisionExtract.DecisionPoint point : extract.decisionPoints()) {
            blocks.add(payload(BlockType.PARAGRAPH, paragraph("결정: " + point.decision()), thread));
            if (StringUtils.hasText(point.rationale())) {
                blocks.add(payload(BlockType.PARAGRAPH, paragraph("근거: " + point.rationale()), thread));
            }
            if (point.alternatives() != null && !point.alternatives().isEmpty()) {
                blocks.add(payload(BlockType.PARAGRAPH,
                        paragraph("검토했지만 채택하지 않은 대안: " + String.join(", ", point.alternatives())), thread));
            }
            if (StringUtils.hasText(point.owner())) {
                blocks.add(payload(BlockType.PARAGRAPH, paragraph("담당: " + point.owner()), thread));
            }
            if (StringUtils.hasText(point.condition())) {
                blocks.add(payload(BlockType.PARAGRAPH, paragraph("적용 조건: " + point.condition()), thread));
            }
        }

        List<DecisionExtract.TacitKnowledge> tacit = extract.tacitKnowledge();
        if (tacit != null && !tacit.isEmpty()) {
            blocks.add(payload(BlockType.HEADING2, heading("조직의 암묵지"), thread));
            for (DecisionExtract.TacitKnowledge item : tacit) {
                blocks.add(payload(BlockType.PARAGRAPH,
                        paragraph("[" + item.kind() + "] " + item.statement()), thread));
                if (item.triples() != null) {
                    for (DecisionExtract.Triple triple : item.triples()) {
                        blocks.add(payload(BlockType.PARAGRAPH,
                                paragraph("  · " + triple.subject() + " — " + triple.predicate() + " — " + triple.object()), thread));
                    }
                }
            }
        }

        blocks.add(payload(BlockType.PARAGRAPH,
                paragraph("출처: Slack 채널 " + thread.getChannelId() + " 스레드 " + thread.getThreadTs()), thread));
        return blocks;
    }

    private BlockPayload payload(BlockType type, JsonNode content, QuietThread thread) {
        return new BlockPayload(type, content, SourceType.SLACK_INGEST, null, thread.getChannelId() + ":" + thread.getThreadTs());
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
