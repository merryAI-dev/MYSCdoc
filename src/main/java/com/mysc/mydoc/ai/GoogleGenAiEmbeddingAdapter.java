package com.mysc.mydoc.ai;

import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(EmbeddingModel.class)
public class OpenAiEmbeddingAdapter implements EmbeddingPort {
    private final EmbeddingModel embeddingModel;

    public OpenAiEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
