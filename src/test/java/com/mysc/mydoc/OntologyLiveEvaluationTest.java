package com.mysc.mydoc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.archive.DecisionExtract;
import com.mysc.mydoc.ingest.archive.JsonDecisionExtractPort;
import com.mysc.mydoc.ingest.archive.OntologyDecisionExtractPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_ONTOLOGY_LIVE_EVAL", matches = "true")
class OntologyLiveEvaluationTest {
    private static final Path SOURCE = Path.of("research/corpus/slack_threads_20260729.jsonl");
    private static final Path OUTPUT = Path.of("research/runs/ontology-a-b-30.jsonl");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("mydoc")
            .withUsername("mydoc")
            .withPassword("changeme")
            .withStartupTimeout(Duration.ofMinutes(4));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("mydoc.slack.decision-cron", () -> "-");
        registry.add("mydoc.slack.bot-token", () -> "");
        registry.add("mydoc.slack.app-token", () -> "");
    }

    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonDecisionExtractPort legacy;
    @Autowired OntologyDecisionExtractPort ontology;

    @Test
    void compareThirtyUnusedThreads() throws Exception {
        List<JsonNode> threads = new ArrayList<>();
        for (String line : Files.readAllLines(SOURCE)) {
            JsonNode row = mapper.readTree(line);
            if (row.path("message_count").asInt() >= 3) {
                threads.add(row);
            }
        }

        int limit = Integer.parseInt(System.getenv().getOrDefault("ONTOLOGY_EVAL_LIMIT", "30"));
        Path output = Path.of(System.getenv().getOrDefault("ONTOLOGY_EVAL_OUTPUT", OUTPUT.toString()));
        Files.createDirectories(output.getParent());
        Files.writeString(output, "");
        for (int index = 0; index < limit; index++) {
            JsonNode row = threads.get(index * threads.size() / limit);
            List<SlackMessage> messages = messages(row);
            String result = mapper.writeValueAsString(new Result(
                    row.path("channel_id").asText(),
                    row.path("thread_ts").asText(),
                    row.path("input").asText(),
                    run(() -> legacy.extract(messages), row.path("thread_ts").asText(), "slack"),
                    run(() -> ontology.extract(messages), row.path("thread_ts").asText(), "ontology-%")));
            Files.writeString(output, result + System.lineSeparator(), StandardOpenOption.APPEND);
        }
    }

    private List<SlackMessage> messages(JsonNode row) {
        String rootTs = row.path("thread_ts").asText();
        String[] lines = row.path("input").asText().split("\\R");
        List<SlackMessage> messages = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String[] parts = lines[index].split(": ", 2);
            String user = parts[0];
            String text = parts.length == 2 ? parts[1] : lines[index];
            messages.add(new SlackMessage(user, "직원 " + (index + 1), text,
                    index == 0 ? rootTs : rootTs + "-" + index));
        }
        return messages;
    }

    private Outcome run(Extraction extraction, String threadTs, String sourceKind) {
        try {
            Optional<DecisionExtract> result = extraction.run();
            return new Outcome(result.orElse(null), null, List.of());
        } catch (RuntimeException exception) {
            List<Trace> traces = jdbc.query("""
                            SELECT source_kind, ok, error, output_json
                            FROM extraction_trace
                            WHERE thread_ts = ? AND source_kind LIKE ?
                            ORDER BY created_at
                            """,
                    (rs, rowNum) -> new Trace(
                            rs.getString("source_kind"),
                            rs.getBoolean("ok"),
                            rs.getString("error"),
                            rs.getString("output_json")),
                    threadTs, sourceKind);
            return new Outcome(null, exception.getMessage(), traces);
        }
    }

    private interface Extraction {
        Optional<DecisionExtract> run();
    }

    private record Trace(String sourceKind, boolean success, String error, String response) {}
    private record Outcome(DecisionExtract extract, String error, List<Trace> traces) {}
    private record Result(String channelId, String threadTs, String input,
                          Outcome legacy, Outcome ontology) {}
}
