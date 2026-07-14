package com.mysc.mydoc.ingest.meetily;

import java.util.List;

/**
 * GET /get-meeting/{id} 응답. transcripts는 세그먼트 목록 —
 * audioStartTime/audioEndTime은 null일 수 있고(Optional[float]),
 * 세그먼트의 id 필드는 meeting_id가 복사된 값이라 식별자로 쓰지 않는다.
 */
public record MeetilyMeetingDetail(String id, String title, String createdAt, List<Segment> segments) {
    public record Segment(String text, String timestamp, Double audioStartTime, Double audioEndTime) {}
}
