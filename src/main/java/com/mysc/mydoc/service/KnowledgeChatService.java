package com.mysc.mydoc.service;

import com.mysc.mydoc.ai.KnowledgeAnswerClient;
import com.mysc.mydoc.service.KnowledgeGraphService.RetrievalMode;
import com.mysc.mydoc.service.KnowledgeGraphService.ScoredTriple;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 지식그래프를 위키 삼아 답하는 RAG 챗봇. 질문을 BM25로 검색해 시드 트리플을 찾고,
 * 그 시드의 주어/목적어가 등장하는 1홉 이웃 트리플까지 함께 근거로 삼는다 — 질문과
 * 어휘가 안 겹쳐도 그래프상 연결된 지식을 놓치지 않는다. MULTIHOP 모드는 개체를 따라
 * 여러 홉을 넓히고, 간접 근거마다 경유한 기록을 프롬프트에 밝힌다. 모델은 그 사실만
 * 근거로 답하거나, 결정적 근거가 없으면 거절한다.
 */
@Service
public class KnowledgeChatService {
    private static final int SEED_LIMIT = 6;
    private static final int RETRIEVE_LIMIT = 12;

    private static final String SYSTEM_PROMPT = """
            당신은 사내 업무 기록을 바탕으로 답하는 한국어 챗봇입니다.
            질문에 바로 답하고, 제공된 기록 밖의 회사명·인명·기관명·날짜·수치는 추측하지 마세요.
            답할 근거가 있으면 마지막에 `(근거: C1, C2)`처럼 사용한 기록 ID를 적으세요.
            직접 근거가 없으면 비슷한 대상을 같은 것으로 취급하지 말고 `(근거 부족)`으로 끝내세요.
            확정, 예정, 논의, 조건부 상태를 구분해 자연스러운 해요체로 답하세요.
            `[경로=C2→]`가 붙은 기록은 앞 기록을 거쳐 연결된 간접 근거입니다. 여러 기록을 이어 답했다면
            `(근거: C2→C5)`처럼 연결 순서대로 적고, 간접 근거만으로 단정하지 마세요.
            """;

    private final KnowledgeGraphService knowledge;
    private final KnowledgeAnswerClient chat;
    private final QueryRewriteClient rewriter;

    public KnowledgeChatService(KnowledgeGraphService knowledge, KnowledgeAnswerClient chat,
                                QueryRewriteClient rewriter) {
        this.knowledge = knowledge;
        this.chat = chat;
        this.rewriter = rewriter;
    }

    /** hop: 시드=0, 멀티홉 검색에서 개체를 건너 닿은 근거는 1 이상. */
    public record ChatSource(String subject, String predicate, String object, String kind, UUID documentId,
                             int hop) {}
    /** rewrittenQuery: RL 재작성이 실제로 쓰였을 때만 값이 있다 — A/B 육안 비교용으로 노출. */
    public record ChatAnswer(String answer, List<ChatSource> sources, String rewrittenQuery,
                             RetrievalMode retrievalMode) {}

    public ChatAnswer answer(String question) {
        return answer(question, false, RetrievalMode.BM25);
    }

    public ChatAnswer answer(String question, boolean rewrite) {
        return answer(question, rewrite, RetrievalMode.BM25);
    }

    public ChatAnswer answer(String question, boolean rewrite, RetrievalMode requestedMode) {
        RetrievalMode mode = requestedMode == null ? RetrievalMode.BM25 : requestedMode;
        if (!StringUtils.hasText(question)) {
            return new ChatAnswer("질문을 입력해 주세요.", List.of(), null, mode);
        }
        // RL 재작성은 '검색어'에만 쓴다 — 답변 생성 프롬프트에는 원 질문을 유지한다.
        String rewritten = rewrite ? rewriter.rewrite(question).orElse(null) : null;
        String searchQuery = rewritten != null ? rewritten : question;
        List<ScoredTriple> hits = knowledge.searchContext(searchQuery, SEED_LIMIT, RETRIEVE_LIMIT, mode);
        if (hits.isEmpty()) {
            return new ChatAnswer("지식그래프에 아직 관련된 내용이 없어요. Slack 논의가 더 쌓이면 답할 수 있어요.",
                    List.of(), rewritten, mode);
        }
        String answer = cleanAnswer(chat.answer(SYSTEM_PROMPT, userPrompt(question, hits)));
        List<ChatSource> sources = hits.stream()
                .map(t -> new ChatSource(t.subject(), t.predicate(), t.object(), t.kind(), t.documentId(),
                        t.hop()))
                .toList();
        return new ChatAnswer(answer, sources, rewritten, mode);
    }

    private String userPrompt(String question, List<ScoredTriple> hits) {
        StringBuilder facts = new StringBuilder();
        Map<UUID, Integer> labels = new HashMap<>();
        int i = 1;
        for (ScoredTriple t : hits) {
            labels.put(t.id(), i);
            // 추출 계약상 decision은 확정된 의사결정만 저장한다. 나머지는 weight를 역산하지 않고 미분류로 둔다.
            String grade = "decision".equals(t.kind()) ? "확정" : "미분류";
            facts.append("[C").append(i++).append("] [상태=").append(grade).append("] ");
            // 멀티홉 이웃은 경유한 기록 번호를 밝힌다 — via는 BFS 순서상 항상 앞서 번호가 매겨져 있다.
            Integer via = t.via() == null ? null : labels.get(t.via());
            if (via != null) {
                facts.append("[경로=C").append(via).append("→] ");
            }
            facts.append("[").append(t.kind()).append("] ")
                    .append(t.subject()).append(" — ").append(t.predicate()).append(" — ").append(t.object());
            if (StringUtils.hasText(t.statement())) {
                facts.append("  (").append(t.statement()).append(")");
            }
            facts.append("\n");
        }
        return "업무 기록:\n%s\n\n질문: %s".formatted(facts.toString().strip(), question);
    }

    private String cleanAnswer(String answer) {
        return answer.replace("<answer>", "").replace("</answer>", "")
                .replace("<abstain>", "").replace("</abstain>", "").strip();
    }
}
