package com.mysc.mydoc.api;

import com.mysc.mydoc.service.KnowledgeChatService;
import com.mysc.mydoc.service.KnowledgeChatService.ChatAnswer;
import com.mysc.mydoc.service.KnowledgeGraphService;
import com.mysc.mydoc.service.KnowledgeGraphService.Graph;
import com.mysc.mydoc.service.KnowledgeGraphService.ScoredTriple;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KnowledgeController {
    private final KnowledgeGraphService knowledge;
    private final KnowledgeChatService chat;

    public KnowledgeController(KnowledgeGraphService knowledge, KnowledgeChatService chat) {
        this.knowledge = knowledge;
        this.chat = chat;
    }

    public record TripleListResponse(List<ScoredTriple> triples) {}
    public record ChatRequest(String question) {}

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

    /** 지식그래프를 위키 삼아 답하는 RAG 챗봇 (Gemini Flash). */
    @PostMapping("/api/knowledge/chat")
    ChatAnswer chat(@RequestBody ChatRequest request) {
        return chat.answer(request.question());
    }
}
