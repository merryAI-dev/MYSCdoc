package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.Chunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {
    void deleteByDocumentId(UUID documentId);

    @Query(value = """
            SELECT c.id AS id, c.document_id AS documentId, c.heading_path AS headingPath, c.text AS text
            FROM chunk c JOIN document d ON d.id = c.document_id
            WHERE d.status IN ('ACTIVE','STALE')
              AND (CAST(:spaceId AS uuid) IS NULL OR d.space_id = CAST(:spaceId AS uuid))
            ORDER BY c.embedding <=> CAST(:queryVector AS vector)
            LIMIT 20
            """, nativeQuery = true)
    List<ChunkSearchRow> vectorHits(@Param("queryVector") String queryVector, @Param("spaceId") UUID spaceId);

    @Query(value = """
            SELECT c.id AS id, c.document_id AS documentId, c.heading_path AS headingPath, c.text AS text
            FROM chunk c JOIN document d ON d.id = c.document_id
            WHERE d.status IN ('ACTIVE','STALE')
              AND (CAST(:spaceId AS uuid) IS NULL OR d.space_id = CAST(:spaceId AS uuid))
              AND c.ts @@ plainto_tsquery('simple', :query)
            ORDER BY ts_rank(c.ts, plainto_tsquery('simple', :query)) DESC
            LIMIT 20
            """, nativeQuery = true)
    List<ChunkSearchRow> keywordHits(@Param("query") String query, @Param("spaceId") UUID spaceId);
}
