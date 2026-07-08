package com.mysc.mydoc.api;

import com.mysc.mydoc.service.KnowledgeGraphService;
import com.mysc.mydoc.service.KnowledgeGraphService.Graph;
import com.mysc.mydoc.service.KnowledgeGraphService.ScoredTriple;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KnowledgeController {
    private final KnowledgeGraphService knowledge;

    public KnowledgeController(KnowledgeGraphService knowledge) {
        this.knowledge = knowledge;
    }

    public record TripleListResponse(List<ScoredTriple> triples) {}

    @GetMapping("/api/knowledge/triples")
    TripleListResponse triples(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return new TripleListResponse(knowledge.search(q, limit));
    }

    @GetMapping("/api/knowledge/graph")
    Graph graph(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "60") int limit
    ) {
        return knowledge.graph(q, limit);
    }
}
