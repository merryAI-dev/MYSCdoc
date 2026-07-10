package com.mysc.mydoc.ingest.drive;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Drive/Docs 읽기 전용 접근 토큰. Meet(M7)과 같은 서비스 계정 키 파일을 재사용하지만,
 * 이쪽은 도메인 위임(delegated user) 없이 "가져올 폴더를 서비스 계정 이메일에 뷰어로 공유"하는
 * 방식으로 훨씬 단순하게 구성한다 — Workspace 관리자 콘솔 설정이 필요 없다.
 */
@Component
@ConditionalOnExpression("'${mydoc.drive.google-application-credentials:}' != ''")
public class GoogleDriveAccessTokenProvider {
    private static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/drive.readonly");

    private final GoogleCredentials credentials;

    public GoogleDriveAccessTokenProvider(
            @Value("${mydoc.drive.google-application-credentials}") String credentialsPath
    ) throws IOException {
        try (InputStream stream = Files.newInputStream(Path.of(credentialsPath))) {
            this.credentials = GoogleCredentials.fromStream(stream).createScoped(SCOPES);
        }
    }

    public synchronized String bearerToken() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException exception) {
            throw new IllegalStateException("Google Drive 토큰 갱신 실패", exception);
        }
    }
}
