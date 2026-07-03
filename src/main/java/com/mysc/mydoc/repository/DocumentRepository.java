package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.DocStatus;
import com.mysc.mydoc.domain.Document;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    @EntityGraph(attributePaths = {"space", "owner"})
    java.util.Optional<Document> findById(UUID id);

    @EntityGraph(attributePaths = {"space", "owner"})
    Page<Document> findBySpaceIdAndStatusNot(UUID spaceId, DocStatus excluded, Pageable pageable);
    @EntityGraph(attributePaths = {"space", "owner"})
    Page<Document> findByStatusAndSpaceId(DocStatus status, UUID spaceId, Pageable pageable);

    List<Document> findByStatusAndVerifiedAtBefore(DocStatus status, Instant threshold);
    List<Document> findByStatusAndVerifiedAtIsNullAndCreatedAtBefore(DocStatus status, Instant threshold);

    @Query(value = """
            SELECT *
            FROM document
            WHERE status = 'ACTIVE'
              AND (
                    (verified_at IS NOT NULL AND verified_at < now() - make_interval(days => ttl_days))
                 OR (verified_at IS NULL AND created_at < now() - make_interval(days => ttl_days))
              )
            """, nativeQuery = true)
    List<Document> findStalenessCandidates();
}
