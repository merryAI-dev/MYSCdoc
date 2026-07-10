package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.GoogleDriveIngestLog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoogleDriveIngestLogRepository extends JpaRepository<GoogleDriveIngestLog, UUID> {
    Optional<GoogleDriveIngestLog> findByDriveFileId(String driveFileId);
}
