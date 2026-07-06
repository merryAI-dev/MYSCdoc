package com.mysc.mydoc.ingest.meet;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.pubsub.v1.SubscriptionName;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression(MeetConditions.ENABLED)
public class MeetSubscriptionJob {
    private static final Logger log = LoggerFactory.getLogger(MeetSubscriptionJob.class);
    static final String SMART_NOTE_FILE_GENERATED = "google.workspace.meet.smartNote.v2.fileGenerated";
    static final String TRANSCRIPT_FILE_GENERATED = "google.workspace.meet.transcript.v2.fileGenerated";
    static final String EXPIRATION_REMINDER = "google.workspace.events.subscription.v1.expirationReminder";
    private static final List<String> EVENT_TYPES = List.of(SMART_NOTE_FILE_GENERATED, TRANSCRIPT_FILE_GENERATED);
    private static final Duration RENEW_WINDOW = Duration.ofHours(48);

    private final GoogleWorkspaceAccessTokenProvider tokens;
    private final RestClient restClient;
    private final String subscribedUser;
    private final String pubsubSubscription;

    public MeetSubscriptionJob(
            GoogleWorkspaceAccessTokenProvider tokens,
            RestClient.Builder restClientBuilder,
            @Value("${mydoc.meet.subscribed-user}") String subscribedUser,
            @Value("${mydoc.meet.pubsub-subscription}") String pubsubSubscription
    ) {
        this.tokens = tokens;
        this.restClient = restClientBuilder.baseUrl("https://workspaceevents.googleapis.com/v1").build();
        this.subscribedUser = subscribedUser;
        this.pubsubSubscription = pubsubSubscription;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void ensureSubscription() {
        try {
            JsonNode subscription = findSubscription();
            if (subscription == null) {
                createSubscription(pubSubTopic());
                return;
            }
            String name = subscription.path("name").asText("");
            String expireTime = subscription.path("expireTime").asText("");
            if (StringUtils.hasText(name) && expiresSoon(expireTime)) {
                renew(name);
            }
        } catch (RuntimeException | IOException exception) {
            log.warn("Meet Workspace Events subscription check failed", exception);
        }
    }

    public void renewFromEvent(JsonNode payload) {
        String name = payload.path("subscription").path("name").asText("");
        if (!StringUtils.hasText(name)) {
            name = payload.path("name").asText("");
        }
        if (StringUtils.hasText(name)) {
            renew(name);
        }
    }

    private JsonNode findSubscription() {
        JsonNode response = restClient.get()
                .uri(builder -> builder
                        .path("/subscriptions")
                        .queryParam("filter", filter())
                        .build())
                .header("Authorization", "Bearer " + tokens.bearerToken())
                .retrieve()
                .body(JsonNode.class);
        for (JsonNode subscription : response.path("subscriptions")) {
            if (targetResource().equals(subscription.path("targetResource").asText(""))) {
                return subscription;
            }
        }
        return null;
    }

    private void createSubscription(String pubsubTopic) {
        restClient.post()
                .uri("/subscriptions")
                .header("Authorization", "Bearer " + tokens.bearerToken())
                .body(Map.of(
                        "targetResource", targetResource(),
                        "eventTypes", EVENT_TYPES,
                        "notificationEndpoint", Map.of("pubsubTopic", pubsubTopic),
                        "ttl", "0s"
                ))
                .retrieve()
                .toBodilessEntity();
    }

    private void renew(String name) {
        restClient.patch()
                .uri(builder -> builder
                        .path("/" + name)
                        .queryParam("updateMask", "ttl")
                        .build())
                .header("Authorization", "Bearer " + tokens.bearerToken())
                .body(Map.of("ttl", "0s"))
                .retrieve()
                .toBodilessEntity();
    }

    private String pubSubTopic() throws IOException {
        try (SubscriptionAdminClient client = SubscriptionAdminClient.create()) {
            return client.getSubscription(SubscriptionName.parse(pubsubSubscription)).getTopic();
        }
    }

    private boolean expiresSoon(String expireTime) {
        if (!StringUtils.hasText(expireTime)) {
            return true;
        }
        return Instant.parse(expireTime).isBefore(Instant.now().plus(RENEW_WINDOW));
    }

    private String filter() {
        return "( event_types:\"" + SMART_NOTE_FILE_GENERATED + "\" OR event_types:\""
                + TRANSCRIPT_FILE_GENERATED + "\" ) AND target_resource=\"" + targetResource() + "\"";
    }

    private String targetResource() {
        return "//cloudidentity.googleapis.com/users/" + subscribedUser;
    }
}
