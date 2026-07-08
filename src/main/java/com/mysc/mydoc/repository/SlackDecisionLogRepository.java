package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.SlackDecisionLog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackDecisionLogRepository extends JpaRepository<SlackDecisionLog, UUID> {
    Optional<SlackDecisionLog> findByChannelIdAndThreadTs(String channelId, String threadTs);
}
