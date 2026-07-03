package com.mysc.mydoc.api;

import static com.mysc.mydoc.api.dto.ApiDtos.BlockRequest;
import static com.mysc.mydoc.api.dto.ApiDtos.CollabTokenRequest;
import static com.mysc.mydoc.api.dto.ApiDtos.CollabTokenResponse;
import static com.mysc.mydoc.api.dto.ApiDtos.SnapshotRequest;

import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.domain.Block;
import com.mysc.mydoc.repository.BlockRepository;
import com.mysc.mydoc.service.CollabTokenService;
import com.mysc.mydoc.service.DocumentService;
import com.mysc.mydoc.service.SnapshotCommitService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalController {
    private final CollabTokenService collabTokens;
    private final SnapshotCommitService snapshots;
    private final DocumentService documents;
    private final BlockRepository blocks;

    public InternalController(
            CollabTokenService collabTokens,
            SnapshotCommitService snapshots,
            DocumentService documents,
            BlockRepository blocks
    ) {
        this.collabTokens = collabTokens;
        this.snapshots = snapshots;
        this.documents = documents;
        this.blocks = blocks;
    }

    @PostMapping("/api/internal/collab-tokens")
    CollabTokenResponse collabToken(
            @RequestBody CollabTokenRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        if (request == null) {
            throw new ValidationException("collab token request is required");
        }
        return new CollabTokenResponse(
                collabTokens.issue(request.documentId(), memberId),
                CollabTokenService.EXPIRES_IN_SECONDS
        );
    }

    @PostMapping("/api/internal/snapshots")
    ResponseEntity<Void> snapshot(@RequestBody SnapshotRequest request) {
        if (request == null) {
            throw new ValidationException("snapshot request is required");
        }
        snapshots.commit(request.documentId(), request.editorId(), request.blocks());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/internal/documents/{id}/blocks")
    List<BlockRequest> blocks(@PathVariable UUID id) {
        documents.get(id);
        return blocks.findByDocumentIdOrderByPosition(id).stream().map(InternalController::toBlockRequest).toList();
    }

    private static BlockRequest toBlockRequest(Block block) {
        return new BlockRequest(
                block.getType(),
                block.getContent(),
                block.getProvenance().getSourceType(),
                block.getProvenance().getSourceUrl(),
                block.getProvenance().getSourceRef()
        );
    }
}
