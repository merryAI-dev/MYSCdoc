package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.SlackIngestLog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackIngestLogRepository extends JpaRepository<SlackIngestLog, UUID> {
    Optional<SlackIngestLog> findByChannelIdAndThreadTs(String channelId, String threadTs);
    boolean existsByChannelIdAndThreadTs(String channelId, String threadTs);
}
