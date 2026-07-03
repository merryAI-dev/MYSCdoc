package com.mysc.mydoc.service;

import com.mysc.mydoc.domain.Document;
import com.mysc.mydoc.ingest.SlackDmPort;
import com.mysc.mydoc.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class StalenessJob {
    private static final Logger log = LoggerFactory.getLogger(StalenessJob.class);

    private final DocumentRepository documents;
    private final ObjectProvider<SlackDmPort> slackDms;
    private final String documentBaseUrl;

    public StalenessJob(
            DocumentRepository documents,
            ObjectProvider<SlackDmPort> slackDms,
            @Value("${mydoc.document-base-url}") String documentBaseUrl
    ) {
        this.documents = documents;
        this.slackDms = slackDms;
        this.documentBaseUrl = documentBaseUrl;
    }

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void run() {
        for (Document document : documents.findStalenessCandidates()) {
            document.markStale();
            sendDm(document);
        }
    }

    private void sendDm(Document document) {
        String slackUserId = document.getOwner().getSlackUserId();
        if (!StringUtils.hasText(slackUserId)) {
            log.warn("Skipping stale document DM because owner has no slackUserId: {}", document.getId());
            return;
        }
        SlackDmPort dm = slackDms.getIfAvailable();
        if (dm == null) {
            log.warn("Skipping stale document DM because Slack DM port is not configured");
            return;
        }
        dm.sendDm(slackUserId, message(document));
    }

    private String message(Document document) {
        String verifiedAt = document.getVerifiedAt() == null ? "검증 이력 없음" : document.getVerifiedAt().toString();
        return """
                📄 문서가 오래됐어요: %s
                마지막 검증: %s (TTL %d일)
                내용이 아직 유효하면 [검증하기]를, 아니면 문서를 수정해 주세요.
                %s/api/documents/%s
                """.formatted(document.getTitle(), verifiedAt, document.getTtlDays(), documentBaseUrl, document.getId());
    }
}
