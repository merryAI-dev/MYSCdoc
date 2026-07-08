package com.mysc.mydoc.ingest.archive;

import com.mysc.mydoc.domain.SlackArchiveMessage;
import com.mysc.mydoc.repository.SlackArchiveMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SlackArchiveService {
    private static final Logger log = LoggerFactory.getLogger(SlackArchiveService.class);

    private final SlackArchiveMessageRepository messages;

    public SlackArchiveService(SlackArchiveMessageRepository messages) {
        this.messages = messages;
    }

    // Slack이 3초 내 ack를 못 받으면 이벤트를 재전달하므로 (channel_id, ts) unique로 중복 저장을 막는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void archive(String channelId, String ts, String threadTs, String userId, String text) {
        if (!StringUtils.hasText(channelId) || !StringUtils.hasText(ts)
                || !StringUtils.hasText(userId) || !StringUtils.hasText(text)) {
            return;
        }
        String rootTs = StringUtils.hasText(threadTs) ? threadTs : ts;
        if (messages.existsByChannelIdAndTs(channelId, ts)) {
            return;
        }
        try {
            messages.saveAndFlush(new SlackArchiveMessage(channelId, ts, rootTs, userId, text));
        } catch (DataIntegrityViolationException redelivered) {
            log.debug("Slack archive skipped duplicate message {}:{}", channelId, ts);
        }
    }
}
