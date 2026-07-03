package com.mysc.mydoc.repository;

import java.util.UUID;

public interface ChunkSearchRow {
    UUID getId();
    UUID getDocumentId();
    String getHeadingPath();
    String getText();
}
