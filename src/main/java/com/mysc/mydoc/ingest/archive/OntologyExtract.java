package com.mysc.mydoc.ingest.archive;

import java.util.List;

final class OntologyExtract {
    private OntologyExtract() {}

    record CandidateExtraction(
            String title,
            List<String> summary,
            List<CandidateEntity> entities,
            List<Assertion> assertions
    ) {}

    record CandidateEntity(
            String id,
            String name,
            String type,
            List<String> aliases,
            List<String> mentionTs
    ) {}

    record LinkedExtraction(
            String title,
            List<String> summary,
            List<LinkedEntity> entities,
            List<Assertion> assertions
    ) {}

    record LinkedEntity(
            String id,
            String canonicalName,
            String type,
            String action
    ) {}

    record Assertion(
            String subjectId,
            String predicate,
            String objectId,
            String literal,
            List<String> evidenceTs,
            String statement
    ) {}
}
