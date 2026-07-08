package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.SlackArchiveMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlackArchiveMessageRepository extends JpaRepository<SlackArchiveMessage, UUID> {
    boolean existsByChannelIdAndTs(String channelId, String ts);

    List<SlackArchiveMessage> findByChannelIdAndThreadTsOrderByTs(String channelId, String threadTs);

    // MAX(ts): Slack ts는 "고정폭 epoch 초.시퀀스" 문자열이라 사전순 비교가 시간순과 일치한다.
    @Query(value = """
            SELECT channel_id AS channelId, thread_ts AS threadTs, MAX(ts) AS lastTs
            FROM slack_archive_message
            GROUP BY channel_id, thread_ts
            HAVING COUNT(*) >= :minMessages AND MAX(created_at) < :quietBefore
            """, nativeQuery = true)
    List<QuietThread> findQuietThreads(@Param("minMessages") int minMessages, @Param("quietBefore") Instant quietBefore);

    interface QuietThread {
        String getChannelId();
        String getThreadTs();
        String getLastTs();
    }
}
