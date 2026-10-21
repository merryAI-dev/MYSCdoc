package com.mysc.mydoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.ai.*;
import com.mysc.mydoc.domain.*;
import com.mysc.mydoc.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClientException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeOnlySearchTest {
    private final AnthropicChatClient client = mock(AnthropicChatClient.class);
    private final ClaudeSearchReranker reranker = new ClaudeSearchReranker(
        new StaticListableBeanFactory(Map.of("claude", client)).getBeanProvider(AnthropicChatClient.class));
    private ChunkSearchRow row(UUID documentId, String text) {
        var row = mock(ChunkSearchRow.class);
        when(row.getId()).thenReturn(UUID.randomUUID()); when(row.getDocumentId()).thenReturn(documentId);
        when(row.getHeadingPath()).thenReturn("heading"); when(row.getText()).thenReturn(text); return row;
    }
    @Test void validatesPermutationAndFallsBackOnBadIdsOrApiFailure() {
        var rows = List.of(row(UUID.randomUUID(), "a"), row(UUID.randomUUID(), "b"));
        when(client.summarizeStructured(anyString(), anyString(), any())).thenReturn(
            "{\"ranked_ids\":[1,0]}", "{\"ranked_ids\":[1,1]}", "{\"ranked_ids\":[0,2]}",
            "{\"ranked_ids\":[0]}", "{\"ranked_ids\":[0,1.5]}", "not json")
            .thenThrow(new RestClientException("failure"));
        assertThat(reranker.rank("q", rows)).containsExactly(rows.get(1), rows.get(0));
        for (int i = 0; i < 6; i++) assertThat(reranker.rank("q", rows)).isEqualTo(rows);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_CLAUDE_LIVE_TEST", matches="true")
    void liveReranksCommitmentEvidence() {
        var api = new AnthropicChatClient(org.springframework.web.client.RestClient.builder(),
            System.getenv("ANTHROPIC_API_KEY"), "claude-haiku-5-5", 1024);
        var live = new ClaudeSearchReranker(new StaticListableBeanFactory(Map.of("claude", api)).getBeanProvider(AnthropicChatClient.class));
        var promotion = row(UUID.randomUUID(), "Company A is seeking pilot partners.");
        var meeting = row(UUID.randomUUID(), "Company A: No commercial commitment or pilot project was agreed in the meeting.");
        assertThat(live.rank("Did Company A secure a commercial commitment?", List.of(promotion, meeting)))
            .containsExactly(meeting, promotion);
    }
    @Test void noKeyAndEmptyCandidatesNeedNoApi() {
        var rows = List.of(row(UUID.randomUUID(), "a"), row(UUID.randomUUID(), "b"));
        var noKey = new ClaudeSearchReranker(new StaticListableBeanFactory().getBeanProvider(AnthropicChatClient.class));
        assertThat(noKey.rank("q", rows)).isEqualTo(rows);
        assertThat(reranker.rank("q", List.of())).isEmpty();
        verifyNoInteractions(client);
    }
    @Test void documentSearchWithoutEmbeddingUsesScopedCandidatesAndClaudeOrder() {
        var chunks = mock(ChunkRepository.class); var docs = mock(DocumentRepository.class);
        UUID space = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        var rows = List.of(row(first, "partner outreach"), row(second, "no commitment"));
        when(chunks.keywordHits("commitment", space)).thenReturn(rows);
        for (var id : List.of(first, second)) {
            var doc = mock(Document.class); when(doc.getTitle()).thenReturn("title"); when(docs.findById(id)).thenReturn(Optional.of(doc));
        }
        when(client.summarizeStructured(anyString(), anyString(), any())).thenReturn("{\"ranked_ids\":[1,0]}");
        var service = new SearchService(chunks, docs, new StaticListableBeanFactory().getBeanProvider(EmbeddingPort.class), reranker);
        var hits = service.hybridSearch("commitment", space, 2);
        assertThat(hits).extracting(SearchHit::documentId).containsExactly(second, first);
        verify(chunks).keywordHits("commitment", space); verifyNoMoreInteractions(chunks);
    }
    @Test void indexingWithoutEmbeddingSavesTextAndDoesNotDeleteUnchangedChunks() throws Exception {
        var docs = mock(DocumentService.class); var blocks = mock(BlockRepository.class); var chunks = mock(ChunkRepository.class);
        var tx = mock(PlatformTransactionManager.class); UUID id = UUID.randomUUID();
        var doc = mock(Document.class); when(doc.getStatus()).thenReturn(DocStatus.ACTIVE);
        when(doc.getTitle()).thenReturn("title"); when(doc.getUpdatedAt()).thenReturn(Instant.EPOCH);
        when(docs.getLocked(id)).thenReturn(doc);
        var block = mock(Block.class); when(block.getType()).thenReturn(BlockType.PARAGRAPH);
        when(block.getContent()).thenReturn(new ObjectMapper().readTree("{\"text\":\"searchable text\"}"));
        when(blocks.findByDocumentIdOrderByPosition(id)).thenReturn(List.of(block));
        when(chunks.findByDocumentIdOrderByCreatedAtAscIdAsc(id)).thenReturn(List.of());
        var service = new ChunkingService(docs, blocks, chunks, new StaticListableBeanFactory().getBeanProvider(EmbeddingPort.class), tx);
        assertThat(service.isEnabled()).isTrue(); service.rechunk(id);
        var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(chunks).saveAll(captor.capture());
        var saved = (Chunk)captor.getValue().iterator().next();
        assertThat(saved.getText()).isEqualTo("searchable text"); assertThat(saved.getEmbedding()).isNull();
        clearInvocations(chunks);
        when(chunks.findByDocumentIdOrderByCreatedAtAscIdAsc(id)).thenReturn(List.of(saved));
        service.rechunk(id); verify(chunks, never()).deleteByDocumentId(any()); verify(chunks, never()).saveAll(any());
    }
}
