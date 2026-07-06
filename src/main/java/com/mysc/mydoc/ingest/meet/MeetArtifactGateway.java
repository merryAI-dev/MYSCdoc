package com.mysc.mydoc.ingest.meet;

public interface MeetArtifactGateway {
    MeetArtifact get(String resourceName, ArtifactKind kind);
}
