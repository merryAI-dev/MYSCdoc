package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.MeetilyIngestLog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetilyIngestLogRepository extends JpaRepository<MeetilyIngestLog, UUID> {
    Optional<MeetilyIngestLog> findByMeetingId(String meetingId);
}
