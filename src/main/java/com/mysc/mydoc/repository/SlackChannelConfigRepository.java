package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.SlackChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackChannelConfigRepository extends JpaRepository<SlackChannelConfig, String> {
    boolean existsByChannelIdAndArchiveEnabledTrue(String channelId);
}
