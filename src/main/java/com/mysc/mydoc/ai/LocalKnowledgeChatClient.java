package com.mysc.mydoc.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

/** MYSCdoc 지식그래프 챗 전용 로컬 MLX 클라이언트. */
@Component
public class LocalKnowledgeChatClient implements KnowledgeAnswerClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;

    public LocalKnowledgeChatClient(
            ObjectMapper mapper,
            @Value("${mydoc.local-chat.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${mydoc.local-chat.model:/Users/boram/mydoc/research/mlx/h10c-s43-merged}") String model
    ) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String answer(String systemPrompt, String userPrompt) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)),
                        "max_tokens", 128,
                        "temperature", 0,
                        "chat_template_kwargs", Map.of("enable_thinking", false)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(READ_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RestClientException("local MLX server returned " + response.statusCode());
            }
            JsonNode json = mapper.readTree(response.body());
            String text = json.path("choices").path(0).path("message").path("content").asText("").strip();
            if (!StringUtils.hasText(text)) {
                throw new RestClientException("local MLX server returned an empty answer");
            }
            return text;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RestClientException("local MLX request interrupted", interrupted);
        } catch (Exception failure) {
            if (failure instanceof RestClientException restClientException) {
                throw restClientException;
            }
            throw new RestClientException("local MLX request failed", failure);
        }
    }
}
