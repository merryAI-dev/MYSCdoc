package com.mysc.mydoc.ingest.tiro;

import java.util.List;

public record TiroNoteSummary(
        String guid,
        String title,
        String webUrl,
        String createdAt,
        Integer recordingDurationSeconds,
        List<Collaborator> collaborators,
        List<Participant> participants,
        String recordingStartAt,
        String recordingEndAt
) {
    public record Collaborator(String guid, String name, String email, String role) {}
    public record Participant(String name, String email) {}
}
