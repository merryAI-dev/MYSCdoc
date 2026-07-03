package com.mysc.mydoc.service;

import com.mysc.mydoc.api.dto.ApiDtos.BlockRequest;
import com.mysc.mydoc.common.NotFoundException;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.ingest.SystemMemberInitializer;
import com.mysc.mydoc.repository.MemberRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SnapshotCommitService {
    private final DocumentService documents;
    private final MemberRepository members;

    public SnapshotCommitService(DocumentService documents, MemberRepository members) {
        this.documents = documents;
        this.members = members;
    }

    public void commit(UUID documentId, UUID editorId, List<BlockRequest> blocks) {
        if (documentId == null) {
            throw new ValidationException("documentId is required");
        }
        if (blocks == null) {
            throw new ValidationException("blocks are required");
        }
        UUID resolvedEditorId = editorId == null ? systemMemberId() : editorId;
        List<BlockPayload> payloads = blocks.stream()
                .map(block -> new BlockPayload(block.type(), block.content(), block.sourceType(), block.sourceUrl(), block.sourceRef()))
                .toList();
        documents.replaceBlocks(documentId, payloads, resolvedEditorId, ChangeCause.SNAPSHOT_COMMIT);
    }

    private UUID systemMemberId() {
        return members.findByEmail(SystemMemberInitializer.SYSTEM_MEMBER_EMAIL)
                .orElseThrow(() -> new NotFoundException("system member not found"))
                .getId();
    }
}
