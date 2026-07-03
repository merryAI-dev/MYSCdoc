package com.mysc.mydoc.ai;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(ChatModel.class)
public class AnthropicCorrectionClient implements CorrectionClient, ThreadSummaryClient {
    private final ChatClient chatClient;

    public AnthropicCorrectionClient(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String review(String systemPrompt, String userPrompt) {
        return chatClient.prompt().system(systemPrompt).user(userPrompt).call().content();
    }

    @Override
    public String summarize(String systemPrompt, String userPrompt) {
        return review(systemPrompt, userPrompt);
    }
}
