package com.mysc.mydoc.ingest.meet;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(MeetConditions.ENABLED)
public class GoogleWorkspaceAccessTokenProvider {
    private static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/meetings.space.readonly",
            "https://www.googleapis.com/auth/drive.readonly"
    );

    private final GoogleCredentials credentials;

    public GoogleWorkspaceAccessTokenProvider(
            @Value("${mydoc.meet.google-application-credentials}") String credentialsPath,
            @Value("${mydoc.meet.subscribed-user}") String delegatedUser
    ) throws IOException {
        try (InputStream stream = Files.newInputStream(Path.of(credentialsPath))) {
            this.credentials = GoogleCredentials.fromStream(stream)
                    .createScoped(SCOPES)
                    .createDelegated(delegatedUser);
        }
    }

    public synchronized String bearerToken() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException exception) {
            throw new MeetRetryableException("Google Workspace token refresh failed", exception);
        }
    }
}
