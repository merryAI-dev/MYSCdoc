package com.mysc.mydoc.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DocumentChangedListener {
    private final ChunkingService chunkingService;
    private final Duration debounce;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile boolean running = true;

    @Autowired
    public DocumentChangedListener(
            ChunkingService chunkingService,
            @Value("${mydoc.rechunk.debounce:PT30S}") Duration debounce
    ) {
        this.chunkingService = chunkingService;
        this.debounce = debounce;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mydoc-rechunk-debounce");
            thread.setDaemon(true);
            return thread;
        });
    }

    DocumentChangedListener(ChunkingService chunkingService) {
        this(chunkingService, Duration.ofMillis(50));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentChanged(DocumentChangedEvent event) {
        if (!running || !chunkingService.isEnabled()) {
            return;
        }
        UUID docId = event.docId();
        long delayMillis = Math.max(1, debounce.toMillis());
        ScheduledFuture<?>[] scheduled = new ScheduledFuture<?>[1];
        scheduled[0] = executor.schedule(() -> {
            try {
                if (running) {
                    chunkingService.rechunk(docId);
                }
            } finally {
                pending.remove(docId, scheduled[0]);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = pending.put(docId, scheduled[0]);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    @EventListener(ContextClosedEvent.class)
    void onContextClosed() {
        shutdownScheduler();
    }

    @PreDestroy
    void stop() {
        shutdownScheduler();
    }

    private void shutdownScheduler() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        running = false;
        pending.values().forEach(future -> future.cancel(false));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
