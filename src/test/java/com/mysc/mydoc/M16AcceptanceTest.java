package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.ingest.drive.GoogleDriveGateway;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService.ImportJobView;
import com.mysc.mydoc.ingest.tiro.TiroIngestService;
import com.mysc.mydoc.ingest.tiro.TiroNoteSummary;
import com.mysc.mydoc.ingest.tiro.TiroPort;
import com.mysc.mydoc.ingest.tiro.TiroTranscriptParagraph;
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

/**
 * M16: 지식그래프 연결성 안정화.
 * ① 문장형 개체는 트리플에서 제외(스키마 강제), ② 별칭 정규화·병기 분리,
 * ③ Tiro 임포트도 Slack·Drive와 같은 추출 파이프라인을 탄다 + force 재동기화.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M16AcceptanceTest {

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
        FakeTiroPort fakeTiroPort() {
            return new FakeTiroPort();
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
    TiroIngestService tiro;

    @Autowired
    GoogleDriveIngestService sync;

    @Autowired
    FakeTiroPort tiroPort;

    @Autowired
    FakeThreadSummaryClient llm;

    UUID memberId;
    UUID spaceId;

    @BeforeEach
    void setup() {
        tiroPort.reset();
        llm.reset();
        ReflectionTestUtils.setField(sync, "currentJob", null);
        jdbcTemplate.update("DELETE FROM knowledge_triple");
        jdbcTemplate.update("DELETE FROM tiro_ingest_log");
        jdbcTemplate.update("DELETE FROM google_drive_ingest_log");
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, 'admin-m16@mysc.co.kr', 'Admin M16', 'ADMIN', ?)
                ON CONFLICT (email) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
        memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM member WHERE email = 'admin-m16@mysc.co.kr'", UUID.class);
        jdbcTemplate.update("""
                INSERT INTO space (id, slug, name, created_at)
                VALUES (?, 'm16-space', 'M16 Space', ?)
                ON CONFLICT (slug) DO NOTHING
                """, UUID.randomUUID(), Timestamp.from(Instant.now()));
        spaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM space WHERE slug = 'm16-space'", UUID.class);
    }

    @Test
    void tiroImportFeedsKnowledgeGraphWithNormalizedEntities() {
        seedNote("note-16", "AX 정례 회의");
        // 문장형 개체 1건(버려져야 함) + 병기 개체 1건(분리) + 별칭(수렴) 섞인 응답
        llm.enqueue("""
                {"worthRecording": true, "title": "AX 정례 회의 결정",
                 "summary": ["AX 정례 회의에서 담당과 협업 구조가 정리됐어요."],
                 "decisionPoints": [{"decision": "AX 실습 세션은 메리로 진행해요.", "rationale": "", "alternatives": [],
                   "owner": "혜윰, 보람", "condition": "", "topic": "AX 실습 세션"}],
                 "tacitKnowledge": [
                   {"kind": "convention", "statement": "AX 관련 협업은 MYSC가 주관해요.",
                    "triples": [{"subject": "엠와이소셜컴퍼니", "predicate": "주관한다", "object": "AX 협업"}]},
                   {"kind": "policy", "statement": "문장형 개체는 노드가 될 수 없어요.",
                    "triples": [{"subject": "MYSC는 다음 미팅부터 진행 상황을 PPT로 공유해요.",
                                 "predicate": "이다", "object": "정책"}]}
                 ]}
                """);

        UUID documentId = tiro.importNote("note-16", spaceId, memberId);

        // 원문 보존 + 로그
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tiro_ingest_log WHERE note_guid = 'note-16'", Integer.class)).isEqualTo(1);
        // 트리플: M20 결정은 사건 노드로 분해(주체 2건=혜윰/보람 + 대상 1건),
        // convention은 별칭 수렴(MYSC), 문장형 subject는 제외
        List<Map<String, Object>> triples = jdbcTemplate.queryForList(
                "SELECT kind, subject, predicate, object, channel_id FROM knowledge_triple WHERE document_id = ? ORDER BY subject",
                documentId);
        assertThat(triples).extracting(t -> t.get("channel_id")).containsOnly("tiro");
        // 병기 분리: 결정 사건의 주체가 혜윰·보람 둘로 갈라진다
        assertThat(triples).filteredOn(t -> "주체".equals(t.get("predicate")))
                .extracting(t -> t.get("object").toString()).containsExactlyInAnyOrder("혜윰", "보람");
        // 결정 대상은 명사구 그대로
        assertThat(triples).filteredOn(t -> "대상".equals(t.get("predicate")))
                .extracting(t -> t.get("object").toString()).containsOnly("AX 실습 세션");
        // 별칭 수렴 + 문장형 개체 제외는 그대로 유지
        assertThat(triples).extracting(t -> t.get("subject").toString()).contains("MYSC");
        assertThat(triples).extracting(t -> t.get("subject").toString())
                .noneMatch(s -> s.contains("PPT로 공유해요"));
    }

    @Test
    void duplicateTriplesInOneDocumentAreStoredOnce() {
        seedNote("note-dup", "중복 트리플 회의");
        // 서로 다른 암묵지 항목이 같은 트리플을 만들어도 그래프 차수/BM25 왜곡을 막기 위해 한 번만 저장한다
        llm.enqueue("""
                {"worthRecording": true, "title": "중복 트리플",
                 "summary": ["같은 트리플이 두 번 나왔어요."],
                 "decisionPoints": [],
                 "tacitKnowledge": [
                   {"kind": "convention", "statement": "온보딩은 보람이 담당해요.",
                    "triples": [{"subject": "보람", "predicate": "담당한다", "object": "온보딩"}]},
                   {"kind": "convention", "statement": "온보딩 담당은 보람이에요.",
                    "triples": [{"subject": "보람", "predicate": "담당한다", "object": "온보딩"}]}
                 ]}
                """);

        UUID documentId = tiro.importNote("note-dup", spaceId, memberId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_triple WHERE document_id = ? AND subject='보람' AND predicate='담당한다' AND object='온보딩'",
                Integer.class, documentId)).isEqualTo(1);
    }

    @Test
    void knowledgeSyncCoversTiroAndForceReextracts() {
        seedNote("note-17", "지식 없는 회의");
        llm.enqueue("{\"worthRecording\": false}"); // import 시점: 추출 결과 없음 → 트리플 0
        UUID documentId = tiro.importNote("note-17", spaceId, memberId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_triple WHERE document_id = ?", Integer.class, documentId)).isZero();

        // 일반 동기화: Tiro 로그도 대상에 포함되어 저장된 블록에서 재추출한다
        llm.enqueue("""
                {"worthRecording": true, "title": "지식 없는 회의 재추출",
                 "summary": ["재추출로 지식이 연결됐어요."],
                 "decisionPoints": [],
                 "tacitKnowledge": [{"kind": "gotcha", "statement": "재동기화는 Tiro 문서도 커버해요.",
                   "triples": [{"subject": "지식 동기화", "predicate": "커버한다", "object": "Tiro 문서"}]}]}
                """);
        ImportJobView view = sync.runKnowledgeSyncNow(false);
        assertThat(view.found()).isEqualTo(1);
        assertThat(view.documented()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT channel_id FROM knowledge_triple WHERE document_id = ?", String.class, documentId))
                .isEqualTo("tiro");

        // force=false면 이미 트리플이 있어 건너뛰고 LLM을 부르지 않는다
        int callsBefore = llm.calls;
        ImportJobView skip = sync.runKnowledgeSyncNow(false);
        assertThat(skip.skippedDuplicate()).isEqualTo(1);
        assertThat(llm.calls).isEqualTo(callsBefore);

        // force=true면 스키마 변경 후 전체 재구축용으로 다시 추출한다
        llm.enqueue("""
                {"worthRecording": true, "title": "강제 재추출",
                 "summary": ["강제 재추출로 트리플이 교체됐어요."],
                 "decisionPoints": [],
                 "tacitKnowledge": [{"kind": "policy", "statement": "force는 기존 트리플을 교체해요.",
                   "triples": [{"subject": "force 동기화", "predicate": "교체한다", "object": "기존 트리플"}]}]}
                """);
        ImportJobView forced = sync.runKnowledgeSyncNow(true);
        assertThat(forced.documented()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT subject FROM knowledge_triple WHERE document_id = ?", String.class, documentId))
                .isEqualTo("force 동기화");
    }

    private void seedNote(String guid, String title) {
        tiroPort.notes.put(guid, new TiroNoteSummary(
                guid, title, "https://tiro.ooo/n/" + guid, "2026-07-13T00:00:00Z", 600,
                List.of(), List.of(), "2026-07-13T00:00:10Z", "2026-07-13T00:10:10Z"));
        tiroPort.paragraphs.put(guid, List.of(new TiroTranscriptParagraph(
                "p1", "2026-07-13T00:00:10Z", "2026-07-13T00:01:00Z",
                "실습 세션과 협업 주관, 다음 미팅 공유 방식이 논의되었습니다.", "", false)));
    }

    static class FakeTiroPort implements TiroPort {
        final Map<String, TiroNoteSummary> notes = new LinkedHashMap<>();
        final Map<String, List<TiroTranscriptParagraph>> paragraphs = new LinkedHashMap<>();

        void reset() {
            notes.clear();
            paragraphs.clear();
        }

        @Override
        public List<TiroNoteSummary> listNotes(String keyword) {
            return List.copyOf(notes.values());
        }

        @Override
        public TiroNoteSummary getNote(String noteGuid) {
            return notes.get(noteGuid);
        }

        @Override
        public List<TiroTranscriptParagraph> getTranscriptParagraphs(String noteGuid) {
            return paragraphs.getOrDefault(noteGuid, List.of());
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
