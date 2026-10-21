package com.mysc.mydoc.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysc.mydoc.ingest.ThreadSummaryClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.util.StringUtils;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Primary
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="mydoc.llm.provider", havingValue="gemini")
@ConditionalOnExpression("'${mydoc.gemini.api-key:}' != ''")
public class GoogleGenAiChatClient implements CorrectionClient, ThreadSummaryClient, KnowledgeAnswerClient {
    // The detected HTTP client (OkHttp via the Slack SDK) defaults to a 10s read timeout,
    // which a Gemini generateContent call routinely exceeds. Set explicit timeouts.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final RestClient restClient;
    private final String model;

    public GoogleGenAiChatClient(
            RestClient.Builder restClientBuilder,
            @Value("${mydoc.gemini.api-key}") String apiKey,
            @Value("${mydoc.gemini.chat-model}") String model
    ) {
        this.model = model;
        this.restClient = restClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .defaultHeader("x-goog-api-key", apiKey)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)))
                .build();
    }

    @Override
    public String review(String systemPrompt, String userPrompt) {
        GenerateContentRequest request = new GenerateContentRequest(
                new Content(List.of(new Part(systemPrompt))),
                List.of(new ContentEntry("user", List.of(new Part(userPrompt)))),
                // Correction/summary are structured-output tasks; thinking adds 10s+ latency and
                // token cost without helping here.
                new GenerationConfig(new ThinkingConfig(0))
        );
        GenerateContentResponse response = GeminiRetry.call("generateContent", () -> restClient.post()
                .uri("/models/{model}:generateContent", model)
                .body(request)
                .retrieve()
                .body(GenerateContentResponse.class));
        return extractText(response);
    }

    @Override
    public String answer(String systemPrompt, String userPrompt) {
        return review(systemPrompt, userPrompt);
    }

    @Override
    public String summarize(String systemPrompt, String userPrompt) {
        return review(systemPrompt, userPrompt);
    }

    @Override
    public String summarizeStructured(String systemPrompt, String userPrompt, JsonNode responseSchema) {
        StructuredGenerateContentRequest request = new StructuredGenerateContentRequest(
                new Content(List.of(new Part(systemPrompt))),
                List.of(new ContentEntry("user", List.of(new Part(userPrompt)))),
                new StructuredGenerationConfig(new ThinkingConfig(0), "application/json", responseSchema)
        );
        GenerateContentResponse response = GeminiRetry.call("generateContent", () -> restClient.post()
                .uri("/models/{model}:generateContent", model)
                .body(request)
                .retrieve()
                .body(GenerateContentResponse.class));
        return extractText(response);
    }

    static String extractText(GenerateContentResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new RestClientException("Gemini returned no answer candidate");
        }
        Candidate candidate = response.candidates().get(0);
        if (candidate == null || candidate.content() == null || candidate.content().parts() == null) {
            throw new RestClientException("Gemini returned no answer content");
        }
        String text = candidate.content().parts().stream()
                .filter(Objects::nonNull)
                .filter(part -> !Boolean.TRUE.equals(part.thought()))
                .map(Part::text).filter(Objects::nonNull).collect(Collectors.joining()).strip();
        if (!StringUtils.hasText(text)) {
            throw new RestClientException("Gemini returned an empty answer");
        }
        return text;
    }

    private record GenerateContentRequest(Content systemInstruction, List<ContentEntry> contents, GenerationConfig generationConfig) {}
    private record StructuredGenerateContentRequest(Content systemInstruction, List<ContentEntry> contents,
                                                    StructuredGenerationConfig generationConfig) {}
    private record GenerationConfig(ThinkingConfig thinkingConfig) {}
    private record StructuredGenerationConfig(ThinkingConfig thinkingConfig, String responseMimeType,
                                              JsonNode responseSchema) {}
    private record ThinkingConfig(int thinkingBudget) {}
    private record ContentEntry(String role, List<Part> parts) {}
    record Content(List<Part> parts) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record Part(String text, Boolean thought) {
        Part(String text) { this(text, null); }
    }
    record GenerateContentResponse(List<Candidate> candidates) {}
    record Candidate(Content content) {}
}
