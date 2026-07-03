package com.mysc.mydoc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CollabTokenService {
    public static final int EXPIRES_IN_SECONDS = 3600; // 03-api-spec.md

    private final DocumentService documents;
    private final ObjectMapper objectMapper;
    private final String jwtSecret;

    public CollabTokenService(
            DocumentService documents,
            ObjectMapper objectMapper,
            @Value("${mydoc.collab-jwt-secret}") String jwtSecret
    ) {
        this.documents = documents;
        this.objectMapper = objectMapper;
        this.jwtSecret = jwtSecret;
    }

    public String issue(UUID documentId, UUID memberId) {
        if (documentId == null) {
            throw new ValidationException("documentId is required");
        }
        if (!StringUtils.hasText(jwtSecret)) {
            throw new ValidationException("collab jwt secret is not configured");
        }
        documents.get(documentId);
        long exp = Instant.now().plusSeconds(EXPIRES_IN_SECONDS).getEpochSecond();
        String header = json(Map.of("alg", "HS256", "typ", "JWT"));
        String payload = json(Map.of(
                "sub", memberId.toString(),
                "doc", documentId.toString(),
                "perm", "write",
                "exp", exp
        ));
        String signingInput = base64Url(header.getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(sign(signingInput));
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
