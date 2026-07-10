package com.mysc.mydoc.ingest.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.Block;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.GoogleDriveIngestLog;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.archive.DecisionExtract;
import com.mysc.mydoc.ingest.archive.DecisionExtractPort;
import com.mysc.mydoc.repository.BlockRepository;
import com.mysc.mydoc.repository.DocumentRepository;
import com.mysc.mydoc.repository.GoogleDriveIngestLogRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import com.mysc.mydoc.service.KnowledgeTripleWriter;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 회사 Google Drive 회의록 폴더를 일회성으로 mydoc에 마이그레이션한다(원문 보존, Tiro와 같은 원칙).
 * 가져온 문서도 Slack과 같은 의사결정/암묵지 추출 파이프라인을 타서 지식그래프에 반영된다.
 *
 * 프로덕션 하드닝:
 * - 폴더 임포트는 문서당 Drive export + Gemini 호출로 수 분이 걸릴 수 있어 HTTP 요청과 분리된
 *   백그라운드 잡으로 돌리고(프록시 타임아웃 회피), 진행상황은 status 폴링으로 노출한다.
 * - 한 번에 하나의 임포트만 돌게 가드한다(버튼 연타·중복 실행 방지). 단일 인스턴스 배포 전제(DEPLOY-PLAN).
 * - 문서 생성·블록·dedup 로그는 문서당 하나의 트랜잭션으로 원자화한다(고아 문서 방지).
 * - Gemini 호출 사이에 짧은 pacing을 둬 무료티어 429 폭주를 완화한다(GeminiRetry의 백오프와 별개 보험).
 */
@Service
public class GoogleDriveIngestService {
    private static final Logger log = LoggerFactory.getLogger(GoogleDriveIngestService.class);
    // Gemini 입력 폭주 방지 — M7 Meet 아티팩트와 같은 400,000자 절단 정책을 재사용한다.
    private static final int MAX_EXTRACT_CHARS = 400_000;
    // 초대형 문서 하나가 revision snapshot·DB를 폭발시키지 않게 블록화 상한을 둔다.
    private static final int MAX_BLOCK_PARAGRAPHS = 1_000;
    private static final long LLM_PACING_MS = 1_000;

    private final ObjectProvider<GoogleDriveGateway> drive;
    private final DocumentService documents;
    private final DocumentRepository documentRepository;
    private final BlockRepository blockRepository;
    private final GoogleDriveIngestLogRepository ingestLogs;
    private final DecisionExtractPort extractor;
    private final KnowledgeTripleWriter tripleWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "drive-import-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ImportJob currentJob;

    public GoogleDriveIngestService(
            ObjectProvider<GoogleDriveGateway> drive,
            DocumentService documents,
            DocumentRepository documentRepository,
            BlockRepository blockRepository,
            GoogleDriveIngestLogRepository ingestLogs,
            DecisionExtractPort extractor,
            KnowledgeTripleWriter tripleWriter,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.drive = drive;
        this.documents = documents;
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.ingestLogs = ingestLogs;
        this.extractor = extractor;
        this.tripleWriter = tripleWriter;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    // ── 잡 상태 ────────────────────────────────────────────────────
    public enum JobStatus { RUNNING, DONE, FAILED }

    /** 진행상황 스냅샷 — UI가 2초 간격으로 폴링한다. */
    public record ImportJobView(String jobId, JobStatus status, int found, int processed,
                                int imported, int skippedDuplicate, int failed, int documented,
                                String currentDoc, String error, Instant startedAt) {}

    private static final class ImportJob {
        final String jobId = UUID.randomUUID().toString();
        final Instant startedAt = Instant.now();
        volatile JobStatus status = JobStatus.RUNNING;
        volatile int found;
        volatile int processed;
        volatile int imported;
        volatile int skippedDuplicate;
        volatile int failed;
        volatile int documented;
        volatile String currentDoc = "";
        volatile String error;

        ImportJobView view() {
            return new ImportJobView(jobId, status, found, processed, imported, skippedDuplicate,
                    failed, documented, currentDoc, error, startedAt);
        }
    }

    // ── 공개 API ───────────────────────────────────────────────────
    public List<GoogleDriveGateway.DriveDoc> browse(String folderId) {
        return gateway().listGoogleDocs(folderId);
    }

    /**
     * 폴더 임포트를 백그라운드에서 시작하고 즉시 잡 스냅샷을 돌려준다.
     * 이미 실행 중이면 새로 시작하지 않고 진행 중인 잡을 돌려준다.
     */
    public ImportJobView startImport(String folderId, UUID spaceId, UUID memberId) {
        gateway(); // 미설정이면 여기서 바로 ValidationException — 잡을 만들지 않는다
        if (!running.compareAndSet(false, true)) {
            ImportJob existing = currentJob;
            return existing != null ? existing.view() : status();
        }
        ImportJob job = new ImportJob();
        currentJob = job;
        worker.submit(() -> {
            try {
                runImport(job, folderId, spaceId, memberId);
                job.status = JobStatus.DONE;
            } catch (RuntimeException exception) {
                job.status = JobStatus.FAILED;
                job.error = exception.getMessage();
                log.error("Google Drive 임포트 잡 실패", exception);
            } finally {
                running.set(false);
            }
        });
        return job.view();
    }

    /** 마지막(또는 진행 중) 잡의 상태. 잡이 없으면 빈 DONE 스냅샷. */
    public ImportJobView status() {
        ImportJob job = currentJob;
        if (job == null) {
            return new ImportJobView(null, JobStatus.DONE, 0, 0, 0, 0, 0, 0, "", null, null);
        }
        return job.view();
    }

    /** 동기 실행 — 프로덕션 경로(startImport)와 같은 워커 로직을 그대로 태운다. 테스트·운영 스크립트용. */
    public ImportJobView runImportSync(String folderId, UUID spaceId, UUID memberId) {
        ImportJob job = new ImportJob();
        currentJob = job;
        try {
            runImport(job, folderId, spaceId, memberId);
            job.status = JobStatus.DONE;
        } catch (RuntimeException exception) {
            job.status = JobStatus.FAILED;
            job.error = exception.getMessage();
        }
        return job.view();
    }

    /**
     * 이미 가져온 Drive 문서들(GoogleDriveIngestLog)을 다시 훑어 지식그래프에 연결한다.
     * Drive를 다시 호출하지 않고 이미 저장된 블록에서 원문을 재구성한다 — import 시점에
     * 추출이 실패했거나 아예 시도되지 않았던 문서를 나중에 수동으로 따라잡기 위한 버튼.
     * import와 같은 running 락/워커를 공유해 Gemini 호출이 동시에 겹치지 않게 한다.
     */
    public ImportJobView startKnowledgeSync() {
        if (!running.compareAndSet(false, true)) {
            ImportJob existing = currentJob;
            return existing != null ? existing.view() : status();
        }
        ImportJob job = new ImportJob();
        currentJob = job;
        worker.submit(() -> {
            try {
                runKnowledgeSync(job);
                job.status = JobStatus.DONE;
            } catch (RuntimeException exception) {
                job.status = JobStatus.FAILED;
                job.error = exception.getMessage();
                log.error("Google Drive 지식 동기화 잡 실패", exception);
            } finally {
                running.set(false);
            }
        });
        return job.view();
    }

    /** 동기 실행 — runImportSync와 같은 원칙(테스트·운영 스크립트용). */
    public ImportJobView runKnowledgeSyncNow() {
        ImportJob job = new ImportJob();
        currentJob = job;
        try {
            runKnowledgeSync(job);
            job.status = JobStatus.DONE;
        } catch (RuntimeException exception) {
            job.status = JobStatus.FAILED;
            job.error = exception.getMessage();
        }
        return job.view();
    }

    // ── 워커 본체 ──────────────────────────────────────────────────
    private void runImport(ImportJob job, String folderId, UUID spaceId, UUID memberId) {
        List<GoogleDriveGateway.DriveDoc> found = gateway().listGoogleDocs(folderId);
        job.found = found.size();
        for (GoogleDriveGateway.DriveDoc doc : found) {
            job.currentDoc = doc.name();
            try {
                importDoc(job, doc, spaceId, memberId);
            } catch (RuntimeException exception) {
                job.failed++;
                log.warn("Google Drive 문서 가져오기 실패: {} ({})", doc.name(), doc.fileId(), exception);
            }
            job.processed++;
        }
        job.currentDoc = "";
    }

    private void importDoc(ImportJob job, GoogleDriveGateway.DriveDoc doc, UUID spaceId, UUID memberId) {
        if (ingestLogs.findByDriveFileId(doc.fileId()).isPresent()) {
            job.skippedDuplicate++;
            return;
        }
        String text = gateway().exportText(doc.fileId());
        if (!StringUtils.hasText(text)) {
            throw new ValidationException("빈 문서예요: " + doc.name());
        }
        // 문서 생성 + 블록 + dedup 로그를 한 트랜잭션으로 — 중간 실패 시 고아 문서가 남지 않는다.
        UUID documentId = tx.execute(status -> {
            var document = documents.create(spaceId, doc.name(), memberId);
            documents.replaceBlocks(document.getId(), blocks(text, doc), memberId, ChangeCause.IMPORT);
            ingestLogs.save(new GoogleDriveIngestLog(doc.fileId(), document.getId()));
            return document.getId();
        });
        job.imported++;
        // LLM 호출은 트랜잭션 밖에서 (M2 rechunk와 같은 원칙). 실패해도 원본 임포트는 이미 커밋돼 있다.
        if (extractKnowledge(documentId, doc, text)) {
            job.documented++;
        }
        pace();
    }

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
        extract.ifPresent(value ->
                tx.executeWithoutResult(status -> tripleWriter.replace(documentId, value, "drive", doc.fileId())));
        return extract.isPresent();
    }

    // ── 지식 동기화 워커 (재추출 전용, Drive 재호출 없음) ──────────────────
    private void runKnowledgeSync(ImportJob job) {
        List<GoogleDriveIngestLog> logs = ingestLogs.findAll();
        job.found = logs.size();
        for (GoogleDriveIngestLog entry : logs) {
            job.currentDoc = entry.getDriveFileId();
            try {
                if (syncDocKnowledge(entry.getDocumentId(), entry.getDriveFileId())) {
                    job.documented++;
                } else {
                    job.skippedDuplicate++; // 이미 트리플이 있거나 본문이 비어 건너뜀
                }
            } catch (RuntimeException exception) {
                job.failed++;
                log.warn("Google Drive 지식 동기화 실패: {}", entry.getDriveFileId(), exception);
            }
            job.processed++;
        }
        job.currentDoc = "";
    }

    private boolean syncDocKnowledge(UUID documentId, String driveFileId) {
        if (tripleWriter.hasTriples(documentId)) {
            return false;
        }
        var document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ValidationException("문서를 찾을 수 없어요: " + documentId));
        String text = reconstructText(documentId);
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String truncated = text.length() > MAX_EXTRACT_CHARS ? text.substring(0, MAX_EXTRACT_CHARS) : text;
        List<SlackMessage> pseudoThread = List.of(new SlackMessage("drive", document.getTitle(), truncated, driveFileId));
        Optional<DecisionExtract> extract;
        try {
            extract = extractor.extract(pseudoThread);
        } catch (RuntimeException exception) {
            log.warn("Google Drive 지식 재추출 실패: {}", document.getTitle(), exception);
            return false;
        }
        extract.ifPresent(value ->
                tx.executeWithoutResult(status -> tripleWriter.replace(documentId, value, "drive", driveFileId)));
        pace();
        return extract.isPresent();
    }

    /** 저장된 블록(ProseMirror 스타일 JSON)에서 평문을 재구성한다 — Drive export를 다시 부르지 않는다. */
    private String reconstructText(UUID documentId) {
        return blockRepository.findByDocumentIdOrderByPosition(documentId).stream()
                .map(this::blockText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
    }

    private String blockText(Block block) {
        StringBuilder text = new StringBuilder();
        for (JsonNode node : block.getContent().path("content")) {
            text.append(node.path("text").asText(""));
        }
        return text.toString();
    }

    private void pace() {
        try {
            Thread.sleep(LLM_PACING_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private List<BlockPayload> blocks(String text, GoogleDriveGateway.DriveDoc doc) {
        List<BlockPayload> blocks = new ArrayList<>();
        String sourceUrl = "https://docs.google.com/document/d/" + doc.fileId();
        for (String paragraph : text.split("\n+")) {
            String trimmed = paragraph.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            if (blocks.size() >= MAX_BLOCK_PARAGRAPHS) {
                blocks.add(new BlockPayload(BlockType.PARAGRAPH,
                        paragraph("… (문서가 너무 길어 " + MAX_BLOCK_PARAGRAPHS + "문단에서 잘랐어요. 전체는 원본 링크에서 확인해 주세요.)"),
                        SourceType.IMPORT, sourceUrl, doc.fileId()));
                break;
            }
            blocks.add(new BlockPayload(BlockType.PARAGRAPH, paragraph(trimmed), SourceType.IMPORT, sourceUrl, doc.fileId()));
        }
        if (blocks.isEmpty()) {
            blocks.add(new BlockPayload(BlockType.PARAGRAPH, paragraph("(빈 문서)"), SourceType.IMPORT, sourceUrl, doc.fileId()));
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
