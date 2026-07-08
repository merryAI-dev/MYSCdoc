package com.mysc.mydoc.ingest.archive;

import com.mysc.mydoc.domain.SlackArchiveMessage;
import com.mysc.mydoc.repository.SlackArchiveMessageRepository;
import com.mysc.mydoc.repository.SlackChannelConfigRepository;
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
    private final SlackChannelConfigRepository channelConfigs;

    public SlackArchiveService(SlackArchiveMessageRepository messages, SlackChannelConfigRepository channelConfigs) {
        this.messages = messages;
        this.channelConfigs = channelConfigs;
    }

    // Slack이 3초 내 ack를 못 받으면 이벤트를 재전달하므로 (channel_id, ts) unique로 중복 저장을 막는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void archive(String channelId, String ts, String threadTs, String userId, String text) {
        if (!StringUtils.hasText(channelId) || !StringUtils.hasText(ts)
                || !StringUtils.hasText(userId) || !StringUtils.hasText(text)) {
            return;
        }
        // 봇 초대만으로는 수집하지 않는다 — /knowledge.html 설정에서 명시적으로 켠 채널만 (명시적 옵트인).
        if (!channelConfigs.existsByChannelIdAndArchiveEnabledTrue(channelId)) {
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
