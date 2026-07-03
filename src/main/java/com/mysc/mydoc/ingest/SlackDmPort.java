package com.mysc.mydoc.ingest;

public interface SlackDmPort {
    void sendDm(String slackUserId, String text);
}
