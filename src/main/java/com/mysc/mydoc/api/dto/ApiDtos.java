package com.mysc.mydoc.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.DocStatus;
import com.mysc.mydoc.domain.MemberRole;
import com.mysc.mydoc.domain.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {}

    public record SpaceRequest(String slug, String name) {}
    public record SpaceResponse(UUID id, String slug, String name, Instant createdAt) {}

    public record MemberRequest(String email, String displayName, MemberRole role) {}
    public record MemberResponse(UUID id, String email, String displayName, MemberRole role, String slackUserId, Instant createdAt) {}
    public record OwnerResponse(UUID id, String displayName) {}

    public record DocumentCreateRequest(UUID spaceId, String title) {}
    public record TitleRequest(String title) {}
    public record OwnerRequest(UUID ownerId) {}
    public record BlocksRequest(List<BlockRequest> blocks) {}
    public record BlockRequest(BlockType type, JsonNode content, SourceType sourceType, String sourceUrl, String sourceRef) {}

    public record DocumentResponse(
            UUID id,
            UUID spaceId,
            String title,
            OwnerResponse owner,
            DocStatus status,
            Instant verifiedAt,
            int ttlDays,
            Instant createdAt,
            Instant updatedAt,
            List<BlockResponse> blocks
    ) {}

    public record BlockResponse(
            UUID id,
            int position,
            BlockType type,
            JsonNode content,
            SourceType sourceType,
            String sourceUrl,
            String sourceRef,
            Instant updatedAt
    ) {}

    public record RevisionSummary(UUID id, UUID editorId, String editorName, ChangeCause cause, Instant createdAt) {}
    public record RevisionDetail(UUID id, UUID documentId, JsonNode snapshot, UUID editorId, ChangeCause cause, Instant createdAt) {}
    public record SearchResponse(List<SearchHitResponse> hits) {}
    public record SearchHitResponse(UUID documentId, String title, String headingPath, String snippet, double score) {}
}
