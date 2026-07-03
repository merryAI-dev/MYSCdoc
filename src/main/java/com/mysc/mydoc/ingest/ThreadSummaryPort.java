package com.mysc.mydoc.ingest;

import java.util.List;

public interface ThreadSummaryPort {
    ThreadSummary summarize(List<SlackMessage> messages);
}
