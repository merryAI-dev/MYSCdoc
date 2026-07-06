package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.ingest.meet.ArtifactKind;
import com.mysc.mydoc.ingest.meet.GoogleWorkspaceAccessTokenProvider;
import com.mysc.mydoc.ingest.meet.MeetArtifact;
import com.mysc.mydoc.ingest.meet.MeetArtifactGateway;
import com.mysc.mydoc.ingest.meet.MeetDocumentGateway;
import com.mysc.mydoc.ingest.meet.MeetEventPuller;
import com.mysc.mydoc.ingest.meet.MeetIngestService;
import com.mysc.mydoc.ingest.meet.MeetRetryableException;
import com.mysc.mydoc.ingest.meet.MeetSubscriptionJob;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.ObjectProvider;
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
class M7AcceptanceTest {

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
        registry.add("mydoc.meet.subscribed-user", () -> "meet-owner@mysc.co.kr");
        registry.add("mydoc.meet.default-space-slug", () -> "meet-space");
        registry.add("mydoc.rechunk.debounce", () -> "PT0S");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeMeetArtifactGateway fakeMeetArtifactGateway() {
            return new FakeMeetArtifactGateway();
        }

        @Bean
        @Primary
        FakeMeetDocumentGateway fakeMeetDocumentGateway() {
            return new FakeMeetDocumentGateway();
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
    MeetIngestService ingest;

    @Autowired
    FakeMeetArtifactGateway artifacts;

    @Autowired
    FakeMeetDocumentGateway drive;

    @Autowired
    FakeThreadSummaryClient summaries;

    @Autowired
    ObjectProvider<GoogleWorkspaceAccessTokenProvider> googleTokens;

    @Autowired
    ObjectProvider<MeetEventPuller> eventPuller;

    @Autowired
    ObjectProvider<MeetSubscriptionJob> subscriptionJob;

    @BeforeEach
    void setup() {
        artifacts.reset();
        drive.reset();
        summaries.reset();
        jdbcTemplate.update("""
                INSERT INTO space (id, slug, name, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (slug) DO NOTHING
                """, UUID.randomUUID(), "meet-space", "Meet Space", Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (email) DO NOTHING
                """, UUID.randomUUID(), "meet-owner@mysc.co.kr", "Meet Owner", "MEMBER", Timestamp.from(Instant.now()));
    }

    @Test
    void m7IngestsReadyArtifactAsDraftWithMeetProvenance() {
        String resourceName = "conferenceRecords/m7-ready/smartNotes/smart-1";
        artifacts.items.put(resourceName, new MeetArtifact("FILE_GENERATED", "doc-ready"));
        drive.documents.put("doc-ready", body(500));
        summaries.responses.add("""
                {"title":"Meet 결정 문서","sections":[{"heading":"결정 사항","paragraphs":["A를 진행하기로 했어요."]},{"heading":"후속 작업","paragraphs":["보람이 초안을 정리해요."]}]}
                """);

        ingest.onArtifactGenerated(resourceName, ArtifactKind.SMART_NOTE);

        UUID documentId = jdbcTemplate.queryForObject("SELECT document_id FROM meet_ingest_log WHERE conference_record = 'conferenceRecords/m7-ready'", UUID.class);
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM document WHERE id = ?", String.class, documentId)).isEqualTo("Meet 결정 문서");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, documentId)).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT m.email FROM member m JOIN document d ON d.owner_id = m.id WHERE d.id = ?
                """, String.class, documentId)).isEqualTo("meet-owner@mysc.co.kr");
        assertThat(jdbcTemplate.queryForList("SELECT type FROM block WHERE document_id = ? ORDER BY position", String.class, documentId))
                .containsExactly("HEADING2", "PARAGRAPH", "HEADING2", "PARAGRAPH", "PARAGRAPH");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT source_type FROM block WHERE document_id = ?", String.class, documentId))
                .containsExactly("MEETING_INGEST");
        assertThat(jdbcTemplate.queryForObject("SELECT source_url FROM block WHERE document_id = ? ORDER BY position LIMIT 1", String.class, documentId))
                .isEqualTo("https://docs.google.com/document/d/doc-ready");
        assertThat(summaries.systemPrompts).last().asString().contains("회의록").contains("코드펜스 없이 순수 JSON");
    }

    @Test
    void duplicateConferenceRecordDoesNotCreateSecondDocument() {
        String smartNote = "conferenceRecords/m7-duplicate/smartNotes/smart-1";
        artifacts.items.put(smartNote, new MeetArtifact("FILE_GENERATED", "doc-duplicate"));
        drive.documents.put("doc-duplicate", body(500));
        summaries.responses.add(successJson("중복 회의록"));

        ingest.onArtifactGenerated(smartNote, ArtifactKind.SMART_NOTE);
        ingest.onArtifactGenerated("conferenceRecords/m7-duplicate/transcripts/transcript-1", ArtifactKind.TRANSCRIPT);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM document d
                JOIN meet_ingest_log l ON l.document_id = d.id
                WHERE l.conference_record = 'conferenceRecords/m7-duplicate'
                """, Integer.class)).isEqualTo(1);
        assertThat(summaries.systemPrompts).hasSize(1);
    }

    @Test
    void invalidSummaryJsonRollsBackAndRetries() {
        String resourceName = "conferenceRecords/m7-invalid-json/smartNotes/smart-1";
        artifacts.items.put(resourceName, new MeetArtifact("FILE_GENERATED", "doc-invalid-json"));
        drive.documents.put("doc-invalid-json", body(500));
        summaries.responses.add("not json");
        summaries.responses.add("still not json");

        assertThatThrownBy(() -> ingest.onArtifactGenerated(resourceName, ArtifactKind.SMART_NOTE))
                .isInstanceOf(MeetRetryableException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM meet_ingest_log WHERE conference_record = 'conferenceRecords/m7-invalid-json'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document WHERE title = 'm7-invalid-json'", Integer.class)).isZero();
    }

    @Test
    void blankOrShortBodyIsNackedForPubSubRetry() {
        String resourceName = "conferenceRecords/m7-short-body/smartNotes/smart-1";
        artifacts.items.put(resourceName, new MeetArtifact("FILE_GENERATED", "doc-short-body"));
        drive.documents.put("doc-short-body", "짧은 회의록");

        assertThatThrownBy(() -> ingest.onArtifactGenerated(resourceName, ArtifactKind.SMART_NOTE))
                .isInstanceOf(MeetRetryableException.class)
                .hasMessageContaining("body");

        assertThat(summaries.systemPrompts).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM meet_ingest_log WHERE conference_record = 'conferenceRecords/m7-short-body'", Integer.class)).isZero();
    }

    @Test
    void longBodyIsTruncatedBeforeSummary() {
        String resourceName = "conferenceRecords/m7-long-body/smartNotes/smart-1";
        artifacts.items.put(resourceName, new MeetArtifact("FILE_GENERATED", "doc-long-body"));
        drive.documents.put("doc-long-body", body(410_000));
        summaries.responses.add(successJson("긴 회의록"));

        ingest.onArtifactGenerated(resourceName, ArtifactKind.SMART_NOTE);

        assertThat(summaries.userPrompts).last().asString().hasSize(400_000);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM meet_ingest_log WHERE conference_record = 'conferenceRecords/m7-long-body'", Integer.class)).isEqualTo(1);
    }

    @Test
    void poisonMessageIsAckedAfterTenFailures() {
        String resourceName = "conferenceRecords/m7-poison/smartNotes/smart-1";
        artifacts.items.put(resourceName, new MeetArtifact("FILE_GENERATED", "doc-poison"));
        drive.documents.put("doc-poison", body(500));

        for (int i = 0; i < 9; i++) {
            assertThatThrownBy(() -> ingest.onArtifactGenerated(resourceName, ArtifactKind.SMART_NOTE))
                    .isInstanceOf(MeetRetryableException.class);
        }

        assertThatCode(() -> ingest.onArtifactGenerated(resourceName, ArtifactKind.SMART_NOTE)).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM meet_ingest_log WHERE conference_record = 'conferenceRecords/m7-poison'", Integer.class)).isZero();
    }

    @Test
    void googleComponentsStayDisabledWhenMeetEnvIsIncomplete() {
        assertThat(googleTokens.getIfAvailable()).isNull();
        assertThat(eventPuller.getIfAvailable()).isNull();
        assertThat(subscriptionJob.getIfAvailable()).isNull();
    }

    private static String body(int length) {
        return "회의 본문 ".repeat((length / 6) + 1).substring(0, length);
    }

    private static String successJson(String title) {
        return """
                {"title":"%s","sections":[{"heading":"결정 사항","paragraphs":["진행하기로 했어요."]}]}
                """.formatted(title);
    }

    static class FakeMeetArtifactGateway implements MeetArtifactGateway {
        final Map<String, MeetArtifact> items = new ConcurrentHashMap<>();

        @Override
        public MeetArtifact get(String resourceName, ArtifactKind kind) {
            return Optional.ofNullable(items.get(resourceName))
                    .orElseThrow(() -> new IllegalStateException("missing artifact: " + resourceName));
        }

        void reset() {
            items.clear();
        }
    }

    static class FakeMeetDocumentGateway implements MeetDocumentGateway {
        final Map<String, String> documents = new ConcurrentHashMap<>();

        @Override
        public String exportText(String documentId) {
            return Optional.ofNullable(documents.get(documentId))
                    .orElseThrow(() -> new IllegalStateException("missing document: " + documentId));
        }

        void reset() {
            documents.clear();
        }
    }

    static class FakeThreadSummaryClient implements ThreadSummaryClient {
        final Queue<String> responses = new ArrayDeque<>();
        final Queue<String> systemPrompts = new ArrayDeque<>();
        final Queue<String> userPrompts = new ArrayDeque<>();

        @Override
        public String summarize(String systemPrompt, String userPrompt) {
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            if (responses.isEmpty()) {
                return "not json";
            }
            return responses.remove();
        }

        void reset() {
            responses.clear();
            systemPrompts.clear();
            userPrompts.clear();
        }
    }
}
