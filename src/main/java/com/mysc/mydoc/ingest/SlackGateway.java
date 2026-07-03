package com.mysc.mydoc.ingest;

import java.util.Optional;

public interface SlackGateway {
    SlackThread thread(String channelId, String messageTs);
    Optional<String> userEmail(String slackUserId);
    void reply(String channelId, String threadTs, String text);
}
