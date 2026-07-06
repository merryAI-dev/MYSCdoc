package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.MeetIngestLog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetIngestLogRepository extends JpaRepository<MeetIngestLog, UUID> {
    boolean existsByConferenceRecord(String conferenceRecord);
    Optional<MeetIngestLog> findByConferenceRecord(String conferenceRecord);
}
