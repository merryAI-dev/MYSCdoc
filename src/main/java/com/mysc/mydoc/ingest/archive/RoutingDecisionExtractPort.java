package com.mysc.mydoc.ingest.archive;

import com.mysc.mydoc.ingest.SlackMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class RoutingDecisionExtractPort implements DecisionExtractPort {
    private final JsonDecisionExtractPort legacy;
    private final OntologyDecisionExtractPort ontology;
    private final ExtractionMode mode;

    public RoutingDecisionExtractPort(
            JsonDecisionExtractPort legacy,
            OntologyDecisionExtractPort ontology,
            @Value("${mydoc.slack.extraction-mode:legacy}") String mode
    ) {
        this.legacy = legacy;
        this.ontology = ontology;
        this.mode = ExtractionMode.parse(mode);
    }

    @Override
    public Optional<DecisionExtract> extract(List<SlackMessage> messages) {
        return extract(messages, mode);
    }

    public Optional<DecisionExtract> extract(List<SlackMessage> messages, ExtractionMode selected) {
        return selected == ExtractionMode.ONTOLOGY
                ? ontology.extract(messages)
                : legacy.extract(messages);
    }
}
