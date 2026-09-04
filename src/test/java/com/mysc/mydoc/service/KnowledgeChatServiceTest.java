package com.mysc.mydoc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mysc.mydoc.ai.KnowledgeAnswerClient;
import com.mysc.mydoc.service.KnowledgeGraphService.RetrievalMode;
import com.mysc.mydoc.service.KnowledgeGraphService.ScoredTriple;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeChatServiceTest {

    @Test
    void answer_usesTrainingPromptAndRemovesInternalTags() {
        KnowledgeGraphService knowledge = mock();
        KnowledgeAnswerClient client = mock();
        QueryRewriteClient rewriter = mock();
        ScoredTriple fact = new ScoredTriple(UUID.randomUUID(), UUID.randomUUID(), "convention",
                "운영사 데모데이 선발 팀이 통합 데모데이에 참여해요.",
                "해양수산 데모데이", "포함한다", "통합 데모데이",
                Instant.now(), null, 43.5);
        when(knowledge.searchContext("해양수산 데모데이", 6, 12, RetrievalMode.BM25))
                .thenReturn(List.of(fact));
        when(client.answer(anyString(), anyString())).thenReturn(
                "<answer>\n운영사 데모데이와 통합 데모데이로 나뉘어요. (근거: C1)\n<answer>");

        KnowledgeChatService service = new KnowledgeChatService(knowledge, client, rewriter);
        KnowledgeChatService.ChatAnswer result = service.answer("해양수산 데모데이");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).answer(system.capture(), user.capture());
        assertThat(system.getValue()).contains("사내 업무 기록").doesNotContain("<answer>");
        assertThat(user.getValue())
                .startsWith("업무 기록:\n[C1]")
                .contains("해양수산 데모데이 — 포함한다 — 통합 데모데이")
                .endsWith("질문: 해양수산 데모데이");
        assertThat(result.answer()).isEqualTo("운영사 데모데이와 통합 데모데이로 나뉘어요. (근거: C1)");
        assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.BM25);
    }

    @Test
    void answer_usesRequestedGraphRetrievalMode() {
        KnowledgeGraphService knowledge = mock();
        KnowledgeAnswerClient client = mock();
        QueryRewriteClient rewriter = mock();
        when(knowledge.searchContext("해양수산 데모데이", 6, 12, RetrievalMode.GRAPH))
                .thenReturn(List.of());

        KnowledgeChatService service = new KnowledgeChatService(knowledge, client, rewriter);
        KnowledgeChatService.ChatAnswer result = service.answer(
                "해양수산 데모데이", false, RetrievalMode.GRAPH);

        verify(knowledge).searchContext("해양수산 데모데이", 6, 12, RetrievalMode.GRAPH);
        assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.GRAPH);
    }
}
