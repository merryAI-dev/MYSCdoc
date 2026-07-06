package com.mysc.mydoc.ingest.meet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnExpression(MeetConditions.ENABLED)
public class MeetEventPuller {
    private static final Logger log = LoggerFactory.getLogger(MeetEventPuller.class);

    private final MeetIngestService ingest;
    private final MeetSubscriptionJob subscriptions;
    private final ObjectMapper objectMapper;
    private final String subscriptionName;
    private Subscriber subscriber;

    public MeetEventPuller(
            MeetIngestService ingest,
            MeetSubscriptionJob subscriptions,
            ObjectMapper objectMapper,
            @Value("${mydoc.meet.pubsub-subscription}") String subscriptionName
    ) {
        this.ingest = ingest;
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
        this.subscriptionName = subscriptionName;
    }

    @PostConstruct
    void start() {
        MessageReceiver receiver = this::receive;
        subscriber = Subscriber.newBuilder(ProjectSubscriptionName.parse(subscriptionName), receiver).build();
        subscriber.startAsync().awaitRunning();
    }

    @PreDestroy
    void stop() {
        if (subscriber != null) {
            try {
                subscriber.stopAsync().awaitTerminated(10, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                log.warn("Timed out stopping Meet Pub/Sub subscriber", exception);
            }
        }
    }

    private void receive(PubsubMessage message, AckReplyConsumer consumer) {
        try {
            route(message);
            consumer.ack();
        } catch (MeetRetryableException exception) {
            log.warn("Meet event processing will be retried: {}", exception.getMessage());
            consumer.nack();
        } catch (RuntimeException exception) {
            log.warn("Meet event processing failed; Pub/Sub will retry", exception);
            consumer.nack();
        }
    }

    void route(PubsubMessage message) {
        String type = message.getAttributesOrDefault("ce-type", "");
        if (!StringUtils.hasText(type)) {
            type = message.getAttributesOrDefault("ce_type", "");
        }
        if (MeetSubscriptionJob.SMART_NOTE_FILE_GENERATED.equals(type)) {
            JsonNode payload = readPayload(message);
            ingest.onArtifactGenerated(requiredName(payload, "smartNote"), ArtifactKind.SMART_NOTE);
        } else if (MeetSubscriptionJob.TRANSCRIPT_FILE_GENERATED.equals(type)) {
            JsonNode payload = readPayload(message);
            ingest.onArtifactGenerated(requiredName(payload, "transcript"), ArtifactKind.TRANSCRIPT);
        } else if (MeetSubscriptionJob.EXPIRATION_REMINDER.equals(type)) {
            JsonNode payload = readPayload(message);
            subscriptions.renewFromEvent(payload);
        } else {
            log.debug("Ignoring unsupported Meet event type {}", type);
        }
    }

    private JsonNode readPayload(PubsubMessage message) {
        try {
            return objectMapper.readTree(message.getData().toStringUtf8());
        } catch (Exception exception) {
            throw new MeetRetryableException("Meet event payload is not JSON", exception);
        }
    }

    private String requiredName(JsonNode payload, String field) {
        String name = payload.path(field).path("name").asText("");
        if (!StringUtils.hasText(name)) {
            throw new MeetRetryableException("Meet event has no " + field + ".name");
        }
        return name;
    }
}
