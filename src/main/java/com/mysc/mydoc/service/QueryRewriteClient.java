package com.mysc.mydoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * RL로 학습한 로컬 1.2B 쿼리 재작성기 클라이언트 (mlx_lm.server, OpenAI 호환).
 *
 * 왜: 사용자는 노드 라벨의 어휘로 묻지 않는다. held-out 실측에서 재작성이 검색 hit@1을
 * 17.8%→26.8%로 올렸다(무학습 재작성은 오히려 31.8%로 악화 — 반드시 학습된 모델이어야 함).
 *
 * 프롬프트는 RL 학습 시와 글자 그대로 동일해야 한다 — 분포가 어긋나면 학습 효과가 사라진다.
 * 서버가 죽어 있으면 조용히 빈 값을 돌려주고 호출측이 원 질문으로 검색한다(가용성 우선).
 */
@Service
public class QueryRewriteClient {
    private static final Logger log = LoggerFactory.getLogger(QueryRewriteClient.class);
    /** RL 학습(grpo_train_search.py)과 동일한 문자열 — 바꾸면 학습 분포가 깨진다. */
    private static final String SYSTEM = "사내 회의록 지식그래프를 BM25로 검색한다. 질문을 검색에 효과적인 "
            + "핵심 키워드 검색어 한 줄로 바꿔라. 검색어만 출력하고 다른 말은 하지 마라.";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String modelId;

    public QueryRewriteClient(
            @Value("${mydoc.rewriter.base-url:http://localhost:8090}") String baseUrl,
            // mlx_lm.server는 model 필드를 로드 경로로 취급한다 — 서버가 띄운 경로와 같아야 한다.
            @Value("${mydoc.rewriter.model:/Users/boram/mydoc/research/mlx/rl-rewriter-q8}") String modelId) {
        this.baseUrl = baseUrl;
        this.modelId = modelId;
    }

    public Optional<String> rewrite(String question) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(question)) {
            return Optional.empty();
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", modelId,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM),
                            Map.of("role", "user", "content", question)),
                    "max_tokens", 96,
                    "temperature", 0));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("재작성 서버 응답 {} — 원 질문으로 검색", response.statusCode());
                return Optional.empty();
            }
            JsonNode content = mapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
            // RL 형식 게이트와 같은 기준: 한 줄, 2~80자만 신뢰. 벗어나면 버린다.
            String query = content.asText("").strip().split("\n")[0].strip();
            query = query.replaceAll("^[\"'“”]+|[\"'“”]+$", "").strip();
            if (query.length() < 2 || query.length() > 80) {
                return Optional.empty();
            }
            return Optional.of(query);
        } catch (Exception failure) {
            log.warn("재작성 호출 실패({}) — 원 질문으로 검색", failure.getMessage());
            return Optional.empty();
        }
    }
}
