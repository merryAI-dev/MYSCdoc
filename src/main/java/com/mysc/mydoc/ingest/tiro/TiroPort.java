package com.mysc.mydoc.ingest.tiro;

public interface TiroPort {
    java.util.List<TiroNoteSummary> listNotes(String keyword);

    TiroNoteSummary getNote(String noteGuid);

    String getTranscriptText(String noteGuid);
}
