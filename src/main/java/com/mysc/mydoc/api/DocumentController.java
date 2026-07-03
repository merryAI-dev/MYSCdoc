package com.mysc.mydoc.api;

import static com.mysc.mydoc.api.dto.ApiDtos.BlockResponse;
import static com.mysc.mydoc.api.dto.ApiDtos.BlocksRequest;
import static com.mysc.mydoc.api.dto.ApiDtos.DocumentCreateRequest;
import static com.mysc.mydoc.api.dto.ApiDtos.DocumentResponse;
import static com.mysc.mydoc.api.dto.ApiDtos.OwnerRequest;
import static com.mysc.mydoc.api.dto.ApiDtos.OwnerResponse;
import static com.mysc.mydoc.api.dto.ApiDtos.RevisionDetail;
import static com.mysc.mydoc.api.dto.ApiDtos.RevisionSummary;
import static com.mysc.mydoc.api.dto.ApiDtos.TitleRequest;

import com.mysc.mydoc.common.NotFoundException;
import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.domain.Block;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.Document;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.Revision;
import com.mysc.mydoc.repository.BlockRepository;
import com.mysc.mydoc.repository.MemberRepository;
import com.mysc.mydoc.repository.RevisionRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentController {
    private final DocumentService documents;
    private final BlockRepository blocks;
    private final RevisionRepository revisions;
    private final MemberRepository members;

    public DocumentController(
            DocumentService documents,
            BlockRepository blocks,
            RevisionRepository revisions,
            MemberRepository members
    ) {
        this.documents = documents;
        this.blocks = blocks;
        this.revisions = revisions;
        this.members = members;
    }

    @PostMapping("/api/documents")
    ResponseEntity<DocumentResponse> create(
            @RequestBody DocumentCreateRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        Document document = documents.create(request.spaceId(), request.title(), memberId);
        return ResponseEntity.created(URI.create("/api/documents/" + document.getId())).body(toResponse(document));
    }

    @GetMapping("/api/documents/{id}")
    DocumentResponse get(@PathVariable UUID id) {
        return toResponse(documents.get(id));
    }

    @GetMapping("/api/documents")
    Page<DocumentResponse> list(@RequestParam UUID spaceId, Pageable pageable) {
        return documents.list(spaceId, pageable).map(this::toResponse);
    }

    @PutMapping("/api/documents/{id}/title")
    DocumentResponse rename(@PathVariable UUID id, @RequestBody TitleRequest request) {
        return toResponse(documents.rename(id, request.title()));
    }

    @PutMapping("/api/documents/{id}/blocks")
    ResponseEntity<Void> replaceBlocks(
            @PathVariable UUID id,
            @RequestBody BlocksRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        List<BlockPayload> payloads = request.blocks().stream()
                .map(block -> new BlockPayload(block.type(), block.content(), block.sourceType(), block.sourceUrl(), block.sourceRef()))
                .toList();
        documents.replaceBlocks(id, payloads, memberId, ChangeCause.MANUAL);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/documents/{id}/verify")
    DocumentResponse verify(
            @PathVariable UUID id,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        documents.verify(id, memberId);
        return toResponse(documents.get(id));
    }

    @PostMapping("/api/documents/{id}/archive")
    ResponseEntity<Void> archive(
            @PathVariable UUID id,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        documents.archive(id, memberId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/documents/{id}/owner")
    DocumentResponse changeOwner(
            @PathVariable UUID id,
            @RequestBody OwnerRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        documents.changeOwner(id, request.ownerId(), memberId);
        return toResponse(documents.get(id));
    }

    @GetMapping("/api/documents/{id}/revisions")
    Page<RevisionSummary> revisions(@PathVariable UUID id, Pageable pageable) {
        documents.get(id);
        return revisions.findByDocumentIdOrderByCreatedAtDesc(id, pageable).map(this::toSummary);
    }

    @GetMapping("/api/revisions/{id}")
    RevisionDetail revision(@PathVariable UUID id) {
        Revision revision = revisions.findById(id)
                .orElseThrow(() -> new NotFoundException("revision not found: " + id));
        return new RevisionDetail(
                revision.getId(),
                revision.getDocumentId(),
                revision.getSnapshot(),
                revision.getEditorId(),
                revision.getCause(),
                revision.getCreatedAt()
        );
    }

    private DocumentResponse toResponse(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getSpace().getId(),
                document.getTitle(),
                new OwnerResponse(document.getOwner().getId(), document.getOwner().getDisplayName()),
                document.getStatus(),
                document.getVerifiedAt(),
                document.getTtlDays(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                blocks.findByDocumentIdOrderByPosition(document.getId()).stream().map(DocumentController::toBlockResponse).toList()
        );
    }

    private RevisionSummary toSummary(Revision revision) {
        String editorName = members.findById(revision.getEditorId()).map(Member::getDisplayName).orElse("");
        return new RevisionSummary(revision.getId(), revision.getEditorId(), editorName, revision.getCause(), revision.getCreatedAt());
    }

    private static BlockResponse toBlockResponse(Block block) {
        return new BlockResponse(
                block.getId(),
                block.getPosition(),
                block.getType(),
                block.getContent(),
                block.getProvenance().getSourceType(),
                block.getProvenance().getSourceUrl(),
                block.getProvenance().getSourceRef(),
                block.getUpdatedAt()
        );
    }
}
