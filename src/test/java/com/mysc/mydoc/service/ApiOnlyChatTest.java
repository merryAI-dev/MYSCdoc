package com.mysc.mydoc.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

import com.mysc.mydoc.ai.GoogleGenAiChatClient;
import com.mysc.mydoc.ai.KnowledgeAnswerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.Map;

class ApiOnlyChatTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(KnowledgeGraphService.class, () -> mock(KnowledgeGraphService.class))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(KnowledgeChatService.class, QueryRewriteClient.class,
                    GoogleGenAiChatClient.class, com.mysc.mydoc.ai.AnthropicChatClient.class);

    @Test
    void chatContextStartsWithoutKeyAndDoesNotSearchOrRewrite() {
        runner.withPropertyValues("mydoc.gemini.api-key=").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeAnswerClient.class);
            var result = context.getBean(KnowledgeChatService.class).answer("업무 질문", true);
            assertThat(result.answer()).contains("API 키 설정");
            assertThat(result.sources()).isEmpty();
            verifyNoInteractions(context.getBean(KnowledgeGraphService.class));
        });
    }

    @Test
    void apiKeySelectsGeminiClientWithoutLocalModel() {
        runner.withPropertyValues("mydoc.llm.provider=gemini", "mydoc.gemini.api-key=test-only", "mydoc.gemini.chat-model=test-model")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KnowledgeAnswerClient.class));
    }

    @Test
    void bothKeysSelectOnlyClaudeByDefault() {
        runner.withPropertyValues("mydoc.anthropic.api-key=test-only", "mydoc.gemini.api-key=test-only")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(KnowledgeAnswerClient.class);
                assertThat(context.getBean(KnowledgeAnswerClient.class)).isInstanceOf(com.mysc.mydoc.ai.AnthropicChatClient.class);
            });
    }

    @Test
    void geminiKeyDoesNotSilentlyReplaceMissingClaudeKey() {
        runner.withPropertyValues("mydoc.gemini.api-key=test-only")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeAnswerClient.class));
    }

    @Test
    void rewriterUsesApiAndFallsBackOnFailureOrInvalidOutput() {
        KnowledgeAnswerClient client = mock();
        var rewriter = new QueryRewriteClient(new StaticListableBeanFactory(Map.of("api", client))
                .getBeanProvider(KnowledgeAnswerClient.class));
        when(client.answer(anyString(), anyString())).thenReturn(" 데모데이 일정 ", "설명\n검색어", " ", "x")
                .thenThrow(new RestClientException("upstream failure"));
        assertThat(rewriter.rewrite("데모데이가 언제인가요?")).contains("데모데이 일정");
        for (int i = 0; i < 4; i++) assertThat(rewriter.rewrite("질문")).isEmpty();
    }
}
