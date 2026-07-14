package com.mysc.mydoc.ingest.meetily;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Meetily 레거시 FastAPI 백엔드 게이트웨이 (기본 http://localhost:5167).
 *
 * 주의사항 (repo 실사 결과):
 * - 이 백엔드는 무인증 + CORS 와일드카드라 반드시 localhost/사내망에만 바인딩해야 한다.
 *   mydoc이 유일한 소비자가 되도록 게이트웨이 뒤에 격리하는 전제.
 * - 목록 API는 id·title만 내려준다(날짜 없음). 날짜는 상세에서.
 * - 세그먼트 SQL에 ORDER BY가 없어 순서 보장이 없다 → audio_start_time·timestamp로 정렬해 쓴다.
 */
@Component
@ConditionalOnExpression("'${mydoc.meetily.base-url:}' != ''")
public class MeetilyApiGateway implements MeetilyPort {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestClient restClient;

    public MeetilyApiGateway(RestClient.Builder restClientBuilder,
                             @Value("${mydoc.meetily.base-url}") String baseUrl) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(CONNECT_TIMEOUT)
                        .withReadTimeout(READ_TIMEOUT)))
                .build();
    }

    @Override
    public List<MeetilyMeeting> listMeetings() {
        List<MeetingItem> items = restClient.get()
                .uri("/get-meetings")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<MeetingItem>>() {});
        if (items == null) {
            return List.of();
        }
        return items.stream().map(item -> new MeetilyMeeting(item.id(), item.title())).toList();
    }

    @Override
    public MeetilyMeetingDetail getMeeting(String meetingId) {
        MeetingDetailResponse response;
        try {
            response = restClient.get()
                    .uri("/get-meeting/{id}", meetingId)
                    .retrieve()
                    // 404만 '없는 회의'로 취급한다 — 422/401/403 등 다른 4xx까지 삼키면 설정·인증
                    // 오류가 '회의 없음'으로 둔갑한다. 바디 변환 전에 끊어야 프록시의 HTML 에러
                    // 페이지(비JSON)에도 안전하다.
                    .onStatus(status -> status.value() == 404, (request, res) -> {
                        throw new MeetingNotFound();
                    })
                    .body(MeetingDetailResponse.class);
        } catch (MeetingNotFound notFound) {
            return null;
        }
        if (response == null || response.id() == null) {
            return null;
        }
        List<MeetilyMeetingDetail.Segment> segments = response.transcripts() == null ? List.of()
                : response.transcripts().stream()
                        // SQL에 ORDER BY가 없어 순서 비보장 — 오디오 시작시각(없으면 timestamp 문자열)으로 정렬
                        .sorted(Comparator
                                .comparing(TranscriptSegment::audioStartTime,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(TranscriptSegment::timestamp,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(segment -> new MeetilyMeetingDetail.Segment(
                                segment.text(), segment.timestamp(),
                                segment.audioStartTime(), segment.audioEndTime()))
                        .toList();
        return new MeetilyMeetingDetail(response.id(), response.title(), response.createdAt(), segments);
    }

    /** 404 신호용 — onStatus 핸들러에서 던져 바디 변환 전에 흐름을 끊는다. */
    private static final class MeetingNotFound extends RuntimeException {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MeetingItem(String id, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MeetingDetailResponse(
            String id,
            String title,
            @JsonProperty("created_at") String createdAt,
            List<TranscriptSegment> transcripts
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TranscriptSegment(
            String text,
            String timestamp,
            @JsonProperty("audio_start_time") Double audioStartTime,
            @JsonProperty("audio_end_time") Double audioEndTime
    ) {}
}
