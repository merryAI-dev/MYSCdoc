package com.mysc.mydoc.ingest.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.ingest.SlackMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Slack 스레드 → {@link DecisionExtract}. 프롬프트는 Gemini 2.5 Flash 기준
 * PTCF(Persona-Task-Context-Format) 구조 + 부정 제약(negative constraint) +
 * 스키마 내장(schema-in-prompt) + 1-shot 예시로 설계했다 (근거는 클래스 하단 주석).
 */
@Service
public class JsonDecisionExtractPort implements DecisionExtractPort {
    // Gemini는 파싱 실패 시 코드펜스를 다시 붙이는 등 같은 실수를 반복하는 경향이 있어
    // JsonThreadSummaryPort와 동일하게 짧은 재시도만 둔다 (긴 재시도는 비용 대비 효과가 없음).
    private static final int MAX_JSON_PARSE_ATTEMPTS = 2;

    // ── Persona + 부정 제약 ──────────────────────────────────────────────
    // "사내 문서 플랫폼의 기록 담당자"라는 페르소나는 JsonThreadSummaryPort와 동일하게 맞춰
    // 같은 톤(해요체, 결정기록 문서 문체)의 산출물이 나오게 한다.
    private static final String SYSTEM_PROMPT = """
            당신은 사내 지식 플랫폼 mydoc의 기록 담당자(Technical Writer)입니다.
            Slack 스레드 하나를 읽고 세 가지를 함께 추출합니다: (1) 스레드 요약,
            (2) 팀이 실제로 내린 의사결정, (3) 대화 속에 암묵적으로 드러난 조직의 지식
            (정책, 제약, 관행, 함정, 리스크 등 — 아무도 문서화하지 않았지만 다음에 같은
            상황이 오면 반드시 알아야 할 것).

            반드시 지켜야 할 규칙:
            - 스레드에 실제로 없는 내용을 추론하거나 지어내지 마세요. 확실하지 않으면 그 필드를 비우거나 배열에서 빼세요.
            - 발화자 이름을 문장에 그대로 옮기지 말고, "팀은", "담당자는"처럼 자연스러운 3인칭으로 정리하세요.
            - 같은 개념을 가리키는 표현(그것, 이거, 저 기능, 위 이슈 등)은 스레드 맥락에서 실제 지칭 대상으로
              바꿔 쓰세요(대명사를 그대로 남기지 마세요). 예: "그거 고쳐야 해요" → "결제 재시도 로직을 고쳐야 해요".
            - 같은 대상은 스레드 전체에서 하나의 이름으로 통일하세요(1turn에서 "결제 API", 3turn에서
              "그 API"로 불렸다면 둘 다 "결제 API"로 통일).
            - 존댓말(해요체)로 쓰세요. 문장은 완결된 한국어 문장으로, 1~2문장 이내로 간결하게.
            - 반드시 지정된 JSON 형식으로만, 코드펜스나 설명 없이 순수 JSON 하나만 출력하세요.
            - 출력하기 전에 스스로 JSON 문법이 유효한지, 모든 필수 필드가 있는지 확인하세요.
            """;

    private final ObjectProvider<com.mysc.mydoc.ingest.ThreadSummaryClient> client;
    private final ObjectMapper objectMapper;

    public JsonDecisionExtractPort(
            ObjectProvider<com.mysc.mydoc.ingest.ThreadSummaryClient> client,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DecisionExtract> extract(List<SlackMessage> messages) {
        com.mysc.mydoc.ingest.ThreadSummaryClient summaryClient = client.getIfAvailable();
        if (summaryClient == null) {
            throw new ValidationException("thread summary client is not configured");
        }
        String userPrompt = userPrompt(messages);
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_JSON_PARSE_ATTEMPTS; attempt++) {
            try {
                return parse(summaryClient.summarize(SYSTEM_PROMPT, userPrompt));
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    private Optional<DecisionExtract> parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new ValidationException("decision response is not JSON");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new ValidationException("decision response is not JSON");
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            if (!node.path("worthRecording").asBoolean(false)) {
                return Optional.empty();
            }
            DecisionExtract extract = objectMapper.treeToValue(node, DecisionExtract.class);
            validate(extract);
            return Optional.of(extract);
        } catch (ValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ValidationException("decision response is not JSON");
        }
    }

    private void validate(DecisionExtract extract) {
        if (extract == null
                || !StringUtils.hasText(extract.title())
                || extract.summary() == null || extract.summary().isEmpty()) {
            throw new ValidationException("decision response is not JSON");
        }
        for (String line : extract.summary()) {
            if (!StringUtils.hasText(line)) {
                throw new ValidationException("decision response is not JSON");
            }
        }
        // 결정과 암묵지 중 최소 하나는 있어야 기록 가치가 있다 (결정 없는 해결/지식 스레드도 허용).
        boolean hasDecision = extract.decisionPoints() != null && !extract.decisionPoints().isEmpty();
        boolean hasKnowledge = extract.tacitKnowledge() != null && !extract.tacitKnowledge().isEmpty();
        if (!hasDecision && !hasKnowledge) {
            throw new ValidationException("decision response is not JSON");
        }
        if (extract.decisionPoints() != null) {
            for (DecisionExtract.DecisionPoint point : extract.decisionPoints()) {
                if (point == null || !StringUtils.hasText(point.decision())) {
                    throw new ValidationException("decision response is not JSON");
                }
            }
        }
        if (extract.tacitKnowledge() != null) {
            for (DecisionExtract.TacitKnowledge item : extract.tacitKnowledge()) {
                if (item == null || !StringUtils.hasText(item.statement())) {
                    throw new ValidationException("decision response is not JSON");
                }
                if (item.triples() != null) {
                    for (DecisionExtract.Triple triple : item.triples()) {
                        if (triple == null
                                || !StringUtils.hasText(triple.subject())
                                || !StringUtils.hasText(triple.predicate())
                                || !StringUtils.hasText(triple.object())) {
                            throw new ValidationException("decision response is not JSON");
                        }
                    }
                }
            }
        }
    }

    // ── Task + Context + Format(스키마 내장) + 1-shot 예시 ────────────────
    private String userPrompt(List<SlackMessage> messages) {
        String thread = messages.stream()
                .map(message -> "[" + message.userName() + "] " + message.text())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return """
                다음 Slack 스레드를 분석해 주세요.

                <thread>
                %s
                </thread>

                ## 1단계: 기록 가치 판별
                이 스레드가 조직의 지식으로 "기록할 가치"가 있나요? 아래 둘 중 하나라도 해당하면 있습니다:
                  (A) 팀이 앞으로의 행동/기준을 확정한 의사결정 ("~하기로 했다", "~로 정했다", "다음부터 ~하자")
                  (B) 다음에 같은 상황에서 재사용할 지식 — 문제의 근본원인과 해결책("원인은 ~였고 ~로 고쳤다"),
                      정책·제약·관행·함정처럼 나중에 반드시 알아야 할 것

                반대로, 아래는 기록하지 마세요. 정확히 {"worthRecording": false} 만 출력하고 멈추세요:
                  - 단순 정보/링크/뉴스 공유 (예: "이 글 공유합니다", "이런 기사 있네요")
                  - 개인 감상이나 추천 (예: "이 모델 써보시길 추천드립니다", "재밌었어요")
                  - 잡담·인사·리액션, 일정 조율 (예: "수요일에 뵐게요"), 결론 없는 질문-답변
                  - 마감/프로모션 안내처럼 남에게 권하는 내용 (팀의 확정 결정이 아님)

                ## 2단계 (기록 가치가 있을 때만): 아래 스키마로 추출
                {
                  "worthRecording": true,
                  "title": "문서 제목, 60자 이내, 핵심을 요약하는 명사구",
                  "summary": ["스레드 흐름을 시간 순서대로 요약한 문장 하나 (아래 '요약 작성 기준' 참고)"],
                  "decisionPoints": [
                    {
                      "decision": "확정된 결정 사항 한 문장",
                      "rationale": "그렇게 결정한 이유나 근거. 스레드에 없으면 빈 문자열.",
                      "alternatives": ["검토했지만 채택하지 않은 대안. 없으면 빈 배열."],
                      "owner": "이 결정의 실행/책임 주체로 스레드에서 지목된 사람이나 팀. 없으면 빈 문자열.",
                      "condition": "이 결정이 적용되는 조건이나 예외(예: '공휴일이면 예외'). 없으면 빈 문자열."
                    }
                  ],
                  "tacitKnowledge": [
                    {
                      "kind": "policy | constraint | workaround | gotcha | convention | risk 중 하나",
                      "statement": "다음에 같은 상황이 오면 알아야 할 지식을 한 문장으로.",
                      "triples": [
                        {"subject": "개체(시스템/정책/사람/팀 등 스레드에서 실제로 지칭된 이름)",
                         "predicate": "관계(동사구, 예: '차단한다', '담당한다', '전제로 한다')",
                         "object": "개체 또는 값"}
                      ]
                    }
                  ]
                }

                ## 요약 작성 기준 (summary)
                이 요약은 조직의 아카이브 기록입니다 — 나중에 원문 스레드 없이 이 요약 문장들만 읽어도
                "무엇이 문제/주제였고, 누가 무엇을 말했고, 어떻게 마무리됐는지"가 파악돼야 합니다. 부실하면
                아카이브로서 의미가 없으니 다음을 지키세요:
                - 2~5개의 완결된 한국어 문장, 시간 순서대로.
                - 발화자를 빠짐없이 반영하되 이름을 그대로 옮기지 말고 역할로 쓰세요(예: "요청한 사람은", "담당자는").
                - 숫자·기한·환경명·에러 메시지처럼 나중에 검색될 구체적 사실은 뭉뚱그리지 말고 그대로 남기세요.
                - "논의가 있었다"처럼 내용 없이 뭉개는 문장 대신, 실제로 오간 내용을 구체적으로 쓰세요.

                ## 규칙
                - decisionPoints와 tacitKnowledge 중 **최소 하나는 비어있지 않아야** 합니다.
                  · 앞으로의 결정이 있으면 decisionPoints를 채우세요.
                  · 결정은 없지만 근본원인·해결·정책 같은 재사용 지식만 있으면 decisionPoints는 [] 로 두고
                    tacitKnowledge만 채우세요 (해결 기록은 대개 이 경우입니다).
                - 억지로 만들지 마세요. 스레드에 없는 내용을 추론·창작하면 안 됩니다.
                - triples의 subject/object는 스레드 전체에서 같은 대상을 가리키면 항상 같은 표현을 쓰세요
                  (엔티티 일관성). 예: "결제 API"를 한 번은 "그 API", 한 번은 "결제 서버"라고 부르지 마세요.
                - 한 문장에 트리플이 여러 개 나올 수 있습니다. 각각 별도 트리플로 쪼개세요.

                ## 예시 1 — 의사결정 (형식 참고용, 실제 스레드와 무관)
                입력: "[민지] 결제 재시도 로직 때문에 야간에 알림이 계속 와요"
                "[태호] 3회 실패하면 재시도 끄는 걸로 하죠. 5회는 너무 많아요" "[민지] 좋아요, 3회로 정하고 제가 반영할게요"
                출력:
                {"worthRecording": true, "title": "결제 재시도 3회 제한 확정",
                 "summary": ["결제 재시도 로직이 야간에 반복 알림을 유발하는 문제가 논의됐어요.",
                             "재시도 횟수를 3회로 제한하기로 확정했고, 민지가 반영을 맡기로 했어요."],
                 "decisionPoints": [{"decision": "결제 재시도는 3회 실패 시 중단해요.",
                   "rationale": "5회는 과도하다는 판단이 있었어요.", "alternatives": ["5회 재시도"],
                   "owner": "민지", "condition": ""}],
                 "tacitKnowledge": [{"kind": "constraint",
                   "statement": "결제 재시도 로직은 실패 횟수 상한이 없으면 야간 알림 과다를 유발해요.",
                   "triples": [{"subject": "결제 재시도 로직", "predicate": "유발한다", "object": "야간 알림 과다"}]}]}

                ## 예시 2 — 결정은 없지만 해결/지식만 있는 경우 (decisionPoints는 빈 배열)
                입력: "[지훈] 사용자 목록이 렌더링될 때 컨설턴트 메일이 문자열이 아니면 화면이 터져요"
                "[지훈] 해결됐습니다. 원인은 메일 필드가 null일 때 처리가 누락된 거였고, null 가드를 넣었어요"
                출력:
                {"worthRecording": true, "title": "사용자 목록 렌더링 시 메일 null 처리 누락",
                 "summary": ["사용자 목록 렌더링에서 컨설턴트 메일이 문자열이 아니면 화면이 깨지는 문제가 있었어요.",
                             "원인은 메일 필드가 null일 때 처리 누락이었고, null 가드를 추가해 해결했어요."],
                 "decisionPoints": [],
                 "tacitKnowledge": [{"kind": "gotcha",
                   "statement": "사용자 목록 렌더링은 컨설턴트 메일이 null이면 화면이 깨져요 — null 가드가 필요해요.",
                   "triples": [{"subject": "사용자 목록 렌더링", "predicate": "깨진다", "object": "메일이 null일 때"},
                               {"subject": "메일 null 가드", "predicate": "해결한다", "object": "사용자 목록 렌더링 오류"}]}]}
                """.formatted(thread);
    }
}
