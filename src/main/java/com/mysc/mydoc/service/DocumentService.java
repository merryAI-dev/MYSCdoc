package com.mysc.mydoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ForbiddenException;
import com.mysc.mydoc.common.NotFoundException;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.Block;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.DocStatus;
import com.mysc.mydoc.domain.Document;
import com.mysc.mydoc.domain.Member;
import com.mysc.mydoc.domain.MemberRole;
import com.mysc.mydoc.domain.Provenance;
import com.mysc.mydoc.domain.Revision;
import com.mysc.mydoc.repository.BlockRepository;
import com.mysc.mydoc.repository.DocumentRepository;
import com.mysc.mydoc.repository.MemberRepository;
import com.mysc.mydoc.repository.RevisionRepository;
import com.mysc.mydoc.repository.SpaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DocumentService {
    private final DocumentRepository documents;
    private final SpaceRepository spaces;
    private final MemberRepository members;
    private final BlockRepository blocks;
    private final RevisionRepository revisions;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public DocumentService(
            DocumentRepository documents,
            SpaceRepository spaces,
            MemberRepository members,
            BlockRepository blocks,
            RevisionRepository revisions,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events
    ) {
        this.documents = documents;
        this.spaces = spaces;
        this.members = members;
        this.blocks = blocks;
        this.revisions = revisions;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Transactional
    public Document create(UUID spaceId, String title, UUID ownerId) {
        if (!StringUtils.hasText(title)) {
            throw new ValidationException("title is required");
        }
        var space = spaces.findById(spaceId)
                .orElseThrow(() -> new NotFoundException("space not found: " + spaceId));
        var owner = members.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("member not found: " + ownerId));
        return documents.save(new Document(space, title, owner));
    }

    @Transactional(readOnly = true)
    public Document get(UUID docId) {
        return documents.findById(docId)
                .orElseThrow(() -> new NotFoundException("document not found: " + docId));
    }

    @Transactional(readOnly = true)
    public Page<Document> list(UUID spaceId, Pageable p) {
        return documents.findBySpaceIdAndStatusNot(spaceId, DocStatus.ARCHIVED, p);
    }

    @Transactional(readOnly = true)
    public Page<Document> listStale(UUID spaceId, Pageable p) {
        return documents.findByStatusAndSpaceId(DocStatus.STALE, spaceId, p);
    }

    @Transactional
    public Document rename(UUID docId, String title) {
        if (!StringUtils.hasText(title)) {
            throw new ValidationException("title is required");
        }
        Document document = get(docId);
        document.rename(title);
        return document;
    }

    @Transactional
    public void replaceBlocks(UUID docId, List<BlockPayload> payloads, UUID editorId, ChangeCause cause) {
        Document document = get(docId);
        if (!members.existsById(editorId)) {
            throw new NotFoundException("member not found: " + editorId);
        }
        blocks.deleteByDocumentId(docId);

        List<Block> newBlocks = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            BlockPayload payload = payloads.get(i);
            if (payload.type() == null || payload.content() == null || payload.sourceType() == null) {
                throw new ValidationException("block type, content, sourceType are required");
            }
            newBlocks.add(new Block(
                    docId,
                    i,
                    payload.type(),
                    payload.content(),
                    new Provenance(payload.sourceType(), payload.sourceUrl(), payload.sourceRef())
            ));
        }

        blocks.saveAll(newBlocks);
        JsonNode snapshot = objectMapper.valueToTree(payloads);
        revisions.save(new Revision(docId, snapshot, editorId, cause));
        if (document.getStatus() == DocStatus.DRAFT && cause != ChangeCause.SLACK_INGEST && cause != ChangeCause.AI_SUGGESTION) {
            document.activate();
        } else {
            document.rename(document.getTitle());
        }
        events.publishEvent(new DocumentChangedEvent(docId));
    }

    @Transactional
    public void verify(UUID docId, UUID memberId) {
        Document document = get(docId);
        requireOwnerOrAdmin(document, memberId);
        document.verify();
    }

    @Transactional
    public void archive(UUID docId, UUID memberId) {
        Document document = get(docId);
        requireOwnerOrAdmin(document, memberId);
        document.archive();
    }

    @Transactional
    public void changeOwner(UUID docId, UUID newOwnerId, UUID actorId) {
        Document document = get(docId);
        Member actor = member(actorId);
        if (actor.getRole() != MemberRole.ADMIN && !document.getOwner().getId().equals(actorId)) {
            throw new ForbiddenException("owner or admin required");
        }
        document.changeOwner(member(newOwnerId));
    }

    private void requireOwnerOrAdmin(Document document, UUID memberId) {
        Member member = member(memberId);
        if (member.getRole() != MemberRole.ADMIN && !document.getOwner().getId().equals(memberId)) {
            throw new ForbiddenException("owner or admin required");
        }
    }

    private Member member(UUID memberId) {
        return members.findById(memberId)
                .orElseThrow(() -> new NotFoundException("member not found: " + memberId));
    }
}
