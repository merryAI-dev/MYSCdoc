package com.mysc.mydoc.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DocumentChangedListener {
    private final ChunkingService chunkingService;

    public DocumentChangedListener(ChunkingService chunkingService) {
        this.chunkingService = chunkingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentChanged(DocumentChangedEvent event) {
        chunkingService.rechunk(event.docId());
    }
}
