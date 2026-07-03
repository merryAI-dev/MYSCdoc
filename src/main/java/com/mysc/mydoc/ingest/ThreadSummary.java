package com.mysc.mydoc.ingest;

import java.util.List;

public record ThreadSummary(String title, List<Section> sections) {
    public record Section(String heading, List<String> paragraphs) {}
}
