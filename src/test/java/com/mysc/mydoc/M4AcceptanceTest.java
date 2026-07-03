package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mysc.mydoc.ingest.SlackGateway;
import com.mysc.mydoc.ingest.SlackIngestService;
import com.mysc.mydoc.ingest.SlackMessage;
import com.mysc.mydoc.ingest.SlackThread;
import com.mysc.mydoc.ingest.ThreadSummaryClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M4AcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("mydoc")
            .withUsername("mydoc")
            .withPassword("changeme");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("mydoc.slack.default-space-slug", () -> "m4-space");
        registry.add("mydoc.document-base-url", () -> "http://mydoc.test");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeSlackGateway fakeSlackGateway() {
            return new FakeSlackGateway();
        }

        @Bean
        @Primary
        FakeThreadSummaryClient fakeThreadSummaryClient() {
            return new FakeThreadSummaryClient();
        }
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SlackIngestService ingest;

    @Autowired
    FakeSlackGateway slack;

    @Autowired
    FakeThreadSummaryClient summaryClient;

    UUID adminId;
    UUID ownerId;

    @BeforeEach
    void setup() {
        slack.reset();
        summaryClient.reset();
        adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, adminId, "admin-m4@mysc.co.kr", "Admin M4", "ADMIN", Timestamp.from(Instant.now()));
        restTemplate.exchange("/api/spaces", HttpMethod.POST, entity(Map.of("slug", "m4-space", "name", "M4 Space"), adminId), Map.class);
        ownerId = UUID.fromString((String) restTemplate.exchange(
                "/api/members",
                HttpMethod.POST,
                entity(Map.of("email", "bookmark@mysc.co.kr", "displayName", "Bookmark Owner", "role", "MEMBER"), adminId),
                Map.class
        ).getBody().get("id"));
    }

    @Test
    void m4AcceptanceScenario() {
        slack.userEmails.put("U1", "bookmark@mysc.co.kr");
        slack.threads.put("C1:111.1", new SlackThread(
                "111.1",
                "https://slack.test/archives/C1/p1111",
                List.of(
                        new SlackMessage("U1", "보람", "결정은 A입니다.", "111.1"),
                        new SlackMessage("U2", "이지", "근거는 B입니다.", "111.2"),
                        new SlackMessage("U3", "메씨리", "후속 작업은 없습니다.", "111.3")
                )
        ));
        summaryClient.responses.add("""
                {"title":"결정 문서","sections":[{"heading":"결정 사항","paragraphs":["A로 결정했어요."]},{"heading":"근거","paragraphs":["B를 근거로 삼았어요."]}]}
                """);

        ingest.onReactionAdded("C1", "111.1", "U1");

        UUID documentId = jdbcTemplate.queryForObject("SELECT id FROM document WHERE title = '결정 문서'", UUID.class);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, documentId)).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("SELECT owner_id FROM document WHERE id = ?", UUID.class, documentId)).isEqualTo(ownerId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM slack_ingest_log WHERE channel_id = 'C1' AND thread_ts = '111.1'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT type FROM block WHERE document_id = ? ORDER BY position", String.class, documentId))
                .containsExactly("HEADING2", "PARAGRAPH", "HEADING2", "PARAGRAPH", "PARAGRAPH");
        assertThat(jdbcTemplate.queryForObject("SELECT content::text FROM block WHERE document_id = ? ORDER BY position DESC LIMIT 1", String.class, documentId))
                .contains("출처: https://slack.test/archives/C1/p1111");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT source_type FROM block WHERE document_id = ?", String.class, documentId))
                .containsExactly("SLACK_INGEST");
        assertThat(slack.replies).anySatisfy(reply -> assertThat(reply.text()).contains("http://mydoc.test/d/" + documentId));

        ingest.onReactionAdded("C1", "111.1", "U1");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document WHERE title = '결정 문서'", Integer.class)).isEqualTo(1);
        assertThat(slack.replies).filteredOn(reply -> reply.text().contains(documentId.toString())).hasSize(2);

        slack.threads.put("C1:222.1", new SlackThread("222.1", "https://slack.test/archives/C1/p2222", List.of(new SlackMessage("U1", "보람", "실패 케이스", "222.1"))));
        summaryClient.responses.add("not json");
        summaryClient.responses.add("still not json");
        ingest.onReactionAdded("C1", "222.1", "U1");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM slack_ingest_log WHERE thread_ts = '222.1'", Integer.class)).isZero();
        assertThat(slack.replies).anySatisfy(reply -> assertThat(reply.text()).contains("요약에 실패했어요"));

        slack.failReplies = true;
        slack.threads.put("C1:444.1", new SlackThread("444.1", "https://slack.test/archives/C1/p4444", List.of(new SlackMessage("U1", "보람", "Slack reply failure", "444.1"))));
        summaryClient.responses.add("not json");
        summaryClient.responses.add("still not json");
        assertThatCode(() -> ingest.onReactionAdded("C1", "444.1", "U1")).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM slack_ingest_log WHERE thread_ts = '444.1'", Integer.class)).isZero();
        slack.failReplies = false;

        slack.failReplies = true;
        slack.threads.put("C1:555.1", new SlackThread("555.1", "https://slack.test/archives/C1/p5555", List.of(new SlackMessage("U1", "보람", "reply failure after ingest", "555.1"))));
        summaryClient.responses.add("""
                {"title":"reply failure 문서","sections":[{"heading":"결정 사항","paragraphs":["문서는 생성돼야 해요."]}]}
                """);
        assertThatCode(() -> ingest.onReactionAdded("C1", "555.1", "U1")).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM slack_ingest_log WHERE thread_ts = '555.1'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document WHERE title = 'reply failure 문서'", Integer.class)).isEqualTo(1);
        slack.failReplies = false;

        slack.threads.put("C1:333.1", new SlackThread("333.1", "https://slack.test/archives/C1/p3333", List.of(new SlackMessage("U9", "외부인", "owner fallback", "333.1"))));
        summaryClient.responses.add("""
                {"title":"fallback 문서","sections":[{"heading":"결정 사항","paragraphs":["시스템 멤버가 owner예요."]}]}
                """);
        ingest.onReactionAdded("C1", "333.1", "UNKNOWN");
        UUID fallbackOwner = jdbcTemplate.queryForObject("""
                SELECT owner_id FROM document WHERE title = 'fallback 문서'
                """, UUID.class);
        String fallbackEmail = jdbcTemplate.queryForObject("SELECT email FROM member WHERE id = ?", String.class, fallbackOwner);
        assertThat(fallbackEmail).isEqualTo("bot@mydoc.internal");
    }

    private HttpEntity<Object> entity(Object body, UUID memberId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Member-Id", memberId.toString());
        return new HttpEntity<>(body, headers);
    }

    record Reply(String channelId, String threadTs, String text) {}

    static class FakeSlackGateway implements SlackGateway {
        final Map<String, SlackThread> threads = new ConcurrentHashMap<>();
        final Map<String, String> userEmails = new ConcurrentHashMap<>();
        final List<Reply> replies = new ArrayList<>();
        boolean failReplies;

        @Override
        public SlackThread thread(String channelId, String messageTs) {
            return threads.get(channelId + ":" + messageTs);
        }

        @Override
        public Optional<String> userEmail(String slackUserId) {
            return Optional.ofNullable(userEmails.get(slackUserId));
        }

        @Override
        public void reply(String channelId, String threadTs, String text) {
            if (failReplies) {
                throw new IllegalStateException("reply failed");
            }
            replies.add(new Reply(channelId, threadTs, text));
        }

        void reset() {
            threads.clear();
            userEmails.clear();
            replies.clear();
            failReplies = false;
        }
    }

    static class FakeThreadSummaryClient implements ThreadSummaryClient {
        final Queue<String> responses = new ConcurrentLinkedQueue<>();

        @Override
        public String summarize(String systemPrompt, String userPrompt) {
            return responses.remove();
        }

        void reset() {
            responses.clear();
        }
    }
}
