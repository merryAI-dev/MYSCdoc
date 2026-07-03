package com.mysc.mydoc.service;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class EditingPlaneClient {
    private static final Logger log = LoggerFactory.getLogger(EditingPlaneClient.class);

    private final RestClient restClient;
    private final String internalServiceToken;

    public EditingPlaneClient(
            RestClient.Builder restClientBuilder,
            @Value("${mydoc.editing-plane-url}") String editingPlaneUrl,
            @Value("${mydoc.internal-service-token}") String internalServiceToken
    ) {
        this.restClient = restClientBuilder.baseUrl(editingPlaneUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    public void kick(UUID documentId, UUID memberId) {
        if (!StringUtils.hasText(internalServiceToken)) {
            log.warn("Skipping editing-plane kick because internal service token is not configured");
            return;
        }
        try {
            restClient.post()
                    .uri("/internal/kick")
                    .header("Authorization", "Bearer " + internalServiceToken)
                    .body(Map.of("documentId", documentId.toString(), "memberId", memberId.toString()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            log.warn("Editing-plane kick failed for document {}", documentId, exception);
        }
    }
}
