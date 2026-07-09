package com.mysc.mydoc.service;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.service.KnowledgeGraphService.ScoredTriple;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 지식그래프를 위키 삼아 답하는 RAG 챗봇. 질문을 BM25로 검색해 관련 트리플만 뽑고,
 * 그 사실만 근거로 Gemini Flash가 비결정론적으로 답을 생성한다(미리 상정한 답이 아님).
 */
@Service
public class KnowledgeChatService {
    private static final int RETRIEVE_LIMIT = 12;

    private static final String SYSTEM_PROMPT = """
            당신은 사내 지식그래프를 위키처럼 참고해 답하는 어시스턴트입니다.
            아래에 주어진 '지식그래프 사실'만 근거로 답하세요. 다음을 반드시 지키세요:
            - 제공된 사실에 없는 내용은 지어내지 마세요. 추측하지 마세요.
            - 사실이 질문을 충분히 커버하지 못하면 "지식그래프에 아직 그 내용이 없어요"라고 솔직히 말하세요.
            - 답변은 한국어 해요체로, 2~5문장 이내로 간결하게.
            - 근거가 된 사실을 자연스럽게 녹여 설명하되, 번호나 원문을 그대로 나열하지는 마세요.
            """;

    private final KnowledgeGraphService knowledge;
    private final ObjectProvider<ThreadSummaryClient> chat;

    public KnowledgeChatService(KnowledgeGraphService knowledge, ObjectProvider<ThreadSummaryClient> chat) {
        this.knowledge = knowledge;
        this.chat = chat;
    }

    public record ChatSource(String subject, String predicate, String object, String kind, UUID documentId) {}
    public record ChatAnswer(String answer, List<ChatSource> sources) {}

    public ChatAnswer answer(String question) {
        if (!StringUtils.hasText(question)) {
            return new ChatAnswer("질문을 입력해 주세요.", List.of());
        }
        ThreadSummaryClient client = chat.getIfAvailable();
        if (client == null) {
            return new ChatAnswer("AI가 설정되지 않았어요 (GEMINI_API_KEY 필요).", List.of());
        }
        List<ScoredTriple> hits = knowledge.search(question, RETRIEVE_LIMIT);
        if (hits.isEmpty()) {
            return new ChatAnswer("지식그래프에 아직 관련된 내용이 없어요. Slack 논의가 더 쌓이면 답할 수 있어요.", List.of());
        }
        String answer = client.summarize(SYSTEM_PROMPT, userPrompt(question, hits));
        List<ChatSource> sources = hits.stream()
                .map(t -> new ChatSource(t.subject(), t.predicate(), t.object(), t.kind(), t.documentId()))
                .toList();
        return new ChatAnswer(answer, sources);
    }

    private String userPrompt(String question, List<ScoredTriple> hits) {
        StringBuilder facts = new StringBuilder();
        int i = 1;
        for (ScoredTriple t : hits) {
            facts.append(i++).append(". [").append(t.kind()).append("] ")
                    .append(t.subject()).append(" — ").append(t.predicate()).append(" — ").append(t.object());
            if (StringUtils.hasText(t.statement())) {
                facts.append("  (").append(t.statement()).append(")");
            }
            facts.append("\n");
        }
        return """
                질문: %s

                지식그래프 사실:
                %s
                위 사실만 근거로 질문에 답하세요.
                """.formatted(question, facts);
    }
}
