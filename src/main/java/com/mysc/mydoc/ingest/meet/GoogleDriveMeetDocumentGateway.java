package com.mysc.mydoc.ingest.meet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression(MeetConditions.ENABLED)
public class GoogleDriveMeetDocumentGateway implements MeetDocumentGateway {
    private final GoogleWorkspaceAccessTokenProvider tokens;
    private final RestClient restClient;

    public GoogleDriveMeetDocumentGateway(GoogleWorkspaceAccessTokenProvider tokens, RestClient.Builder restClientBuilder) {
        this.tokens = tokens;
        this.restClient = restClientBuilder.baseUrl("https://www.googleapis.com/drive/v3").build();
    }

    @Override
    public String exportText(String documentId) {
        return restClient.get()
                .uri(builder -> builder
                        .path("/files/{documentId}/export")
                        .queryParam("mimeType", "text/plain")
                        .build(documentId))
                .header("Authorization", "Bearer " + tokens.bearerToken())
                .retrieve()
                .body(String.class);
    }
}
