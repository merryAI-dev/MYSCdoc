package com.mysc.mydoc.ingest.tiro;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression("'${mydoc.tiro.api-key:}' != ''")
public class TiroClient implements TiroPort {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final AtomicReference<String> workspaceGuid = new AtomicReference<>();

    public TiroClient(RestClient.Builder restClientBuilder, @Value("${mydoc.tiro.api-key}") String apiKey) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.tiro.ooo/v1/external")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)))
                .build();
    }

    // 픽커용 가벼운 목록/검색. keyword가 비어 있으면 최신순 목록, 있으면 관련도순 shallow 검색(Tiro가 자체 재정렬).
    @Override
    public List<TiroNoteSummary> listNotes(String keyword) {
        String path = "/workspaces/{ws}/notes?size=20" + (keyword == null || keyword.isBlank() ? "" : "&keyword={kw}");
        NoteListResponse response = keyword == null || keyword.isBlank()
                ? restClient.get().uri(path, workspaceGuid()).retrieve().body(NoteListResponse.class)
                : restClient.get().uri(path, workspaceGuid(), keyword).retrieve().body(NoteListResponse.class);
        return response == null || response.content() == null ? List.of() : response.content();
    }

    @Override
    public TiroNoteSummary getNote(String noteGuid) {
        return restClient.get().uri("/notes/{guid}", noteGuid).retrieve().body(TiroNoteSummary.class);
    }

    @Override
    public List<TiroTranscriptParagraph> getTranscriptParagraphs(String noteGuid) {
        ParagraphListResponse response = restClient.get()
                .uri("/notes/{guid}/paragraphs", noteGuid)
                .retrieve()
                .body(ParagraphListResponse.class);
        if (response == null || response.content() == null) {
            return List.of();
        }
        return response.content().stream()
                .map(paragraph -> new TiroTranscriptParagraph(
                        paragraph.uuid(),
                        paragraph.timeFrom(),
                        paragraph.timeTo(),
                        content(paragraph.transcript()),
                        content(paragraph.summary()),
                        paragraph.locked()
                ))
                .toList();
    }

    private String content(TextBlock text) {
        return text == null ? "" : text.content();
    }

    private String workspaceGuid() {
        String cached = workspaceGuid.get();
        if (cached != null) {
            return cached;
        }
        WorkspaceMe me = restClient.get().uri("/workspaces/me").retrieve().body(WorkspaceMe.class);
        String guid = me.guid();
        workspaceGuid.compareAndSet(null, guid);
        return guid;
    }

    private record WorkspaceMe(String guid) {}
    private record NoteListResponse(List<TiroNoteSummary> content) {}
    private record ParagraphListResponse(List<Paragraph> content) {}
    private record Paragraph(String uuid, TextBlock transcript, TextBlock summary, String timeFrom, String timeTo, Boolean locked) {}
    private record TextBlock(String content) {}
}
