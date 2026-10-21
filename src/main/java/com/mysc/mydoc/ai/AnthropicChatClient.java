package com.mysc.mydoc.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysc.mydoc.ingest.ThreadSummaryClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.web.client.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
@ConditionalOnProperty(name = "mydoc.llm.provider", havingValue = "anthropic", matchIfMissing = true)
@ConditionalOnExpression("'${mydoc.anthropic.api-key:}' != ''")
public class AnthropicChatClient implements CorrectionClient, ThreadSummaryClient, KnowledgeAnswerClient {
    private final RestClient client;
    private final String model;
    private final int maxTokens;

    public AnthropicChatClient(RestClient.Builder builder,
            @Value("${mydoc.anthropic.api-key}") String key,
            @Value("${mydoc.anthropic.chat-model:claude-haiku-5-5}") String model,
            @Value("${mydoc.anthropic.max-tokens:8192}") int maxTokens) {
        this.model = model;
        if (maxTokens <= 0) throw new IllegalArgumentException("max-tokens must be positive");
        this.maxTokens = maxTokens;
        this.client = builder.baseUrl("https://api.anthropic.com/v1")
                .defaultHeader("x-api-key", key).defaultHeader("anthropic-version", "2023-06-01")
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(Duration.ofSeconds(10)).withReadTimeout(Duration.ofSeconds(120))))
                .build();
    }

    @Override public String answer(String system, String user) { return review(system, user); }
    @Override public String summarize(String system, String user) { return review(system, user); }
    @Override public String review(String system, String user) { return invoke(system, user, null); }
    @Override public String summarizeStructured(String system, String user, JsonNode schema) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException("Object schema required");
        return invoke(system, user, schema);
    }

    private String invoke(String system, String user, JsonNode schema) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("max_tokens", maxTokens);
        request.put("thinking", Map.of("type", "disabled"));
        request.put("system", system);
        request.put("messages", List.of(Map.of("role", "user", "content", user)));
        if (schema != null) {
            request.put("tools", List.of(Map.of("name", "emit_result", "description", "Return the extracted result", "input_schema", schema)));
            request.put("tool_choice", Map.of("type", "tool", "name", "emit_result", "disable_parallel_tool_use", true));
        }
        JsonNode response;
        try {
            response = client.post().uri("/messages").body(request).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            // Do not propagate provider response bodies into downstream logs or user messages.
            throw new RestClientException("Claude request failed: HTTP " + ex.getStatusCode().value());
        }
        return extract(response, schema != null);
    }

    static String extract(JsonNode response, boolean structured) {
        if (response == null) throw new RestClientException("Claude returned no response");
        String stop = response.path("stop_reason").asText();
        if (!(structured ? "tool_use" : "end_turn").equals(stop))
            throw new RestClientException("Claude response incomplete or unexpected stop reason");
        StringBuilder text = new StringBuilder();
        JsonNode result = null;
        for (JsonNode block : response.path("content")) {
            if (structured && "tool_use".equals(block.path("type").asText()) && "emit_result".equals(block.path("name").asText())) {
                if (result != null || !block.path("input").isObject()) throw new RestClientException("Invalid Claude structured result");
                result = block.get("input");
            } else if (!structured && "text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        }
        if (structured && result != null) return result.toString();
        if (!structured && !text.toString().isBlank()) return text.toString().strip();
        throw new RestClientException("Claude returned no usable content");
    }
}
