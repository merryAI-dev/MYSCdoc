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

    /** 아카이브 대상 메시지 하나 (스레드 루트/답글 구분은 threadTs로). */
    record ArchivableMessage(String ts, String threadTs, String userId, String text) {}

    /**
     * 채널의 최근 메시지를 스레드 답글까지 펼쳐 반환한다 (백필용, 읽기 전용).
     * 봇/시스템 메시지와 빈 본문은 제외한다. (테스트 fake가 안 깨지게 default)
     */
    default List<ArchivableMessage> channelHistory(String channelId, int limit) {
        return List.of();
    }
}
