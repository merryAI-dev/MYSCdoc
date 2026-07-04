package com.mysc.mydoc.ai;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression("'${mydoc.gemini.api-key:}' != ''")
public class GoogleGenAiChatClient implements CorrectionClient, ThreadSummaryClient {
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
                .build();
    }

    @Override
    public String review(String systemPrompt, String userPrompt) {
        GenerateContentRequest request = new GenerateContentRequest(
                new Content(List.of(new Part(systemPrompt))),
                List.of(new ContentEntry("user", List.of(new Part(userPrompt))))
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

    private record GenerateContentRequest(Content systemInstruction, List<ContentEntry> contents) {}
    private record ContentEntry(String role, List<Part> parts) {}
    private record Content(List<Part> parts) {}
    private record Part(String text) {}
    private record GenerateContentResponse(List<Candidate> candidates) {}
    private record Candidate(Content content) {}
}
