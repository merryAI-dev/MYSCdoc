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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        // 테스트 도중 스케줄러가 발화해 fake LLM 카운트를 오염시키지 않도록 끈다("-") — job.run()은 직접 호출한다
        registry.add("mydoc.slack.decision-cron", () -> "-");
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

    @Autowired
    org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

    UUID adminId;

    @BeforeEach
    void setup() {
        llm.reset();
        jdbcTemplate.update("DELETE FROM knowledge_triple");
        jdbcTemplate.update("DELETE FROM slack_decision_log");
        jdbcTemplate.update("DELETE FROM slack_archive_message");
        jdbcTemplate.update("DELETE FROM slack_channel_config");
        jdbcTemplate.update("UPDATE knowledge_setting SET quiet_minutes = 30, min_messages = 3");
        jdbcTemplate.update("""
                INSERT INTO space (id, slug, name, created_at)
                VALUES (?, 'm9-space', 'M9 Space', ?)
                ON CONFLICT (slug) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, 'admin-m9@mysc.co.kr', 'Admin M9', 'ADMIN', ?)
                ON CONFLICT (email) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
        adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM member WHERE email = 'admin-m9@mysc.co.kr'", UUID.class);
    }

    @Test
    void archiveStoresMessagesAndDeduplicatesRedelivery() {
        enableChannel("C_M9A");
        archive.archive("C_M9A", "1751800000.000100", null, "U1", "첫 메시지");
        archive.archive("C_M9A", "1751800001.000100", "1751800000.000100", "U2", "스레드 답장");
        // Slack 이벤트 재전달 시나리오 — 같은 (channel, ts)는 한 번만 저장된다
        archive.archive("C_M9A", "1751800000.000100", null, "U1", "첫 메시지");
        // 빈 본문/봇 필터를 통과 못 한 이벤트는 저장되지 않는다
        archive.archive("C_M9A", "1751800002.000100", null, "U3", " ");
        // 설정에서 켜지 않은 채널은 봇이 초대돼 있어도 수집하지 않는다 (명시적 옵트인)
        archive.archive("C_M9X", "1751800003.000100", null, "U1", "옵트인 안 된 채널");

        assertThat(count("SELECT COUNT(*) FROM slack_archive_message WHERE channel_id = 'C_M9A'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM slack_archive_message WHERE channel_id = 'C_M9X'")).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT thread_ts FROM slack_archive_message WHERE ts = '1751800001.000100'", String.class))
                .isEqualTo("1751800000.000100");
    }

    @Test
    void settingsApiTunesExtractionThresholds() {
        // 채널 토글 API — 켜면 수집되고, 목록에 상태가 반영된다
        ResponseEntity<Map> toggled = restTemplate.exchange(
                "/api/slack/channels/C_M9S", HttpMethod.PUT,
                entity(Map.of("enabled", true, "channelName", "m9-settings-test")), Map.class);
        assertThat(toggled.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> channels = restTemplate.exchange(
                "/api/slack/channels", HttpMethod.GET, entity(null), Map.class);
        List<Map<String, Object>> channelList = (List<Map<String, Object>>) channels.getBody().get("channels");
        assertThat(channelList).anySatisfy(ch -> {
            assertThat(ch.get("channelId")).isEqualTo("C_M9S");
            assertThat(ch.get("archiveEnabled")).isEqualTo(true);
        });

        // 미세조정 API — 최소 메시지 수를 2로 낮추면 2개짜리 조용한 스레드도 판별 대상이 된다
        ResponseEntity<Map> updated = restTemplate.exchange(
                "/api/knowledge/settings", HttpMethod.PUT,
                entity(Map.of("quietMinutes", 30, "minMessages", 2)), Map.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("minMessages")).isEqualTo(2);

        seedQuietThread("C_M9S", "1751800600.000100", List.of("이슈 정리합시다", "네 그러시죠"));
        llm.enqueue("{\"worthRecording\": false}");
        job.run();
        assertThat(llm.calls).isEqualTo(1); // 기본값(3)이었다면 0이어야 한다
    }

    @Test
    void manualSyncRunsExtractionNowAndReportsCounts() {
        seedQuietThread("C_M9M", "1751800700.000100",
                List.of("배포 브랜치 전략 정합시다", "trunk 기반으로 가죠", "네 trunk로 확정"));
        llm.enqueue("""
                {"worthRecording": true, "title": "브랜치 전략 결정",
                 "summary": ["브랜치 전략을 trunk 기반으로 확정했어요."],
                 "decisionPoints": [{"decision": "trunk 기반 브랜치 전략을 써요.",
                   "rationale": "", "alternatives": [], "owner": "", "condition": ""}],
                 "tacitKnowledge": []}
                """);

        ResponseEntity<Map> sync = restTemplate.exchange(
                "/api/knowledge/sync", HttpMethod.POST, entity(null), Map.class);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sync.getBody().get("started")).isEqualTo(true);
        assertThat(sync.getBody().get("examined")).isEqualTo(1);
        assertThat(sync.getBody().get("documented")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM document WHERE title = '브랜치 전략 결정'")).isEqualTo(1);
    }

    @Test
    void knowledgeOnlyThreadWithoutDecisionStillBecomesDocument() {
        // 결정("~하기로 했다")은 없지만 근본원인+해결 같은 재사용 지식만 있는 스레드도 기록해야 한다 (todoc 본질).
        String channel = "C_M9K2";
        String root = "1751800800.000100";
        seedQuietThread(channel, root, List.of(
                "사용자 목록 렌더링할 때 컨설턴트 메일이 문자열이 아니면 화면이 터져요",
                "확인해볼게요",
                "해결됐습니다. 원인은 메일 필드가 null일 때 처리 누락이었고 null 가드 넣었어요"));
        llm.enqueue("""
                {"worthRecording": true, "title": "사용자 목록 렌더링 메일 null 처리 누락",
                 "summary": ["사용자 목록 렌더링에서 메일이 문자열이 아니면 화면이 깨지는 문제가 있었어요.",
                             "원인은 메일 null 처리 누락이었고 null 가드로 해결했어요."],
                 "decisionPoints": [],
                 "tacitKnowledge": [{"kind": "gotcha",
                   "statement": "사용자 목록 렌더링은 메일이 null이면 깨져요 — null 가드가 필요해요.",
                   "triples": [{"subject": "사용자 목록 렌더링", "predicate": "깨진다", "object": "메일이 null일 때"}]}]}
                """);

        job.run();

        UUID documentId = jdbcTemplate.queryForObject(
                "SELECT document_id FROM slack_decision_log WHERE channel_id = ? AND thread_ts = ?",
                UUID.class, channel, root);
        assertThat(documentId).as("결정이 없어도 지식만으로 문서가 생성돼야 한다").isNotNull();
        String blocks = String.join("\n", jdbcTemplate.queryForList(
                "SELECT content::text FROM block WHERE document_id = ? ORDER BY position", String.class, documentId));
        assertThat(blocks).contains("조직의 암묵지").contains("null 가드");
        assertThat(blocks).doesNotContain("의사결정"); // 빈 결정 섹션은 만들지 않는다
        // 트리플은 gotcha 1건 (decision 트리플 없음)
        assertThat(count("SELECT COUNT(*) FROM knowledge_triple WHERE document_id = '" + documentId + "'")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT kind FROM knowledge_triple WHERE document_id = ?", String.class, documentId)).isEqualTo("gotcha");
    }

    @Test
    void quietDecisionThreadBecomesDraftDocumentAndUpdatesWhileDraft() {
        String channel = "C_M9B";
        String root = "1751800100.000100";
        seedQuietThread(channel, root, List.of("배포 요일을 정합시다", "화요일이 좋겠어요", "화요일로 확정할게요"));
        llm.enqueue("""
                {"worthRecording": true, "title": "배포 요일 결정",
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
        // 트리플 영속화: decision 1건 + 암묵지 1건
        assertThat(count("SELECT COUNT(*) FROM knowledge_triple WHERE document_id = '" + documentId + "'")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT kind FROM knowledge_triple WHERE document_id = ?", String.class, documentId))
                .containsExactlyInAnyOrder("decision", "constraint");
        assertThat(llm.calls).isEqualTo(1);

        // 같은 상태에서 잡이 다시 돌면 LLM을 다시 부르지 않는다
        job.run();
        assertThat(llm.calls).isEqualTo(1);

        // 스레드에 새 메시지가 오면 다시 판별하고, DRAFT 문서는 갱신된다
        String laterTs = "1751800110.000100";
        archive.archive(channel, laterTs, root, "U2", "아 참, 공휴일이면 수요일로 미뤄요");
        backdate(channel, laterTs);
        llm.enqueue("""
                {"worthRecording": true, "title": "배포 요일 결정",
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
        // 재추출 시 트리플도 통째로 교체된다 — 두 번째 fake는 tacitKnowledge가 비어 decision 1건만 남는다
        assertThat(count("SELECT COUNT(*) FROM knowledge_triple WHERE document_id = '" + documentId + "'")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT object FROM knowledge_triple WHERE document_id = ?", String.class, documentId))
                .contains("공휴일이면 수요일로 미뤄요");
    }

    @Test
    void knowledgeApiRanksTriplesByBm25AndServesGraph() {
        UUID documentId = seedDocument("지식그래프 테스트 문서");
        insertTriple(documentId, "constraint", "결제 재시도 로직은 상한이 없으면 야간 알림 과다를 유발해요.",
                "결제 재시도 로직", "유발한다", "야간 알림 과다");
        insertTriple(documentId, "decision", "배포는 매주 화요일에 하기로 했어요.",
                "팀", "결정했다", "배포는 매주 화요일에 해요");
        insertTriple(documentId, "convention", "결제 API 장애는 민지가 1차 대응해요.",
                "결제 API", "담당한다", "민지");

        // BM25: '결제'가 들어간 트리플 2건만, 점수순으로 반환. '배포' 트리플은 점수 0이라 제외.
        ResponseEntity<Map> search = restTemplate.exchange(
                "/api/knowledge/triples?q=결제", HttpMethod.GET, entity(null), Map.class);
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> hits = (List<Map<String, Object>>) search.getBody().get("triples");
        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(hit ->
                assertThat(hit.get("subject").toString()).contains("결제"));
        assertThat((Double) hits.get(0).get("score")).isGreaterThanOrEqualTo((Double) hits.get(1).get("score"));

        // 그래프: 질의 없이 전체 — 간선 3개, 노드는 중복 없는 개체 수
        ResponseEntity<Map> graph = restTemplate.exchange(
                "/api/knowledge/graph", HttpMethod.GET, entity(null), Map.class);
        assertThat(graph.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) graph.getBody().get("edges")).hasSize(3);
        assertThat((List<?>) graph.getBody().get("nodes")).hasSize(6);
    }

    private UUID seedDocument(String title) {
        UUID documentId = UUID.randomUUID();
        UUID spaceId = jdbcTemplate.queryForObject("SELECT id FROM space WHERE slug = 'm9-space'", UUID.class);
        jdbcTemplate.update("""
                INSERT INTO document (id, space_id, title, owner_id, status, ttl_days, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', 90, 0, ?, ?)
                """, documentId, spaceId, title, adminId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return documentId;
    }

    private void insertTriple(UUID documentId, String kind, String statement,
                              String subject, String predicate, String object) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_triple (id, document_id, kind, statement, subject, predicate, object,
                                              channel_id, thread_ts, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'C_M9K', '1751800500.000100', ?)
                """, UUID.randomUUID(), documentId, kind, statement, subject, predicate, object,
                Timestamp.from(Instant.now()));
    }

    private HttpEntity<Object> entity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Member-Id", adminId.toString());
        return new HttpEntity<>(body, headers);
    }

    @Test
    void nonDecisionThreadIsLoggedWithoutDocument() {
        String channel = "C_M9C";
        String root = "1751800200.000100";
        seedQuietThread(channel, root, List.of("점심 뭐 먹지", "국밥?", "ㅋㅋ 좋다"));
        llm.enqueue("{\"worthRecording\": false}");

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
        // 메시지 3개지만 방금 도착 — 침묵 기준 미달 (backdate 없음)
        String activeRoot = "1751800400.000100";
        enableChannel("C_M9E");
        archive.archive("C_M9E", activeRoot, null, "U1", "하나");
        archive.archive("C_M9E", "1751800401.000100", activeRoot, "U2", "둘");
        archive.archive("C_M9E", "1751800402.000100", activeRoot, "U3", "셋");

        job.run();

        assertThat(count("SELECT COUNT(*) FROM slack_decision_log")).isEqualTo(0);
        assertThat(llm.calls).isEqualTo(0);
    }

    private void enableChannel(String channelId) {
        jdbcTemplate.update("""
                INSERT INTO slack_channel_config (channel_id, channel_name, archive_enabled, updated_at)
                VALUES (?, ?, true, ?)
                ON CONFLICT (channel_id) DO UPDATE SET archive_enabled = true
                """, channelId, channelId, Timestamp.from(Instant.now()));
    }

    private void seedQuietThread(String channel, String root, List<String> texts) {
        enableChannel(channel);
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
