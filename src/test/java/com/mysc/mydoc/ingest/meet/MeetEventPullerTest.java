package com.mysc.mydoc.ingest.meet;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import org.junit.jupiter.api.Test;

class MeetEventPullerTest {

    @Test
    void unknownEventTypeIsIgnoredWithoutParsingPayload() {
        MeetIngestService ingest = mock(MeetIngestService.class);
        MeetSubscriptionJob subscriptions = mock(MeetSubscriptionJob.class);
        MeetEventPuller puller = new MeetEventPuller(ingest, subscriptions, new ObjectMapper(), "projects/p/subscriptions/s");
        PubsubMessage message = PubsubMessage.newBuilder()
                .putAttributes("ce-type", "google.workspace.unrelated")
                .setData(ByteString.copyFromUtf8("not json"))
                .build();

        assertThatCode(() -> puller.route(message)).doesNotThrowAnyException();
        verifyNoInteractions(ingest, subscriptions);
    }
}
