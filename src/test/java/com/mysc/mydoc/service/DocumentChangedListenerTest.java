package com.mysc.mydoc.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentChangedListenerTest {
    @Test
    void onDocumentChanged_coalescesRapidEventsForSameDocument() {
        ChunkingService chunkingService = mock(ChunkingService.class);
        when(chunkingService.isEnabled()).thenReturn(true);
        DocumentChangedListener listener = new DocumentChangedListener(chunkingService);
        UUID documentId = UUID.randomUUID();

        listener.onDocumentChanged(new DocumentChangedEvent(documentId));
        listener.onDocumentChanged(new DocumentChangedEvent(documentId));

        verify(chunkingService, timeout(1000).times(1)).rechunk(documentId);
        verify(chunkingService, times(1)).rechunk(documentId);
    }
}
