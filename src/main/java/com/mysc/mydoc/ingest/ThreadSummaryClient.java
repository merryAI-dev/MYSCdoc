package com.mysc.mydoc.ingest;

import com.fasterxml.jackson.databind.JsonNode;

public interface ThreadSummaryClient {
    String summarize(String systemPrompt, String userPrompt);

    default String summarizeStructured(String systemPrompt, String userPrompt, JsonNode responseSchema) {
        return summarize(systemPrompt, userPrompt);
    }
}
