package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.KnowledgeTriple;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface KnowledgeTripleRepository extends JpaRepository<KnowledgeTriple, UUID> {
    @Modifying
    void deleteByDocumentId(UUID documentId);

    boolean existsByDocumentId(UUID documentId);
}
