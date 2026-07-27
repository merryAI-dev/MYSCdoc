package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.ingest.drive.GoogleDriveGateway;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService.ImportJobView;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService.JobStatus;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M15AcceptanceTest {

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
        FakeDriveGateway fakeDriveGateway() {
            return new FakeDriveGateway();
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
    GoogleDriveIngestService ingest;

    @Autowired
    FakeDriveGateway driveGateway;

    @Autowired
    FakeThreadSummaryClient llm;

    UUID memberId;
    UUID spaceId;

    @BeforeEach
    void setup() {
        driveGateway.reset();
        llm.reset();
        // 테스트 간 pacing 지연 제거 (LLM_PACING_MS는 상수라 리플렉션으로 잡 상태만 초기화)
        ReflectionTestUtils.setField(ingest, "currentJob", null);
        jdbcTemplate.update("DELETE FROM knowledge_triple");
        jdbcTemplate.update("DELETE FROM google_drive_ingest_log");
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, 'admin-m15@mysc.co.kr', 'Admin M15', 'ADMIN', ?)
                ON CONFLICT (email) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
        memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM member WHERE email = 'admin-m15@mysc.co.kr'", UUID.class);
        jdbcTemplate.update("""
                INSERT INTO space (id, slug, name, created_at)
                VALUES (?, 'm15-space', 'M15 Space', ?)
                ON CONFLICT (slug) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
        spaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM space WHERE slug = 'm15-space'", UUID.class);
    }

    @Test
    void importsFolderPreservingTextAndExtractingKnowledge() {
        driveGateway.docs.put("doc-1", new String[]{"주간 회의록 7월", "안건: 배포 요일\n\n화요일로 확정했다.\n담당은 민지."});
        driveGateway.docs.put("doc-2", new String[]{"킥오프 회의록", "프로젝트 킥오프.\n일정 공유만 진행."});
        // doc-1은 의사결정 추출, doc-2는 기록 가치 없음으로 응답
        llm.enqueue("""
                {"worthRecording": true, "title": "배포 요일 결정",
                 "summary": ["배포 요일을 화요일로 확정했어요."],
                 "decisionPoints": [{"decision": "배포는 화요일에 해요.", "rationale": "", "alternatives": [],
                   "owner": "민지", "condition": "", "topic": "배포 요일"}],
                 "tacitKnowledge": []}
                """);
        llm.enqueue("{\"worthRecording\": false}");

        ImportJobView result = ingest.runImportSync("folder-1", spaceId, memberId);

        assertThat(result.status()).isEqualTo(JobStatus.DONE);
        assertThat(result.found()).isEqualTo(2);
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.documented()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        // 원문 보존: 문서가 만들어지고 본문 문단이 블록으로 들어갔다
        UUID doc1 = jdbcTemplate.queryForObject(
                "SELECT document_id FROM google_drive_ingest_log WHERE drive_file_id = 'doc-1'", UUID.class);
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM document WHERE id = ?", String.class, doc1))
                .isEqualTo("주간 회의록 7월");
        String blocks = String.join("\n", jdbcTemplate.queryForList(
                "SELECT content::text FROM block WHERE document_id = ? ORDER BY position", String.class, doc1));
        assertThat(blocks).contains("화요일로 확정했다.").contains("담당은 민지.");
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT source_type FROM block WHERE document_id = ?", String.class, doc1))
                .containsExactly("IMPORT");
        // 지식추출: doc-1에서만 decision 트리플 생성 (M20 — 사건 노드로 분해)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_triple WHERE document_id = ?", Integer.class, doc1))
                .isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT object FROM knowledge_triple WHERE document_id = ? AND predicate = '주체'",
                String.class, doc1)).isEqualTo("민지");

        // 재실행하면 전부 dedup으로 건너뛰고 LLM도 다시 안 부른다
        int callsBefore = llm.calls;
        ImportJobView rerun = ingest.runImportSync("folder-1", spaceId, memberId);
        assertThat(rerun.skippedDuplicate()).isEqualTo(2);
        assertThat(rerun.imported()).isZero();
        assertThat(llm.calls).isEqualTo(callsBefore);
    }

    @Test
    void oneFailingDocDoesNotBlockOthers() {
        driveGateway.docs.put("doc-empty", new String[]{"빈 회의록", "   "}); // 빈 본문 → 실패
        driveGateway.docs.put("doc-ok", new String[]{"정상 회의록", "내용이 있어요."});
        llm.enqueue("{\"worthRecording\": false}");

        ImportJobView result = ingest.runImportSync("folder-2", spaceId, memberId);

        assertThat(result.status()).isEqualTo(JobStatus.DONE);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.imported()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM google_drive_ingest_log WHERE drive_file_id = 'doc-ok'", Integer.class)).isEqualTo(1);
        // 실패한 문서는 로그가 없어(고아 없음) 재실행 시 다시 시도된다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM google_drive_ingest_log WHERE drive_file_id = 'doc-empty'", Integer.class)).isZero();
    }

    @Test
    void knowledgeSyncSkipsDocsWithTriplesAndBackfillsTheRest() {
        driveGateway.docs.put("doc-a", new String[]{"결정 없는 회의록", "그냥 잡담만 했어요."});
        driveGateway.docs.put("doc-b", new String[]{"백엔드 회의록", "배포는 목요일에 하기로 했다.\n담당은 상우."});
        // import 시점엔 두 문서 다 지식 추출 실패(또는 미시도)로 트리플이 없다고 가정
        llm.enqueue("{\"worthRecording\": false}");
        llm.enqueue("{\"worthRecording\": false}");
        ImportJobView imported = ingest.runImportSync("folder-3", spaceId, memberId);
        assertThat(imported.documented()).isZero();

        UUID docB = jdbcTemplate.queryForObject(
                "SELECT document_id FROM google_drive_ingest_log WHERE drive_file_id = 'doc-b'", UUID.class);
        // doc-a는 이미 지식 추출이 끝난 것처럼 트리플을 미리 심어 둔다 → 동기화에서 건너뛰어야 한다
        UUID docA = jdbcTemplate.queryForObject(
                "SELECT document_id FROM google_drive_ingest_log WHERE drive_file_id = 'doc-a'", UUID.class);
        jdbcTemplate.update("""
                INSERT INTO knowledge_triple (id, document_id, kind, statement, subject, predicate, object, channel_id, thread_ts, created_at)
                VALUES (?, ?, 'decision', '기존 트리플', '팀', '결정했다', '기존 결정', 'drive', 'doc-a', ?)
                """, UUID.randomUUID(), docA, Timestamp.from(Instant.now()));

        // Drive를 다시 부르지 않고 저장된 블록에서 재구성해 doc-b만 새로 추출한다
        int callsBefore = llm.calls;
        llm.enqueue("""
                {"worthRecording": true, "title": "배포 요일 결정",
                 "summary": ["배포 요일을 목요일로 확정했어요."],
                 "decisionPoints": [{"decision": "배포는 목요일에 해요.", "rationale": "", "alternatives": [],
                   "owner": "상우", "condition": "", "topic": "배포 요일"}],
                 "tacitKnowledge": []}
                """);
        ImportJobView sync = ingest.runKnowledgeSyncNow(false);

        assertThat(sync.status()).isEqualTo(JobStatus.DONE);
        assertThat(sync.found()).isEqualTo(2);
        assertThat(sync.documented()).isEqualTo(1);
        assertThat(sync.skippedDuplicate()).isEqualTo(1);
        assertThat(llm.calls).isEqualTo(callsBefore + 1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_triple WHERE document_id = ?", Integer.class, docA)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT subject FROM knowledge_triple WHERE document_id = ?", String.class, docB)).isEqualTo("상우");

        // 다시 돌리면 이제 둘 다 트리플이 있어 완전히 건너뛰고 LLM도 안 부른다
        int callsAfterFirstSync = llm.calls;
        ImportJobView resync = ingest.runKnowledgeSyncNow(false);
        assertThat(resync.skippedDuplicate()).isEqualTo(2);
        assertThat(resync.documented()).isZero();
        assertThat(llm.calls).isEqualTo(callsAfterFirstSync);
    }

    static class FakeDriveGateway implements GoogleDriveGateway {
        final Map<String, String[]> docs = new LinkedHashMap<>(); // fileId -> {name, text}

        void reset() {
            docs.clear();
        }

        @Override
        public List<DriveDoc> listGoogleDocs(String folderId) {
            return docs.entrySet().stream()
                    .map(e -> new DriveDoc(e.getKey(), e.getValue()[0]))
                    .toList();
        }

        @Override
        public String exportText(String fileId) {
            return docs.get(fileId)[1];
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
