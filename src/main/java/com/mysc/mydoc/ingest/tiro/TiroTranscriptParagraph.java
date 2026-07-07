package com.mysc.mydoc.ingest.tiro;

public record TiroTranscriptParagraph(
        String uuid,
        String timeFrom,
        String timeTo,
        String transcript,
        String summary,
        Boolean locked
) {}
