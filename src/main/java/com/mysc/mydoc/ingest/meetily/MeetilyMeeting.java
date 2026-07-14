package com.mysc.mydoc.ingest.meetily;

/** GET /get-meetings 응답 항목 — Meetily 목록 API는 id와 title만 내려준다. */
public record MeetilyMeeting(String id, String title) {}
