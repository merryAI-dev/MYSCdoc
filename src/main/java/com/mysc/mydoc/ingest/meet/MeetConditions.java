package com.mysc.mydoc.ingest.meet;

final class MeetConditions {
    static final String ENABLED = """
            '${mydoc.meet.google-application-credentials:}' != ''
            && '${mydoc.meet.subscribed-user:}' != ''
            && '${mydoc.meet.pubsub-subscription:}' != ''
            && '${mydoc.meet.default-space-slug:}' != ''
            """;

    private MeetConditions() {}
}
