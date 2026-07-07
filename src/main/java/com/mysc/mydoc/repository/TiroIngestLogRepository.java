package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.TiroIngestLog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TiroIngestLogRepository extends JpaRepository<TiroIngestLog, UUID> {
    Optional<TiroIngestLog> findByNoteGuid(String noteGuid);
}
