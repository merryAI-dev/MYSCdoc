package com.mysc.mydoc.service;

import com.mysc.mydoc.domain.DocStatus;
import com.mysc.mydoc.domain.Document;
import com.mysc.mydoc.ingest.SlackDmPort;
import com.mysc.mydoc.repository.DocumentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
public class StalenessJob {
    private static final Logger log = LoggerFactory.getLogger(StalenessJob.class);

    private final DocumentRepository documents;
    private final ObjectProvider<SlackDmPort> slackDms;
    private final String documentBaseUrl;
    private final TransactionTemplate tx;

    public StalenessJob(
            DocumentRepository documents,
            ObjectProvider<SlackDmPort> slackDms,
            PlatformTransactionManager transactionManager,
            @Value("${mydoc.document-base-url}") String documentBaseUrl
    ) {
        this.documents = documents;
        this.slackDms = slackDms;
        this.documentBaseUrl = documentBaseUrl;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Seoul")
    public void run() {
        // One transaction per document: a concurrent verify (optimistic-lock conflict) skips that
        // one document instead of rolling back the whole batch, and the DM is sent after commit.
        for (UUID id : candidateIds()) {
            markStale(id).ifPresent(this::sendDm);
        }
    }

    private List<UUID> candidateIds() {
        return tx.execute(status ->
                documents.findStalenessCandidates().stream().map(Document::getId).toList());
    }

    private Optional<Document> markStale(UUID id) {
        try {
            return tx.execute(status -> {
                Document document = documents.findLockedById(id).orElse(null);
                if (document == null || !isStale(document)) {
                    return Optional.<Document>empty();
                }
                document.markStale();
                return Optional.of(document);
            });
        } catch (OptimisticLockingFailureException conflict) {
            log.info("Skipped stale transition; document {} changed concurrently", id);
            return Optional.empty();
        }
    }

    // Re-checks the staleness predicate under the row lock so a verify that landed first is honored.
    private boolean isStale(Document document) {
        if (document.getStatus() != DocStatus.ACTIVE) {
            return false;
        }
        Instant threshold = Instant.now().minus(document.getTtlDays(), ChronoUnit.DAYS);
        Instant reference = document.getVerifiedAt() != null ? document.getVerifiedAt() : document.getCreatedAt();
        return reference.isBefore(threshold);
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
        try {
            dm.sendDm(slackUserId, message(document));
        } catch (RuntimeException exception) {
            log.warn("Stale document DM failed for document {}", document.getId(), exception);
        }
    }

    private String message(Document document) {
        String verifiedAt = document.getVerifiedAt() == null ? "검증 이력 없음" : document.getVerifiedAt().toString();
        return """
                📄 문서가 오래됐어요: %s
                마지막 검증: %s (TTL %d일)
                내용이 아직 유효하면 [검증하기]를, 아니면 문서를 수정해 주세요.
                %s/d/%s
                """.formatted(document.getTitle(), verifiedAt, document.getTtlDays(), documentBaseUrl, document.getId());
    }
}
