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
import org.springframework.beans.factory.ObjectProvider;

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

        KnowledgeChatService service = new KnowledgeChatService(knowledge, provider(client), rewriter);
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
    void answer_marksIndirectEvidenceWithItsPath() {
        KnowledgeGraphService knowledge = mock();
        KnowledgeAnswerClient client = mock();
        QueryRewriteClient rewriter = mock();
        UUID seedId = UUID.randomUUID();
        ScoredTriple seed = new ScoredTriple(seedId, UUID.randomUUID(), "decision", "선정됐어요",
                "오션랩", "선정되었다", "해양수산 데모데이", Instant.now(), null, 1.0, 0, null);
        ScoredTriple neighbor = new ScoredTriple(UUID.randomUUID(), UUID.randomUUID(), "convention",
                "시드투자를 검토해요", "해양수산 데모데이", "후속지원", "시드투자 검토",
                Instant.now(), null, 0.2, 1, seedId);
        when(knowledge.searchContext("오션랩 후속지원", 6, 12, RetrievalMode.MULTIHOP))
                .thenReturn(List.of(seed, neighbor));
        when(client.answer(anyString(), anyString())).thenReturn("시드투자를 검토해요. (근거: C1→C2)");

        KnowledgeChatService service = new KnowledgeChatService(knowledge, provider(client), rewriter);
        KnowledgeChatService.ChatAnswer result = service.answer("오션랩 후속지원", false, RetrievalMode.MULTIHOP);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).answer(anyString(), user.capture());
        assertThat(user.getValue())
                .contains("[C1] [상태=확정] [decision]")
                .contains("[C2] [상태=미분류] [경로=C1→] [convention]");
        assertThat(result.sources()).extracting(KnowledgeChatService.ChatSource::hop).containsExactly(0, 1);
    }

    @Test
    void answer_usesRequestedGraphRetrievalMode() {
        KnowledgeGraphService knowledge = mock();
        KnowledgeAnswerClient client = mock();
        QueryRewriteClient rewriter = mock();
        when(knowledge.searchContext("해양수산 데모데이", 6, 12, RetrievalMode.GRAPH))
                .thenReturn(List.of());

        KnowledgeChatService service = new KnowledgeChatService(knowledge, provider(client), rewriter);
        KnowledgeChatService.ChatAnswer result = service.answer(
                "해양수산 데모데이", false, RetrievalMode.GRAPH);

        verify(knowledge).searchContext("해양수산 데모데이", 6, 12, RetrievalMode.GRAPH);
        assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.GRAPH);
    }

    @Test
    void answer_explainsMissingModelInsteadOfFailing() {
        KnowledgeGraphService knowledge = mock();
        QueryRewriteClient rewriter = mock();
        ScoredTriple fact = new ScoredTriple(UUID.randomUUID(), UUID.randomUUID(), "convention", "s",
                "해양수산 데모데이", "포함한다", "통합 데모데이", Instant.now(), null, 1.0);
        when(knowledge.searchContext("해양수산 데모데이", 6, 12, RetrievalMode.BM25)).thenReturn(List.of(fact));

        KnowledgeChatService service = new KnowledgeChatService(knowledge, provider(null), rewriter);

        assertThat(service.answer("해양수산 데모데이").answer()).contains("답변 모델이 설정되지 않았어요");
    }

    private static ObjectProvider<KnowledgeAnswerClient> provider(KnowledgeAnswerClient client) {
        ObjectProvider<KnowledgeAnswerClient> provider = mock();
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}
