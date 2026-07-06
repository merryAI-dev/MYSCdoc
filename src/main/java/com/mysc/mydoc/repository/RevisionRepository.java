package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.Revision;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevisionRepository extends JpaRepository<Revision, UUID> {
    Page<Revision> findByDocumentIdOrderByCreatedAtDesc(UUID documentId, Pageable pageable);
    Optional<Revision> findFirstByDocumentIdOrderByCreatedAtDesc(UUID documentId);
}
