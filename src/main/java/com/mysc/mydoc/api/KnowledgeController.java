package com.mysc.mydoc.api;

import com.mysc.mydoc.service.GraphEmbeddingService;
import com.mysc.mydoc.service.GraphNormalizationService;
import com.mysc.mydoc.service.GraphEmbeddingService.Prediction;
import com.mysc.mydoc.service.GraphEmbeddingService.TrainConfig;
import com.mysc.mydoc.service.GraphEmbeddingService.TrainResult;
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
    private final GraphEmbeddingService embedding;
    private final GraphNormalizationService normalization;

    public KnowledgeController(KnowledgeGraphService knowledge, KnowledgeChatService chat,
                              GraphEmbeddingService embedding, GraphNormalizationService normalization) {
        this.knowledge = knowledge;
        this.chat = chat;
        this.embedding = embedding;
        this.normalization = normalization;
    }

    public record TripleListResponse(List<ScoredTriple> triples) {}
    public record ChatRequest(String question) {}
    public record PredictionListResponse(List<Prediction> predictions) {}

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

    /**
     * 기존 트리플에 최신 정규화 규칙(개체·괄호별명·서술어 통제 어휘)을 다시 적용한다 — LLM 없이 토큰 0.
     * 규칙을 바꾼 뒤 재추출(Gemini 재호출) 대신 이걸 돌리면 그래프가 새 규칙으로 수렴한다.
     */
    @PostMapping("/api/knowledge/normalize")
    GraphNormalizationService.NormalizeResult normalize() {
        return normalization.normalizeExisting();
    }

    /**
     * 지식그래프 임베딩(TransE)을 학습하고 그로킹 곡선을 돌려준다 (Gemini 안 씀 = 토큰 0원).
     * weightDecay(λ)를 낮출수록 train loss는 일찍 떨어지는데 test 성능은 뒤늦게 오르는 그로킹이 커진다.
     */
    @PostMapping("/api/knowledge/embeddings/train")
    TrainResult trainEmbeddings(
            @RequestParam(defaultValue = "64") int dim,
            @RequestParam(defaultValue = "400") int epochs,
            @RequestParam(defaultValue = "0.05") double lr,
            @RequestParam(defaultValue = "0.0001") double weightDecay,
            @RequestParam(defaultValue = "1.0") double margin,
            @RequestParam(defaultValue = "1.0") double initScale,
            @RequestParam(defaultValue = "0.1") double testRatio
    ) {
        return embedding.train(new TrainConfig(dim, epochs, lr, weightDecay, margin, initScale, testRatio, 42L));
    }

    /** 특정 엔티티에서 나가는, 명시 안 된 그럴듯한 관계를 예측(추론) — 저장된 사실은 제외한다. */
    @GetMapping("/api/knowledge/predict")
    PredictionListResponse predict(
            @RequestParam String entity,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return new PredictionListResponse(embedding.predict(entity, limit));
    }
}
