package com.mysc.mydoc.ingest.drive;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression("'${mydoc.drive.google-application-credentials:}' != ''")
public class GoogleDriveApiGateway implements GoogleDriveGateway {
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String DOC_MIME = "application/vnd.google-apps.document";
    // 명시적 타임아웃 — 기본값을 쓰면 Slack SDK의 OkHttp(10초 read)가 픽업돼 큰 문서 export가
    // 조용히 실패한다 (M2/M5에서 Gemini로 이미 당한 함정).
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final GoogleDriveAccessTokenProvider tokens;
    private final RestClient restClient;

    public GoogleDriveApiGateway(GoogleDriveAccessTokenProvider tokens, RestClient.Builder restClientBuilder) {
        this.tokens = tokens;
        this.restClient = restClientBuilder
                .baseUrl("https://www.googleapis.com/drive/v3")
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)))
                .build();
    }

    @Override
    public List<DriveDoc> listGoogleDocs(String folderId) {
        List<DriveDoc> docs = new ArrayList<>();
        Deque<String> pendingFolders = new ArrayDeque<>();
        pendingFolders.add(folderId);
        while (!pendingFolders.isEmpty()) {
            String current = pendingFolders.poll();
            for (DriveFile file : listChildren(current)) {
                if (FOLDER_MIME.equals(file.mimeType())) {
                    pendingFolders.add(file.id());
                } else if (DOC_MIME.equals(file.mimeType())) {
                    docs.add(new DriveDoc(file.id(), file.name()));
                }
            }
        }
        return docs;
    }

    private List<DriveFile> listChildren(String folderId) {
        List<DriveFile> children = new ArrayList<>();
        String pageToken = null;
        do {
            final String cursor = pageToken;
            FileListResponse response = restClient.get()
                    .uri(builder -> builder
                            .path("/files")
                            .queryParam("q", "'" + folderId + "' in parents and trashed = false")
                            .queryParam("fields", "nextPageToken, files(id, name, mimeType)")
                            .queryParam("pageSize", 200)
                            // 회사 회의록 폴더는 보통 공유 드라이브(Shared Drive)라, 이 두 플래그가
                            // 없으면 서비스 계정에 뷰어 권한이 있어도 목록이 조용히 빈 배열로 나온다.
                            .queryParam("supportsAllDrives", true)
                            .queryParam("includeItemsFromAllDrives", true)
                            .queryParamIfPresent("pageToken", java.util.Optional.ofNullable(cursor))
                            .build())
                    .header("Authorization", "Bearer " + tokens.bearerToken())
                    .retrieve()
                    .body(FileListResponse.class);
            if (response != null && response.files() != null) {
                children.addAll(response.files());
            }
            pageToken = response == null ? null : response.nextPageToken();
        } while (StringUtils.hasText(pageToken));
        return children;
    }

    @Override
    public String exportText(String fileId) {
        return restClient.get()
                .uri(builder -> builder
                        .path("/files/{fileId}/export")
                        .queryParam("mimeType", "text/plain")
                        .queryParam("supportsAllDrives", true)
                        .build(fileId))
                .header("Authorization", "Bearer " + tokens.bearerToken())
                .retrieve()
                .body(String.class);
    }

    private record DriveFile(String id, String name, String mimeType) {}
    private record FileListResponse(String nextPageToken, List<DriveFile> files) {}
}
