package com.mysc.mydoc.ingest;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.ReactionAddedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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

    private final SlackIngestService ingest;
    private final String botToken;
    private final String appToken;
    private SocketModeApp socketModeApp;

    public SlackSocketModeRunner(
            SlackIngestService ingest,
            @Value("${mydoc.slack.bot-token}") String botToken,
            @Value("${mydoc.slack.app-token}") String appToken
    ) {
        this.ingest = ingest;
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
