package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.ingest.ThreadSummaryClient;
import com.mysc.mydoc.ingest.tiro.TiroIngestService;
import com.mysc.mydoc.ingest.tiro.TiroNoteSummary;
import com.mysc.mydoc.ingest.tiro.TiroPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class M8AcceptanceTest {

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
        FakeSummaryClient fakeSummaryClient() {
            return new FakeSummaryClient();
        }
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TiroIngestService ingest;

    @Autowired
    FakeTiroPort tiro;

    @Autowired
    FakeSummaryClient summaryClient;

    UUID adminId;
    UUID spaceId;

    @BeforeEach
    void setup() {
        tiro.reset();
        summaryClient.reset();
        adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, adminId, "admin-m8@mysc.co.kr", "Admin M8", "ADMIN", Timestamp.from(Instant.now()));
        Map<String, Object> space = restTemplate.exchange(
                "/api/spaces", HttpMethod.POST, entity(Map.of("slug", "m8-space", "name", "M8 Space"), adminId), Map.class
        ).getBody();
        spaceId = UUID.fromString((String) space.get("id"));
    }

    @Test
    void m8AcceptanceScenario() {
        tiro.notes.put("note-1", new TiroNoteSummary("note-1", "회의: 계정 권한", "https://tiro.ooo/n/1", "2026-07-01T00:00:00Z", 600));
        tiro.transcripts.put("note-1", "권한 사용자를 삭제하기로 했습니다.");
        summaryClient.responses.add("""
                {"title":"계정 권한 정리","sections":[{"heading":"결정 사항","paragraphs":["권한 사용자 항목을 삭제하기로 했어요."]}]}
                """);

        // 1. 픽커 목록 (REST) — keyword 없이 호출
        ResponseEntity<List> listResponse = restTemplate.exchange(
                "/api/integrations/tiro/notes", HttpMethod.GET, entity(null, adminId), List.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);

        // 2. 가져오기 (REST) — DRAFT 문서 생성
        ResponseEntity<Map> importResponse = restTemplate.exchange(
                "/api/integrations/tiro/notes/note-1/import", HttpMethod.POST,
                entity(Map.of("spaceId", spaceId.toString()), adminId), Map.class);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID documentId = UUID.fromString((String) importResponse.getBody().get("documentId"));

        assertThat(jdbcTemplate.queryForObject("SELECT title FROM document WHERE id = ?", String.class, documentId))
                .isEqualTo("계정 권한 정리");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, documentId))
                .isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("SELECT owner_id FROM document WHERE id = ?", UUID.class, documentId))
                .isEqualTo(adminId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tiro_ingest_log WHERE note_guid = 'note-1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT type FROM block WHERE document_id = ? ORDER BY position", String.class, documentId))
                .containsExactly("HEADING2", "PARAGRAPH", "PARAGRAPH");
        assertThat(jdbcTemplate.queryForObject("SELECT content::text FROM block WHERE document_id = ? ORDER BY position DESC LIMIT 1", String.class, documentId))
                .contains("출처: https://tiro.ooo/n/1");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT source_type FROM block WHERE document_id = ?", String.class, documentId))
                .containsExactly("IMPORT");

        // 3. 같은 노트 재가져오기 — 새 문서를 만들지 않고 기존 문서를 반환한다 (중복 방지)
        ResponseEntity<Map> secondImport = restTemplate.exchange(
                "/api/integrations/tiro/notes/note-1/import", HttpMethod.POST,
                entity(Map.of("spaceId", spaceId.toString()), adminId), Map.class);
        assertThat(secondImport.getBody().get("documentId")).isEqualTo(documentId.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document WHERE title = '계정 권한 정리'", Integer.class)).isEqualTo(1);

        // 4. 전사 없는 노트 — 실패해야 한다
        tiro.notes.put("note-empty", new TiroNoteSummary("note-empty", "빈 회의", "https://tiro.ooo/n/2", null, null));
        tiro.transcripts.put("note-empty", "");
        assertThatThrownBy(() -> ingest.importNote("note-empty", spaceId, adminId)).isInstanceOf(ValidationException.class);
    }

    private HttpEntity<Object> entity(Object body, UUID memberId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Member-Id", memberId.toString());
        return new HttpEntity<>(body, headers);
    }

    static class FakeTiroPort implements TiroPort {
        final Map<String, TiroNoteSummary> notes = new HashMap<>();
        final Map<String, String> transcripts = new HashMap<>();

        void reset() {
            notes.clear();
            transcripts.clear();
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
        public String getTranscriptText(String noteGuid) {
            return transcripts.getOrDefault(noteGuid, "");
        }
    }

    static class FakeSummaryClient implements ThreadSummaryClient {
        final List<String> responses = new ArrayList<>();
        int index;

        void reset() {
            responses.clear();
            index = 0;
        }

        @Override
        public String summarize(String systemPrompt, String userPrompt) {
            return responses.get(index++);
        }
    }
}
