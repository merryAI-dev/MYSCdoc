package com.mysc.mydoc.ingest.meetily;

import java.util.List;

/**
 * Meetily(오픈소스 로컬 회의 비서, https://github.com/Zackriya-Solutions/meetily)의
 * 레거시 FastAPI 백엔드(기본 포트 5167)를 추상화한다. 테스트에서 fake로 대체.
 */
public interface MeetilyPort {
    /** 회의 목록 — GET /get-meetings. id·title만 내려온다(날짜 없음, 최신순). */
    List<MeetilyMeeting> listMeetings();

    /** 회의 상세 + 전사 세그먼트 — GET /get-meeting/{id}. 없으면 null. */
    MeetilyMeetingDetail getMeeting(String meetingId);
}
