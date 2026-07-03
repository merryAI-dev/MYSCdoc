package com.mysc.mydoc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public class Provenance {
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false) private SourceType sourceType;
    @Column(name = "source_url") private String sourceUrl;
    @Column(name = "source_ref") private String sourceRef;

    protected Provenance() {}

    public Provenance(SourceType sourceType, String sourceUrl, String sourceRef) {
        this.sourceType = sourceType;
        this.sourceUrl = sourceUrl;
        this.sourceRef = sourceRef;
    }

    public static Provenance manual() { return new Provenance(SourceType.MANUAL, null, null); }

    public SourceType getSourceType() { return sourceType; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSourceRef() { return sourceRef; }
}
