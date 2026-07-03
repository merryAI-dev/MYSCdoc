package com.mysc.mydoc.ingest;

import java.util.List;

public record SlackThread(String threadTs, String permalink, List<SlackMessage> messages) {}
