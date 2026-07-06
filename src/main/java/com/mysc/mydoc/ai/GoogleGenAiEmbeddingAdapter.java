package com.mysc.mydoc.ai;

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
public class GoogleGenAiEmbeddingAdapter implements EmbeddingPort {
    // Same reasoning as GoogleGenAiChatClient: OkHttp's default 10s read timeout is too tight
    // for batch-embedding a whole document.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final RestClient restClient;
    private final String model;
    private final int dimensions;

    public GoogleGenAiEmbeddingAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${mydoc.gemini.api-key}") String apiKey,
            @Value("${mydoc.gemini.embedding-model}") String model,
            @Value("${mydoc.gemini.embedding-dimensions}") int dimensions
    ) {
        this.model = model;
        this.dimensions = dimensions;
        this.restClient = restClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .defaultHeader("x-goog-api-key", apiKey)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)))
                .build();
    }

    @Override
    public float[] embed(String text) {
        EmbedContentResponse response = restClient.post()
                .uri("/models/{model}:embedContent", model)
                .body(new EmbedContentRequest(new Content(List.of(new Part(text))), new EmbedConfig(dimensions)))
                .retrieve()
                .body(EmbedContentResponse.class);
        return toFloatArray(response.embedding().values());
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<BatchEmbedRequestItem> requests = texts.stream()
                .map(text -> new BatchEmbedRequestItem("models/" + model, new Content(List.of(new Part(text))), new EmbedConfig(dimensions)))
                .toList();
        BatchEmbedContentsResponse response = restClient.post()
                .uri("/models/{model}:batchEmbedContents", model)
                .body(new BatchEmbedContentsRequest(requests))
                .retrieve()
                .body(BatchEmbedContentsResponse.class);
        return response.embeddings().stream().map(embedding -> toFloatArray(embedding.values())).toList();
    }

    private float[] toFloatArray(List<Double> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }

    private record EmbedContentRequest(Content content, EmbedConfig embedContentConfig) {}
    private record BatchEmbedContentsRequest(List<BatchEmbedRequestItem> requests) {}
    private record BatchEmbedRequestItem(String model, Content content, EmbedConfig embedContentConfig) {}
    private record EmbedConfig(int outputDimensionality) {}
    private record Content(List<Part> parts) {}
    private record Part(String text) {}
    private record EmbedContentResponse(Embedding embedding) {}
    private record BatchEmbedContentsResponse(List<Embedding> embeddings) {}
    private record Embedding(List<Double> values) {}
}
