package com.mysc.mydoc.ingest.meet;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression(MeetConditions.ENABLED)
public class GoogleMeetArtifactGateway implements MeetArtifactGateway {
    private final GoogleWorkspaceAccessTokenProvider tokens;
    private final RestClient v2;
    private final RestClient v2beta;

    public GoogleMeetArtifactGateway(GoogleWorkspaceAccessTokenProvider tokens, RestClient.Builder restClientBuilder) {
        this.tokens = tokens;
        this.v2 = restClientBuilder.clone().baseUrl("https://meet.googleapis.com/v2").build();
        this.v2beta = restClientBuilder.clone().baseUrl("https://meet.googleapis.com/v2beta").build();
    }

    @Override
    public MeetArtifact get(String resourceName, ArtifactKind kind) {
        JsonNode response = client(kind).get()
                .uri("/" + resourceName)
                .header("Authorization", "Bearer " + tokens.bearerToken())
                .retrieve()
                .body(JsonNode.class);
        String documentId = response.path("docsDestination").path("document").asText("");
        if (!StringUtils.hasText(documentId)) {
            throw new MeetRetryableException("Meet artifact has no docsDestination.document");
        }
        return new MeetArtifact(response.path("state").asText(""), documentId);
    }

    private RestClient client(ArtifactKind kind) {
        return kind == ArtifactKind.SMART_NOTE ? v2beta : v2;
    }
}
