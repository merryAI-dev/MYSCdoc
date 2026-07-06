package com.mysc.mydoc.ai;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression("'${mydoc.gemini.api-key:}' != ''")
public class GoogleGenAiChatClient implements CorrectionClient, ThreadSummaryClient {
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
        GenerateContentResponse response = restClient.post()
                .uri("/models/{model}:generateContent", model)
                .body(request)
                .retrieve()
                .body(GenerateContentResponse.class);
        return response.candidates().get(0).content().parts().get(0).text();
    }

    @Override
    public String summarize(String systemPrompt, String userPrompt) {
        return review(systemPrompt, userPrompt);
    }

    private record GenerateContentRequest(Content systemInstruction, List<ContentEntry> contents, GenerationConfig generationConfig) {}
    private record GenerationConfig(ThinkingConfig thinkingConfig) {}
    private record ThinkingConfig(int thinkingBudget) {}
    private record ContentEntry(String role, List<Part> parts) {}
    private record Content(List<Part> parts) {}
    private record Part(String text) {}
    private record GenerateContentResponse(List<Candidate> candidates) {}
    private record Candidate(Content content) {}
}
