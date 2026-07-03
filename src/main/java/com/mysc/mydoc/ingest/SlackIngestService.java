package com.mysc.mydoc.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.NotFoundException;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.SlackIngestLog;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.repository.MemberRepository;
import com.mysc.mydoc.repository.SlackIngestLogRepository;
import com.mysc.mydoc.repository.SpaceRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SlackIngestService {
    private static final Logger log = LoggerFactory.getLogger(SlackIngestService.class);

    private final ObjectProvider<SlackGateway> slack;
    private final ThreadSummaryPort summaries;
    private final DocumentService documents;
    private final SpaceRepository spaces;
    private final MemberRepository members;
    private final SlackIngestLogRepository ingestLogs;
    private final ObjectMapper objectMapper;
    private final String defaultSpaceSlug;
    private final String documentBaseUrl;

    public SlackIngestService(
            ObjectProvider<SlackGateway> slack,
            ThreadSummaryPort summaries,
            DocumentService documents,
            SpaceRepository spaces,
            MemberRepository members,
            SlackIngestLogRepository ingestLogs,
            ObjectMapper objectMapper,
            @Value("${mydoc.slack.default-space-slug}") String defaultSpaceSlug,
            @Value("${mydoc.document-base-url}") String documentBaseUrl
    ) {
        this.slack = slack;
        this.summaries = summaries;
        this.documents = documents;
        this.spaces = spaces;
        this.members = members;
        this.ingestLogs = ingestLogs;
        this.objectMapper = objectMapper;
        this.defaultSpaceSlug = defaultSpaceSlug;
        this.documentBaseUrl = documentBaseUrl;
    }

    @Transactional
    public void onReactionAdded(String channelId, String messageTs, String reactorUserId) {
        SlackGateway slackGateway = slack();
        try {
            SlackThread thread = slackGateway.thread(channelId, messageTs);
            ingestLogs.findByChannelIdAndThreadTs(channelId, thread.threadTs())
                    .ifPresentOrElse(
                            log -> slackGateway.reply(channelId, thread.threadTs(), documentUrl(log.getDocumentId())),
                            () -> ingestThread(channelId, thread.threadTs(), thread.messages(), reactorUserId, thread.permalink())
                    );
        } catch (RuntimeException exception) {
            log.warn("Slack thread ingest failed", exception);
            slackGateway.reply(channelId, messageTs, "요약에 실패했어요. 잠시 후 다시 이모지를 달아주세요.");
        }
    }

    @Transactional
    public UUID ingestThread(String channelId, String threadTs, List<SlackMessage> messages) {
        return ingestThread(channelId, threadTs, messages, null, "");
    }

    private UUID ingestThread(String channelId, String threadTs, List<SlackMessage> messages, String reactorUserId, String permalink) {
        if (!StringUtils.hasText(defaultSpaceSlug)) {
            throw new ValidationException("SLACK_DEFAULT_SPACE_SLUG is required");
        }
        var space = spaces.findBySlug(defaultSpaceSlug)
                .orElseThrow(() -> new NotFoundException("space not found: " + defaultSpaceSlug));
        Member owner = owner(reactorUserId);
        ThreadSummary summary = summaries.summarize(messages);
        var document = documents.create(space.getId(), summary.title(), owner.getId());
        documents.replaceBlocks(document.getId(), blocks(summary, threadTs, permalink), owner.getId(), ChangeCause.SLACK_INGEST);
        ingestLogs.save(new SlackIngestLog(channelId, threadTs, document.getId()));
        slack().reply(channelId, threadTs, documentUrl(document.getId()));
        return document.getId();
    }

    private Member owner(String reactorUserId) {
        if (StringUtils.hasText(reactorUserId)) {
            var member = slack().userEmail(reactorUserId).flatMap(members::findByEmail);
            if (member.isPresent()) {
                return member.get();
            }
        }
        return members.findByEmail(SystemMemberInitializer.SYSTEM_MEMBER_EMAIL)
                .orElseThrow(() -> new NotFoundException("system member not found"));
    }

    private List<BlockPayload> blocks(ThreadSummary summary, String threadTs, String permalink) {
        List<TempBlock> blocks = new ArrayList<>();
        for (ThreadSummary.Section section : summary.sections()) {
            blocks.add(block("HEADING2", heading(section.heading())));
            for (String paragraph : section.paragraphs()) {
                blocks.add(block("PARAGRAPH", paragraph(paragraph)));
            }
        }
        blocks.add(block("PARAGRAPH", paragraph("출처: " + permalink)));
        return blocks.stream()
                .map(block -> new BlockPayload(block.type(), block.content(), SourceType.SLACK_INGEST, permalink, threadTs))
                .toList();
    }

    private TempBlock block(String type, JsonNode content) {
        return new TempBlock(com.mysc.mydoc.domain.BlockType.valueOf(type), content);
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

    private String documentUrl(UUID documentId) {
        return documentBaseUrl + "/api/documents/" + documentId;
    }

    private SlackGateway slack() {
        SlackGateway slackGateway = slack.getIfAvailable();
        if (slackGateway == null) {
            throw new ValidationException("Slack gateway is not configured");
        }
        return slackGateway;
    }

    private record TempBlock(com.mysc.mydoc.domain.BlockType type, JsonNode content) {}
}
