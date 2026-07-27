package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.ingest.meetily.MeetilyIngestService;
import com.mysc.mydoc.ingest.meetily.MeetilyMeeting;
import com.mysc.mydoc.ingest.meetily.MeetilyMeetingDetail;
import com.mysc.mydoc.ingest.meetily.MeetilyPort;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M17AcceptanceTest {

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
        registry.add("mydoc.slack.decision-cron", () -> "-");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeMeetilyPort fakeMeetilyPort() {
            return new FakeMeetilyPort();
        }

        @Bean
        @Primary
        FakeThreadSummaryClient fakeThreadSummaryClient() {
            return new FakeThreadSummaryClient();
        }
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MeetilyIngestService ingest;

    @Autowired
    FakeMeetilyPort meetily;

    @Autowired
    FakeThreadSummaryClient llm;

    UUID memberId;
    UUID spaceId;

    @BeforeEach
    void setup() {
        meetily.reset();
        llm.reset();
        jdbcTemplate.update("DELETE FROM knowledge_triple");
        jdbcTemplate.update("DELETE FROM meetily_ingest_log");
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, 'admin-m17@mysc.co.kr', 'Admin M17', 'ADMIN', ?)
                ON CONFLICT (email) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
        memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM member WHERE email = 'admin-m17@mysc.co.kr'", UUID.class);
        jdbcTemplate.update("""
                INSERT INTO space (id, slug, name, created_at)
                VALUES (?, 'm17-space', 'M17 Space', ?)
                ON CONFLICT (slug) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
        spaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM space WHERE slug = 'm17-space'", UUID.class);
    }

    @Test
    void importsMeetingPreservingTranscriptAndExtractingKnowledge() {
        meetily.add("meeting-1718340000000", "주간 싱크", "2026-07-14 10:00:00", List.of(
                new MeetilyMeetingDetail.Segment("배포 요일을 화요일로 확정했어요.", "2026-07-14 10:00:01", 0.0, 5.2),
                new MeetilyMeetingDetail.Segment("담당은 민지가 맡아요.", "2026-07-14 10:00:06", 5.2, 8.0)
        ));
        llm.enqueue("""
                {"worthRecording": true, "title": "배포 요일 결정",
                 "summary": ["배포 요일을 화요일로 확정했어요."],
                 "decisionPoints": [{"decision": "배포는 화요일에 해요.", "rationale": "", "alternatives": [],
                   "owner": "민지", "condition": "", "topic": "배포 요일"}],
                 "tacitKnowledge": []}
                """);

        UUID documentId = ingest.importMeeting("meeting-1718340000000", spaceId, memberId);

        // 원문 보존: 전사 문단 + 메타 라벨(녹음 시작/출처)이 블록으로 들어간다
        String blocks = String.join("\n", jdbcTemplate.queryForList(
                "SELECT content::text FROM block WHERE document_id = ? ORDER BY position", String.class, documentId));
        assertThat(blocks).contains("배포 요일을 화요일로 확정했어요.").contains("출처: Meetily (meeting-1718340000000)");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM document WHERE id = ?", String.class, documentId)).isEqualTo("주간 싱크");
        // M20: 결정이 사건 노드로 분해돼 저장 — 주체(민지)·대상(배포 요일) 엣지
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_triple WHERE document_id = ? AND channel_id = 'meetily'",
                Integer.class, documentId)).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT object FROM knowledge_triple WHERE document_id = ? AND predicate = '대상'",
                String.class, documentId)).isEqualTo("배포 요일");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT object FROM knowledge_triple WHERE document_id = ? AND predicate = '주체'",
                String.class, documentId)).isEqualTo("민지");

        // 재가져오기는 멱등 — 같은 documentId, LLM 재호출 없음
        int callsBefore = llm.calls;
        UUID again = ingest.importMeeting("meeting-1718340000000", spaceId, memberId);
        assertThat(again).isEqualTo(documentId);
        assertThat(llm.calls).isEqualTo(callsBefore);
    }

    @Test
    void llmFailureDoesNotLoseImportedTranscript() {
        meetily.add("meeting-2", "장애 회의", "2026-07-14 11:00:00", List.of(
                new MeetilyMeetingDetail.Segment("전사 내용이에요.", "2026-07-14 11:00:01", null, null)
        ));
        // LLM 응답을 큐에 넣지 않음 → 추출 단계에서 예외 → 원문 문서는 살아 있어야 한다

        UUID documentId = ingest.importMeeting("meeting-2", spaceId, memberId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM meetily_ingest_log WHERE meeting_id = 'meeting-2'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM block WHERE document_id = ?", Integer.class, documentId)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_triple WHERE document_id = ?", Integer.class, documentId)).isZero();
    }

    static class FakeMeetilyPort implements MeetilyPort {
        final Map<String, MeetilyMeetingDetail> meetings = new LinkedHashMap<>();

        void reset() {
            meetings.clear();
        }

        void add(String id, String title, String createdAt, List<MeetilyMeetingDetail.Segment> segments) {
            meetings.put(id, new MeetilyMeetingDetail(id, title, createdAt, segments));
        }

        @Override
        public List<MeetilyMeeting> listMeetings() {
            return meetings.values().stream()
                    .map(meeting -> new MeetilyMeeting(meeting.id(), meeting.title()))
                    .toList();
        }

        @Override
        public MeetilyMeetingDetail getMeeting(String meetingId) {
            return meetings.get(meetingId);
        }
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
