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

    private static final int MAX_START_ATTEMPTS = 5;
    private static final long START_BACKOFF_MS = 3000;

    private final SlackIngestService ingest;
    private final SlackArchiveService archive;
    private final String botToken;
    private final String appToken;
    private volatile SocketModeApp socketModeApp;

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
    void start() {
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

        // Slack 연결을 부팅 스레드에서 떼어낸다. SocketModeApp.startAsync()는 이름과 달리 내부에서
        // authTest를 동기 호출하는데, Slack의 일시적 오류(레이트리밋·네트워크)로 여기서 예외가 나면
        // @PostConstruct가 실패해 Spring 컨텍스트 전체가 죽는다 — Slack 수집 하나가 코어 플랫폼을
        // 다운시키면 안 되므로, 별도 데몬 스레드에서 재시도하고 끝내 실패해도 앱은 정상 기동한다.
        Thread starter = new Thread(() -> connectWithRetry(app), "slack-socket-mode-starter");
        starter.setDaemon(true);
        starter.start();
    }

    private void connectWithRetry(App app) {
        for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
            try {
                SocketModeApp started = new SocketModeApp(appToken, app);
                started.startAsync();
                socketModeApp = started;
                log.info("Slack Socket Mode started (attempt {})", attempt);
                return;
            } catch (Exception exception) {
                log.warn("Slack Socket Mode start failed (attempt {}/{}): {}",
                        attempt, MAX_START_ATTEMPTS, exception.getMessage());
                if (attempt < MAX_START_ATTEMPTS) {
                    try {
                        Thread.sleep(START_BACKOFF_MS * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("Slack Socket Mode disabled after {} failed attempts — 코어 플랫폼은 Slack 수집 없이 계속 동작합니다.",
                MAX_START_ATTEMPTS);
    }

    @PreDestroy
    void stop() throws Exception {
        if (socketModeApp != null) {
            socketModeApp.stop();
        }
    }
}
