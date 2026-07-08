package com.mysc.mydoc.ai;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Gemini 호출 재시도 — 429(쿼터)와 5xx만 지수 백오프+jitter로 재시도한다.
 * 타임아웃(ResourceAccessException)은 재시도하지 않는다: read timeout이 120s라
 * 요청 경로에서 재시도하면 지연이 배가되고, 타임아웃은 대개 재시도로 낫지 않는다.
 */
final class GeminiRetry {
    private static final Logger log = LoggerFactory.getLogger(GeminiRetry.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;

    private GeminiRetry() {}

    static <T> T call(String operation, Supplier<T> request) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return request.get();
            } catch (HttpClientErrorException.TooManyRequests | HttpServerErrorException exception) {
                lastFailure = exception;
                if (attempt < MAX_ATTEMPTS) {
                    long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1))
                            + ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MS);
                    log.warn("Gemini {} failed (attempt {}/{}), retrying in {}ms: {}",
                            operation, attempt, MAX_ATTEMPTS, backoff, exception.getStatusCode());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                }
            }
        }
        throw lastFailure;
    }
}
