package com.mysc.mydoc.ingest;

public record SlackMessage(String userId, String userName, String text, String ts) {}
