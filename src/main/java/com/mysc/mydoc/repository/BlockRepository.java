package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.Block;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockRepository extends JpaRepository<Block, UUID> {
    List<Block> findByDocumentIdOrderByPosition(UUID documentId);
    void deleteByDocumentId(UUID documentId);
}
