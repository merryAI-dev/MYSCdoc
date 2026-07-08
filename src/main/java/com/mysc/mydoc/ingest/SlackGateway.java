package com.mysc.mydoc.ingest;

import java.util.List;
import java.util.Optional;

public interface SlackGateway {
    SlackThread thread(String channelId, String messageTs);
    Optional<String> userEmail(String slackUserId);
    void reply(String channelId, String threadTs, String text);

    record SlackChannel(String id, String name, boolean isPrivate) {}

    /** 봇이 멤버로 초대돼 있는 공개/비공개 채널 목록. (아카이브 설정 UI 전용이라 default로 둔다) */
    default List<SlackChannel> memberChannels() {
        return List.of();
    }
}
