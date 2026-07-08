package com.mysc.mydoc.ingest.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.ThreadSummaryClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 회의론자 검증 에이전트 (실제 Gemini). 추출 에이전트가 뽑은 결과를 원문 스레드와 대조해,
 * 스레드에 근거 없는 내용(환각)이나 기록 가치 없는 오탐이면 반려한다.
 * 검증 호출 자체가 실패하면 fail-open(승인)한다 — 검증 삐끗이 정상 추출의 데이터 손실로 이어지면 안 되므로.
 */
@Service
public class JsonDecisionVerifyPort implements DecisionVerifyPort {
    private static final Logger log = LoggerFactory.getLogger(JsonDecisionVerifyPort.class);

    private static final String SYSTEM_PROMPT = """
            당신은 사내 지식 플랫폼의 깐깐한 검증 담당자입니다. 다른 에이전트가 Slack 스레드에서 뽑아낸
            '기록 후보'를 원문과 한 줄씩 대조해, 아래 중 하나라도 해당하면 반려(approved=false)합니다:
              - 원문에 근거가 없는 내용을 지어냈다(환각).
              - 사실은 단순 정보 공유·추천·잡담·일정 조율인데 결정/지식으로 잘못 잡았다(오탐).
              - decision이 실제로는 확정된 결정이 아니라 누군가의 제안·의견에 불과하다.
            모두 통과하면 승인(approved=true)합니다. 애매하면 반려 쪽으로 기웁니다.
            반드시 코드펜스 없이 순수 JSON 하나만 출력하세요: {"approved": true 또는 false, "reason": "한 문장"}
            """;

    private final ObjectProvider<ThreadSummaryClient> client;
    private final ObjectMapper objectMapper;

    public JsonDecisionVerifyPort(ObjectProvider<ThreadSummaryClient> client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public Verdict verify(List<SlackMessage> messages, DecisionExtract extract) {
        ThreadSummaryClient summaryClient = client.getIfAvailable();
        if (summaryClient == null) {
            return new Verdict(true, "verifier not configured");
        }
        try {
            String raw = summaryClient.summarize(SYSTEM_PROMPT, userPrompt(messages, extract));
            return parse(raw);
        } catch (RuntimeException exception) {
            // fail-open: 검증 호출 실패는 정상 추출을 버리지 않는다.
            log.warn("Decision verify call failed, approving by default: {}", exception.getMessage());
            return new Verdict(true, "verify failed, approved by default");
        }
    }

    private Verdict parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new Verdict(true, "empty verify response, approved by default");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            return new Verdict(true, "non-JSON verify response, approved by default");
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            boolean approved = node.path("approved").asBoolean(true); // 필드 누락 시 승인(fail-open)
            String reason = node.path("reason").asText("");
            return new Verdict(approved, reason);
        } catch (Exception exception) {
            return new Verdict(true, "unparseable verify response, approved by default");
        }
    }

    private String userPrompt(List<SlackMessage> messages, DecisionExtract extract) {
        String thread = messages.stream()
                .map(message -> "[" + message.userName() + "] " + message.text())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        String extracted;
        try {
            extracted = objectMapper.writeValueAsString(extract);
        } catch (Exception exception) {
            extracted = "{}";
        }
        return """
                아래 <thread>는 원문이고, <extracted>는 다른 에이전트가 이 스레드에서 뽑아낸 기록 후보입니다.
                extracted의 모든 decision과 tacitKnowledge가 thread에 실제로 근거가 있는지, 기록 가치가 있는지
                검증하고 승인/반려를 판정하세요.

                <thread>
                %s
                </thread>

                <extracted>
                %s
                </extracted>

                {"approved": true 또는 false, "reason": "한 문장으로 근거"}
                """.formatted(thread, extracted);
    }
}
