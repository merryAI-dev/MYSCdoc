package com.mysc.mydoc.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class JsonThreadSummaryPort implements ThreadSummaryPort {
    private static final String SYSTEM_PROMPT = """
            당신은 사내 문서 플랫폼의 기록 담당자입니다. Slack 스레드를 읽고, 나중에 다른 팀원이나
            AI가 참고할 수 있는 결정 기록 문서를 만듭니다. 스레드에 없는 내용을 지어내지 마세요.
            결정이 명확하지 않으면 "결정 사항"에 "명시적 결정 없음 — 논의 요약"이라고 쓰세요.
            반드시 지정된 JSON 형식으로만 응답하세요.
            """;

    private final ObjectProvider<ThreadSummaryClient> client;
    private final ObjectMapper objectMapper;

    public JsonThreadSummaryPort(ObjectProvider<ThreadSummaryClient> client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public ThreadSummary summarize(List<SlackMessage> messages) {
        ThreadSummaryClient summaryClient = client.getIfAvailable();
        if (summaryClient == null) {
            throw new ValidationException("thread summary client is not configured");
        }
        String userPrompt = userPrompt(messages);
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return parse(summaryClient.summarize(SYSTEM_PROMPT, userPrompt));
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    private ThreadSummary parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new ValidationException("summary response is not JSON");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new ValidationException("summary response is not JSON");
        }
        try {
            ThreadSummary summary = objectMapper.readValue(raw.substring(start, end + 1), ThreadSummary.class);
            validate(summary);
            return summary;
        } catch (Exception exception) {
            throw new ValidationException("summary response is not JSON");
        }
    }

    private void validate(ThreadSummary summary) {
        if (summary == null || !StringUtils.hasText(summary.title()) || summary.sections() == null || summary.sections().isEmpty()) {
            throw new ValidationException("summary response is not JSON");
        }
        for (ThreadSummary.Section section : summary.sections()) {
            if (section == null
                    || !StringUtils.hasText(section.heading())
                    || section.paragraphs() == null
                    || section.paragraphs().isEmpty()
                    || section.paragraphs().stream().anyMatch(paragraph -> !StringUtils.hasText(paragraph))) {
                throw new ValidationException("summary response is not JSON");
            }
        }
    }

    private String userPrompt(List<SlackMessage> messages) {
        String thread = messages.stream()
                .map(message -> "[" + message.userName() + "] " + message.text())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return """
                다음 Slack 스레드를 결정 기록 문서로 요약해 주세요.

                <thread>
                %s
                </thread>

                다음 JSON 형식으로만 응답하세요:
                {"title": "문서 제목 (60자 이내)", "sections": [{"heading": "결정 사항", "paragraphs": ["..."]},
                {"heading": "근거", "paragraphs": ["..."]}, {"heading": "후속 작업", "paragraphs": ["..."]}]}

                규칙: 후속 작업이 없으면 해당 섹션을 빼세요. paragraphs 항목은 각각 1~3문장의 완결된 한국어 문장.
                존댓말(해요체)로 쓰세요.
                """.formatted(thread);
    }
}
