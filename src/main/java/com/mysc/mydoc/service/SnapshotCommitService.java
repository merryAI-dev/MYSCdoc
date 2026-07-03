package com.mysc.mydoc.service;

import com.mysc.mydoc.api.dto.ApiDtos.BlockRequest;
import com.mysc.mydoc.domain.ChangeCause;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SnapshotCommitService {
    private final DocumentService documents;

    public SnapshotCommitService(DocumentService documents) {
        this.documents = documents;
    }

    public void commit(UUID documentId, UUID editorId, List<BlockRequest> blocks) {
        List<BlockPayload> payloads = blocks.stream()
                .map(block -> new BlockPayload(block.type(), block.content(), block.sourceType(), block.sourceUrl(), block.sourceRef()))
                .toList();
        documents.replaceBlocks(documentId, payloads, editorId, ChangeCause.SNAPSHOT_COMMIT);
    }
}
