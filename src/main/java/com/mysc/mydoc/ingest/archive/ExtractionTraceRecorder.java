package com.mysc.mydoc.ingest.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교사 호출의 (입력 원문 → 출력 JSON) 쌍을 적재한다 — 나중의 학습셋.
 *
 * 왜: 제품 추출은 지금 Gemini가 하지만 학생 모델 증류로 옮겨가는 중이다. 매 호출이 곧
 * 교사 데이터인데 지금은 파싱하고 버려서, 학습셋을 만들려면 Gemini를 다시 호출해야 한다.
 * 흘려보내지 않고 남긴다. 실패 건도 남긴다 — 형식 실패 분석이 곧 개선 재료다.
 *
 * 기록 실패가 추출을 막아서는 안 되므로 예외를 삼키고, 별도 트랜잭션으로 커밋한다
 * (추출이 롤백돼도 그때 교사가 뭘 뱉었는지는 남아야 원인을 볼 수 있다).
 */
@Component
public class ExtractionTraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(ExtractionTraceRecorder.class);
    /** 프롬프트가 개정되면 학습셋을 갈라야 하므로 해시로 버전을 남긴다. */
    private static final int MAX_INPUT_CHARS = 60_000;

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String model;

    public ExtractionTraceRecorder(JdbcTemplate jdbc,
                                   @Value("${mydoc.extraction-trace.enabled:true}") boolean enabled,
                                   @Value("${mydoc.gemini.chat-model:unknown}") String model) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.model = model;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String sourceKind, String channelId, String threadTs,
                       String systemPrompt, String inputText, String outputJson,
                       boolean ok, String error) {
        if (!enabled) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO extraction_trace
                      (id, source_kind, channel_id, thread_ts, document_id, model,
                       prompt_hash, input_text, output_json, ok, error, created_at)
                    VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), sourceKind, channelId, threadTs, model,
                    sha256(systemPrompt),
                    truncate(inputText, MAX_INPUT_CHARS),
                    outputJson == null ? null : truncate(outputJson, MAX_INPUT_CHARS),
                    ok, truncate(error, 500), Timestamp.from(Instant.now()));
        } catch (Exception failure) {
            log.warn("추출 로그 적재 실패(추출은 계속) — {}", failure.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            return "unknown";
        }
    }
}
