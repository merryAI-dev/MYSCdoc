package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.Block;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockRepository extends JpaRepository<Block, UUID> {
    List<Block> findByDocumentIdOrderByPosition(UUID documentId);

    @Modifying
    @Query("delete from Block b where b.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);
}
