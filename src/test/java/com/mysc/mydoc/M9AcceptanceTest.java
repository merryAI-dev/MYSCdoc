package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.ingest.archive.DecisionExtractionJob;
import com.mysc.mydoc.ingest.archive.SlackArchiveService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M9AcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("mydoc")
            .withUsername("mydoc")
            .withPassword("changeme")
            .withStartupTimeout(Duration.ofMinutes(4));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("mydoc.slack.default-space-slug", () -> "m9-space");
        // 테스트 도중 스케줄러가 발화해 fake LLM 카운트를 오염시키지 않도록 늦춘다 — job.run()은 직접 호출한다
        registry.add("mydoc.slack.decision-job-initial-delay-ms", () -> "3600000");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeThreadSummaryClient fakeThreadSummaryClient() {
            return new FakeThreadSummaryClient();
        }
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SlackArchiveService archive;

    @Autowired
    DecisionExtractionJob job;

    @Autowired
    FakeThreadSummaryClient llm;

    @BeforeEach
    void setup() {
        llm.reset();
        jdbcTemplate.update("DELETE FROM slack_decision_log");
        jdbcTemplate.update("DELETE FROM slack_archive_message");
        jdbcTemplate.update("""
                INSERT INTO space (id, slug, name, created_at)
                VALUES (?, 'm9-space', 'M9 Space', ?)
                ON CONFLICT (slug) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
    }

    @Test
    void archiveStoresMessagesAndDeduplicatesRedelivery() {
        archive.archive("C_M9A", "1751800000.000100", null, "U1", "첫 메시지");
        archive.archive("C_M9A", "1751800001.000100", "1751800000.000100", "U2", "스레드 답장");
        // Slack 이벤트 재전달 시나리오 — 같은 (channel, ts)는 한 번만 저장된다
        archive.archive("C_M9A", "1751800000.000100", null, "U1", "첫 메시지");
        // 빈 본문/봇 필터를 통과 못 한 이벤트는 저장되지 않는다
        archive.archive("C_M9A", "1751800002.000100", null, "U3", " ");

        assertThat(count("SELECT COUNT(*) FROM slack_archive_message WHERE channel_id = 'C_M9A'")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT thread_ts FROM slack_archive_message WHERE ts = '1751800001.000100'", String.class))
                .isEqualTo("1751800000.000100");
    }

    @Test
    void quietDecisionThreadBecomesDraftDocumentAndUpdatesWhileDraft() {
        String channel = "C_M9B";
        String root = "1751800100.000100";
        seedQuietThread(channel, root, List.of("배포 요일을 정합시다", "화요일이 좋겠어요", "화요일로 확정할게요"));
        llm.enqueue("""
                {"hasDecision": true, "title": "배포 요일 결정",
                 "summary": ["배포 요일에 대한 논의가 있었고, 화요일로 확정됐어요."],
                 "decisionPoints": [{"decision": "배포는 매주 화요일에 해요.",
                   "rationale": "주말 직후 장애 대응이 어려워요.", "alternatives": [], "owner": "", "condition": ""}],
                 "tacitKnowledge": [{"kind": "constraint",
                   "statement": "주말 직후에는 장애 대응 인력이 부족해요.",
                   "triples": [{"subject": "주말 직후 배포", "predicate": "어렵게 만든다", "object": "장애 대응"}]}]}
                """);

        job.run();

        UUID documentId = jdbcTemplate.queryForObject(
                "SELECT document_id FROM slack_decision_log WHERE channel_id = ? AND thread_ts = ?",
                UUID.class, channel, root);
        assertThat(documentId).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM document WHERE id = ?", String.class, documentId))
                .isEqualTo("배포 요일 결정");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, documentId))
                .isEqualTo("DRAFT");
        String blocks = String.join("\n", jdbcTemplate.queryForList(
                "SELECT content::text FROM block WHERE document_id = ? ORDER BY position", String.class, documentId));
        assertThat(blocks)
                .contains("결정: 배포는 매주 화요일에 해요.")
                .contains("근거: 주말 직후 장애 대응이 어려워요.")
                .contains("[constraint] 주말 직후에는 장애 대응 인력이 부족해요.")
                .contains("주말 직후 배포 — 어렵게 만든다 — 장애 대응")
                .contains("출처: Slack 채널 " + channel + " 스레드 " + root);
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT source_type FROM block WHERE document_id = ?", String.class, documentId))
                .containsExactly("SLACK_INGEST");
        assertThat(llm.calls).isEqualTo(1);

        // 같은 상태에서 잡이 다시 돌면 LLM을 다시 부르지 않는다
        job.run();
        assertThat(llm.calls).isEqualTo(1);

        // 스레드에 새 메시지가 오면 다시 판별하고, DRAFT 문서는 갱신된다
        String laterTs = "1751800110.000100";
        archive.archive(channel, laterTs, root, "U2", "아 참, 공휴일이면 수요일로 미뤄요");
        backdate(channel, laterTs);
        llm.enqueue("""
                {"hasDecision": true, "title": "배포 요일 결정",
                 "summary": ["배포 요일이 화요일로 확정된 뒤, 공휴일 예외가 추가로 논의됐어요."],
                 "decisionPoints": [{"decision": "배포는 매주 화요일에 하고, 공휴일이면 수요일로 미뤄요.",
                   "rationale": "", "alternatives": [], "owner": "", "condition": "공휴일인 경우"}],
                 "tacitKnowledge": []}
                """);
        job.run();
        assertThat(llm.calls).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM document WHERE title = '배포 요일 결정'")).isEqualTo(1);
        String updated = String.join("\n", jdbcTemplate.queryForList(
                "SELECT content::text FROM block WHERE document_id = ? ORDER BY position", String.class, documentId));
        assertThat(updated).contains("공휴일이면 수요일로 미뤄요");
    }

    @Test
    void nonDecisionThreadIsLoggedWithoutDocument() {
        String channel = "C_M9C";
        String root = "1751800200.000100";
        seedQuietThread(channel, root, List.of("점심 뭐 먹지", "국밥?", "ㅋㅋ 좋다"));
        llm.enqueue("{\"hasDecision\": false}");

        job.run();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT document_id FROM slack_decision_log WHERE channel_id = ? AND thread_ts = ?",
                UUID.class, channel, root)).isNull();
        assertThat(count("SELECT COUNT(*) FROM slack_decision_log WHERE channel_id = '" + channel + "'")).isEqualTo(1);
        assertThat(llm.calls).isEqualTo(1);

        // 판별이 끝난 스레드는 재실행해도 LLM을 다시 부르지 않는다
        job.run();
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    void shortOrActiveThreadsAreNotExamined() {
        // 메시지 2개짜리 조용한 스레드 — MIN_MESSAGES 미달
        seedQuietThread("C_M9D", "1751800300.000100", List.of("메시지 하나", "메시지 둘"));
        // 메시지 3개지만 방금 도착 — QUIET_PERIOD 미달 (backdate 없음)
        String activeRoot = "1751800400.000100";
        archive.archive("C_M9E", activeRoot, null, "U1", "하나");
        archive.archive("C_M9E", "1751800401.000100", activeRoot, "U2", "둘");
        archive.archive("C_M9E", "1751800402.000100", activeRoot, "U3", "셋");

        job.run();

        assertThat(count("SELECT COUNT(*) FROM slack_decision_log")).isEqualTo(0);
        assertThat(llm.calls).isEqualTo(0);
    }

    private void seedQuietThread(String channel, String root, List<String> texts) {
        long rootSeconds = Long.parseLong(root.substring(0, root.indexOf('.')));
        for (int i = 0; i < texts.size(); i++) {
            String ts = i == 0 ? root : (rootSeconds + i) + ".000100";
            archive.archive(channel, ts, i == 0 ? null : root, "U" + (i + 1), texts.get(i));
        }
        jdbcTemplate.update("UPDATE slack_archive_message SET created_at = ? WHERE channel_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), channel);
    }

    private void backdate(String channel, String ts) {
        jdbcTemplate.update("UPDATE slack_archive_message SET created_at = ? WHERE channel_id = ? AND ts = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), channel, ts);
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    static class FakeThreadSummaryClient implements ThreadSummaryClient {
        final Deque<String> responses = new ArrayDeque<>();
        int calls;

        void reset() {
            responses.clear();
            calls = 0;
        }

        void enqueue(String response) {
            responses.add(response);
        }

        @Override
        public String summarize(String systemPrompt, String userPrompt) {
            calls++;
            if (responses.isEmpty()) {
                throw new IllegalStateException("unexpected LLM call");
            }
            return responses.poll();
        }
    }
}
