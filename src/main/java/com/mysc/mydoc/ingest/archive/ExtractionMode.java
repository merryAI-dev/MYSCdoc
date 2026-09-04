package com.mysc.mydoc.ingest.archive;

public enum ExtractionMode {
    LEGACY,
    ONTOLOGY;

    public static ExtractionMode parse(String value) {
        return value == null ? LEGACY : valueOf(value.strip().toUpperCase());
    }
}
