package com.mysc.mydoc.ingest.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.service.EntityNormalizer;
import com.mysc.mydoc.service.PredicateVocabulary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OntologyDecisionExtractPort implements DecisionExtractPort {
    static final Set<String> ENTITY_TYPES = Set.of(
            "Person", "Team", "Organization", "Startup", "Program", "Project",
            "Event", "Document", "Decision", "Task", "Concept");
    private static final Set<String> ACTIONS = Set.of("link", "create", "discard");
    private static final Pattern RAW_SLACK_ID = Pattern.compile("^[USW][A-Z0-9]{8,}$");
    private static final Pattern GENERATED_EMPLOYEE = Pattern.compile("^직원\\s*\\d+$");

    private static final String CANDIDATE_SYSTEM = """
            당신은 Slack 스레드 전체에서 조직 지식그래프 후보를 추출하는 정보 추출기입니다.
            root와 모든 댓글을 하나의 문서로 읽으세요. 결정뿐 아니라 사람, 팀, 기업, 사업,
            프로젝트, 행사, 문서, 업무 사이의 명시적 사실도 추출하세요.
            관계마다 실제 근거가 있는 메시지 ts를 붙이고, 추론하거나 일반상식을 보태지 마세요.
            entity id는 이 응답 안에서만 쓰는 E1, E2 형식입니다.
            """;

    private static final String LINK_SYSTEM = """
            당신은 후보 지식을 기존 조직 그래프에 연결하고 검증하는 담당자입니다.
            각 entity를 기존 후보 중 같은 실체에 link하거나, 없으면 create하거나, 실체가 아니면 discard하세요.
            link일 때 canonicalName은 반드시 제공된 기존 후보 중 하나여야 합니다.
            assertion은 원문에 명시된 사실이고 근거 ts가 정확할 때만 남기세요.
            predicate는 제공된 표준 관계 중 하나만 쓰고 자유형 관계를 만들지 마세요.
            """;

    private static final String CANDIDATE_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "title":{"type":"string"},
                "summary":{"type":"array","items":{"type":"string"}},
                "entities":{"type":"array","items":{"type":"object","properties":{
                  "id":{"type":"string"},"name":{"type":"string"},"type":{"type":"string","enum":%s},
                  "aliases":{"type":"array","items":{"type":"string"}},
                  "mentionTs":{"type":"array","items":{"type":"string"}}
                },"required":["id","name","type","aliases","mentionTs"]}},
                "assertions":{"type":"array","items":{"type":"object","properties":{
                  "subjectId":{"type":"string"},"predicate":{"type":"string","enum":%s},
                  "objectId":{"type":"string"},"literal":{"type":"string"},
                  "evidenceTs":{"type":"array","items":{"type":"string"}},"statement":{"type":"string"}
                },"required":["subjectId","predicate","objectId","literal","evidenceTs","statement"]}}
              },
              "required":["title","summary","entities","assertions"]
            }
            """;

    private static final String LINKED_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "title":{"type":"string"},
                "summary":{"type":"array","items":{"type":"string"}},
                "entities":{"type":"array","items":{"type":"object","properties":{
                  "id":{"type":"string"},"canonicalName":{"type":"string"},
                  "type":{"type":"string","enum":%s},"action":{"type":"string","enum":["link","create","discard"]}
                },"required":["id","canonicalName","type","action"]}},
                "assertions":{"type":"array","items":{"type":"object","properties":{
                  "subjectId":{"type":"string"},"predicate":{"type":"string","enum":%s},
                  "objectId":{"type":"string"},"literal":{"type":"string"},
                  "evidenceTs":{"type":"array","items":{"type":"string"}},"statement":{"type":"string"}
                },"required":["subjectId","predicate","objectId","literal","evidenceTs","statement"]}}
              },
              "required":["title","summary","entities","assertions"]
            }
            """;

    private final ObjectProvider<ThreadSummaryClient> client;
    private final ObjectMapper objectMapper;
    private final OntologyEntityLinker linker;
    private final EntityNormalizer normalizer;
    private final ExtractionTraceRecorder trace;
    private final JsonNode candidateSchema;
    private final JsonNode linkedSchema;

    public OntologyDecisionExtractPort(
            ObjectProvider<ThreadSummaryClient> client,
            ObjectMapper objectMapper,
            OntologyEntityLinker linker,
            EntityNormalizer normalizer,
            ExtractionTraceRecorder trace
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.linker = linker;
        this.normalizer = normalizer;
        this.trace = trace;
        this.candidateSchema = schema(CANDIDATE_SCHEMA);
        this.linkedSchema = schema(LINKED_SCHEMA);
    }

    @Override
    public Optional<DecisionExtract> extract(List<SlackMessage> messages) {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        ThreadSummaryClient llm = client.getIfAvailable();
        if (llm == null) {
            throw new ValidationException("thread summary client is not configured");
        }
        Set<String> validTs = messages.stream().map(SlackMessage::ts).collect(Collectors.toSet());
        String thread = thread(messages);
        String threadTs = messages.get(0).ts();

        OntologyExtract.CandidateExtraction candidates = call(
                llm, "ontology-candidate", threadTs, CANDIDATE_SYSTEM,
                candidatePrompt(thread), candidateSchema, OntologyExtract.CandidateExtraction.class);
        validateCandidates(candidates, validTs);

        Map<String, List<String>> existing = linker.candidates(candidates.entities());
        String linkedPrompt = linkedPrompt(thread, candidates, existing);
        OntologyExtract.LinkedExtraction linked = call(
                llm, "ontology-link", threadTs, LINK_SYSTEM,
                linkedPrompt, linkedSchema, OntologyExtract.LinkedExtraction.class);
        Set<String> candidateIds = candidates.entities().stream()
                .map(OntologyExtract.CandidateEntity::id)
                .collect(Collectors.toSet());
        Map<String, String> candidateTypes = candidates.entities().stream()
                .collect(Collectors.toMap(
                        OntologyExtract.CandidateEntity::id,
                        OntologyExtract.CandidateEntity::type));
        return project(linked, existing, candidateIds, candidateTypes, validTs);
    }

    private <T> T call(ThreadSummaryClient llm, String sourceKind, String threadTs,
                       String system, String prompt, JsonNode schema, Class<T> type) {
        String raw = null;
        try {
            raw = llm.summarizeStructured(system, prompt, schema);
            T parsed = objectMapper.readValue(json(raw), type);
            trace.record(sourceKind, null, threadTs, system, prompt, raw, true, null);
            return parsed;
        } catch (Exception exception) {
            trace.record(sourceKind, null, threadTs, system, prompt, raw, false, exception.getMessage());
            throw exception instanceof ValidationException validation
                    ? validation : new ValidationException("ontology response is not valid JSON");
        }
    }

    private void validateCandidates(OntologyExtract.CandidateExtraction extract, Set<String> validTs) {
        if (extract == null || extract.entities() == null || extract.assertions() == null) {
            throw new ValidationException("ontology candidates are incomplete");
        }
        Set<String> ids = new HashSet<>();
        for (OntologyExtract.CandidateEntity entity : extract.entities()) {
            if (entity == null || !StringUtils.hasText(entity.id()) || !StringUtils.hasText(entity.name())
                    || !ENTITY_TYPES.contains(entity.type()) || !ids.add(entity.id())
                    || entity.mentionTs() == null || entity.mentionTs().isEmpty()
                    || !validTs.containsAll(entity.mentionTs())) {
                throw new ValidationException("ontology entity is invalid");
            }
        }
        validateAssertions(extract.assertions(), ids, validTs);
    }

    private Optional<DecisionExtract> project(OntologyExtract.LinkedExtraction extract,
                                              Map<String, List<String>> existing,
                                              Set<String> candidateIds,
                                              Map<String, String> candidateTypes,
                                              Set<String> validTs) {
        if (extract == null || !StringUtils.hasText(extract.title())
                || extract.summary() == null || extract.summary().isEmpty()
                || extract.entities() == null || extract.assertions() == null) {
            throw new ValidationException("linked ontology is incomplete");
        }
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (OntologyExtract.LinkedEntity entity : extract.entities()) {
            if (entity == null || !StringUtils.hasText(entity.id()) || !candidateIds.contains(entity.id())
                    || !ids.add(entity.id())
                    || !ENTITY_TYPES.contains(entity.type())
                    || !entity.type().equals(candidateTypes.get(entity.id()))
                    || !ACTIONS.contains(entity.action())) {
                throw new ValidationException("linked ontology entity is invalid");
            }
            if ("discard".equals(entity.action())) {
                continue;
            }
            Optional<String> normalized = normalizer.entity(entity.canonicalName());
            if (normalized.isEmpty()) {
                continue;
            }
            String canonical = normalized.orElseThrow();
            if (isInternalName(canonical)) {
                continue;
            }
            if ("link".equals(entity.action()) && existing.getOrDefault(entity.id(), List.of()).stream()
                    .map(name -> normalizer.entity(name).orElse(name))
                    .noneMatch(canonical::equals)) {
                throw new ValidationException("linked entity is not an existing candidate");
            }
            names.put(entity.id(), canonical);
        }

        List<DecisionExtract.TacitKnowledge> facts = new ArrayList<>();
        Set<DecisionExtract.Triple> seen = new HashSet<>();
        for (OntologyExtract.Assertion assertion : extract.assertions()) {
            if (!isValidAssertion(assertion, names.keySet(), validTs)) {
                continue;
            }
            String subject = names.get(assertion.subjectId());
            String object = hasValue(assertion.objectId())
                    ? names.get(assertion.objectId())
                    : normalizer.entity(assertion.literal()).orElse(null);
            if (subject == null || object == null || isInternalName(subject) || isInternalName(object)) {
                continue;
            }
            DecisionExtract.Triple triple = new DecisionExtract.Triple(
                    subject, assertion.predicate(), object);
            if (subject.equals(object) || !seen.add(triple)) {
                continue;
            }
            String evidence = String.join(", ", assertion.evidenceTs());
            facts.add(new DecisionExtract.TacitKnowledge(
                    "fact",
                    assertion.statement() + " (근거 메시지: " + evidence + ")",
                    List.of(triple)
            ));
        }
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DecisionExtract(
                extract.title(),
                extract.summary(),
                List.of(),
                facts
        ));
    }

    private boolean isInternalName(String name) {
        return RAW_SLACK_ID.matcher(name).matches() || GENERATED_EMPLOYEE.matcher(name).matches();
    }

    private void validateAssertions(List<OntologyExtract.Assertion> assertions,
                                    Set<String> entityIds, Set<String> validTs) {
        for (OntologyExtract.Assertion assertion : assertions) {
            if (!isValidAssertion(assertion, entityIds, validTs)) {
                throw new ValidationException("ontology assertion is invalid");
            }
        }
    }

    private boolean isValidAssertion(OntologyExtract.Assertion assertion,
                                     Set<String> entityIds, Set<String> validTs) {
        if (assertion == null) {
            return false;
        }
        boolean hasObject = hasValue(assertion.objectId());
        boolean hasLiteral = hasValue(assertion.literal());
        return entityIds.contains(assertion.subjectId())
                && PredicateVocabulary.isCanonical(assertion.predicate())
                && (hasObject || hasLiteral)
                && (!hasObject || entityIds.contains(assertion.objectId()))
                && assertion.evidenceTs() != null && !assertion.evidenceTs().isEmpty()
                && validTs.containsAll(assertion.evidenceTs())
                && StringUtils.hasText(assertion.statement());
    }

    private static boolean hasValue(String value) {
        return StringUtils.hasText(value) && !"null".equalsIgnoreCase(value.strip());
    }

    private String candidatePrompt(String thread) {
        return """
                다음 Slack 스레드에서 그래프 후보를 추출하세요.

                <thread>
                %s
                </thread>

                허용 entity type: %s
                허용 predicate: %s
                object가 entity면 objectId만, 날짜·수치·짧은 상태값이면 literal만 채우세요.
                """.formatted(thread, ENTITY_TYPES, PredicateVocabulary.CANONICAL_LIST);
    }

    private String linkedPrompt(String thread, OntologyExtract.CandidateExtraction candidates,
                                Map<String, List<String>> existing) {
        try {
            return """
                    원문:
                    <thread>
                    %s
                    </thread>

                    1단계 후보:
                    %s

                    entity별 기존 그래프 후보:
                    %s

                    원문 근거와 기존 후보를 대조해 최종 entity와 assertion만 출력하세요.
                    """.formatted(thread, objectMapper.writeValueAsString(candidates),
                    objectMapper.writeValueAsString(existing));
        } catch (Exception exception) {
            throw new ValidationException("ontology linking prompt could not be built");
        }
    }

    private static String thread(List<SlackMessage> messages) {
        return messages.stream()
                .map(message -> "[ts=" + message.ts() + "][" + message.userName() + "] " + message.text())
                .collect(Collectors.joining("\n"));
    }

    private JsonNode schema(String template) {
        try {
            return objectMapper.readTree(template.formatted(
                    objectMapper.writeValueAsString(ENTITY_TYPES),
                    objectMapper.writeValueAsString(PredicateVocabulary.CANONICAL)));
        } catch (Exception exception) {
            throw new IllegalStateException("ontology schema is invalid", exception);
        }
    }

    private static String json(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new ValidationException("ontology response is empty");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new ValidationException("ontology response is not JSON");
        }
        return raw.substring(start, end + 1);
    }
}
