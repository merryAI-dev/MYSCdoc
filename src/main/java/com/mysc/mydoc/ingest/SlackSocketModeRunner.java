package com.mysc.mydoc.ingest;

import com.mysc.mydoc.ingest.archive.SlackArchiveService;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageDeletedEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.ReactionAddedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(SlackIngestService.class)
@ConditionalOnExpression("'${mydoc.slack.bot-token:}' != '' && '${mydoc.slack.app-token:}' != ''")
public class SlackSocketModeRunner {
    private static final Logger log = LoggerFactory.getLogger(SlackSocketModeRunner.class);

    // 봇이 초대된 채널만 이벤트가 오지만, DM(im/mpim) 제외는 코드에서도 한 번 더 강제한다.
    private static final Set<String> ARCHIVED_CHANNEL_TYPES = Set.of("channel", "group");

    private final SlackIngestService ingest;
    private final SlackArchiveService archive;
    private final String botToken;
    private final String appToken;
    private SocketModeApp socketModeApp;

    public SlackSocketModeRunner(
            SlackIngestService ingest,
            SlackArchiveService archive,
            @Value("${mydoc.slack.bot-token}") String botToken,
            @Value("${mydoc.slack.app-token}") String appToken
    ) {
        this.ingest = ingest;
        this.archive = archive;
        this.botToken = botToken;
        this.appToken = appToken;
    }

    @PostConstruct
    void start() throws Exception {
        App app = new App(AppConfig.builder().singleTeamBotToken(botToken).build());
        app.event(ReactionAddedEvent.class, (payload, ctx) -> {
            ReactionAddedEvent event = payload.getEvent();
            if ("bookmark".equals(event.getReaction()) && event.getItem() != null) {
                ingest.onReactionAdded(event.getItem().getChannel(), event.getItem().getTs(), event.getUser());
            }
            return ctx.ack();
        });
        app.event(MessageEvent.class, (payload, ctx) -> {
            MessageEvent event = payload.getEvent();
            if (ARCHIVED_CHANNEL_TYPES.contains(event.getChannelType()) && event.getBotId() == null) {
                archive.archive(event.getChannel(), event.getTs(), event.getThreadTs(), event.getUser(), event.getText());
            }
            return ctx.ack();
        });
        // 수정/삭제는 아카이브에 반영하지 않는다(append-only). 핸들러가 없으면 매번 경고 로그가 남아 no-op으로 ack만 한다.
        app.event(MessageChangedEvent.class, (payload, ctx) -> ctx.ack());
        app.event(MessageDeletedEvent.class, (payload, ctx) -> ctx.ack());
        socketModeApp = new SocketModeApp(appToken, app);
        socketModeApp.startAsync();
        log.info("Slack Socket Mode started");
    }

    @PreDestroy
    void stop() throws Exception {
        if (socketModeApp != null) {
            socketModeApp.stop();
        }
    }
}
