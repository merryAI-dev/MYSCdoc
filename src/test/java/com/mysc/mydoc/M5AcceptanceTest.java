package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mysc.mydoc.ai.CorrectionClient;
import com.mysc.mydoc.ingest.SlackDmPort;
import com.mysc.mydoc.service.StalenessJob;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
class M5AcceptanceTest {

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
        registry.add("mydoc.document-base-url", () -> "http://mydoc.test");
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeSlackDm fakeSlackDm() {
            return new FakeSlackDm();
        }

        @Bean
        @Primary
        FakeCorrectionClient fakeCorrectionClient() {
            return new FakeCorrectionClient();
        }
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StalenessJob stalenessJob;

    @Autowired
    FakeSlackDm slackDm;

    @Autowired
    FakeCorrectionClient correctionClient;

    UUID adminId;
    UUID memberId;
    UUID spaceId;

    @BeforeEach
    void setup() {
        slackDm.messages.clear();
        correctionClient.responses.clear();
        adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, adminId, "admin-m5@mysc.co.kr", "Admin M5", "ADMIN", Timestamp.from(Instant.now()));
        spaceId = id(exchange("/api/spaces", HttpMethod.POST, Map.of("slug", "m5-space", "name", "M5 Space"), adminId));
        memberId = id(exchange("/api/members", HttpMethod.POST, Map.of("email", "member-m5@mysc.co.kr", "displayName", "Member M5", "role", "MEMBER"), adminId));
        jdbcTemplate.update("UPDATE member SET slack_user_id = 'U-M5' WHERE id = ?", memberId);
    }

    @Test
    void m5AcceptanceScenario() {
        UUID verifiedOldDoc = createActiveDocument("검증 오래된 문서");
        jdbcTemplate.update("UPDATE document SET verified_at = now() - interval '100 days' WHERE id = ?", verifiedOldDoc);

        stalenessJob.run();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, verifiedOldDoc)).isEqualTo("STALE");
        assertThat(slackDm.messages).hasSize(1);
        assertThat(slackDm.messages.get(0)).contains("검증 오래된 문서");
        assertThat(slackDm.messages.get(0)).contains("http://mydoc.test/d/" + verifiedOldDoc);

        stalenessJob.run();
        assertThat(slackDm.messages).hasSize(1);

        ResponseEntity<Map> verify = exchange("/api/documents/" + verifiedOldDoc + "/verify", HttpMethod.POST, null, memberId);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, verifiedOldDoc)).isEqualTo("ACTIVE");

        UUID neverVerifiedDoc = createActiveDocument("검증 이력 없는 문서");
        jdbcTemplate.update("UPDATE document SET verified_at = NULL, created_at = now() - interval '100 days' WHERE id = ?", neverVerifiedDoc);
        stalenessJob.run();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, neverVerifiedDoc)).isEqualTo("STALE");

        UUID dmFailureDoc = createActiveDocument("DM 실패 문서");
        jdbcTemplate.update("UPDATE document SET verified_at = now() - interval '100 days' WHERE id = ?", dmFailureDoc);
        slackDm.fail = true;
        assertThatCode(() -> stalenessJob.run()).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, dmFailureDoc)).isEqualTo("STALE");
        slackDm.fail = false;

        UUID noSlackMemberId = id(exchange("/api/members", HttpMethod.POST, Map.of("email", "no-slack-m5@mysc.co.kr", "displayName", "No Slack M5", "role", "MEMBER"), adminId));
        UUID noSlackDoc = createActiveDocument("Slack ID 없는 문서", noSlackMemberId);
        jdbcTemplate.update("UPDATE document SET verified_at = now() - interval '100 days' WHERE id = ?", noSlackDoc);
        int dmCountBeforeNoSlack = slackDm.messages.size();
        stalenessJob.run();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, noSlackDoc)).isEqualTo("STALE");
        assertThat(slackDm.messages).hasSize(dmCountBeforeNoSlack);

        ResponseEntity<Map> stale = exchange("/api/documents/stale?spaceId=" + spaceId, HttpMethod.GET, null, memberId);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = (List<Map<String, Object>>) stale.getBody().get("content");
        assertThat(content).extracting(item -> item.get("id")).contains(neverVerifiedDoc.toString());

        correctionClient.responses.add("""
                {"score":123,"findings":[null,{"blockPosition":1,"original":"x","suggestion":"y","reason":"분류 누락"},{"category":"SPELLING","blockPosition":1,"suggestion":"y","reason":"원문 누락"},{"category":"STRUCTURE","blockPosition":1,"original":"x","suggestion":" ","reason":"수정안 공백"},{"category":"TERMINOLOGY","blockPosition":1,"original":"단일 진실 공급원","suggestion":"믿고 참고할 기준","reason":"직역투를 줄이면 더 자연스러워요."},{"category":"TYPO","blockPosition":1,"original":"x","suggestion":"y","reason":"범위 밖"},{"category":"SPELLING","blockPosition":99,"original":"x","suggestion":"y","reason":"범위 밖"}]}
                """);
        ResponseEntity<Map> corrections = exchange("/api/documents/" + neverVerifiedDoc + "/corrections", HttpMethod.POST, null, memberId);
        assertThat(corrections.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(corrections.getBody()).containsEntry("score", 100);
        List<Map<String, Object>> findings = (List<Map<String, Object>>) corrections.getBody().get("findings");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0)).containsEntry("blockPosition", 1);
        assertThat(findings.get(0)).containsEntry("category", "TERMINOLOGY");

        correctionClient.responses.add(null);
        ResponseEntity<Map> nullCorrection = exchange("/api/documents/" + neverVerifiedDoc + "/corrections", HttpMethod.POST, null, memberId);
        assertThat(nullCorrection.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private UUID createActiveDocument(String title) {
        return createActiveDocument(title, memberId);
    }

    private UUID createActiveDocument(String title, UUID ownerId) {
        UUID documentId = id(exchange("/api/documents", HttpMethod.POST, Map.of("spaceId", spaceId.toString(), "title", title), ownerId));
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/documents/" + documentId + "/blocks",
                HttpMethod.PUT,
                entity(Map.of("blocks", List.of(block("HEADING1", "제목"), block("PARAGRAPH", "단일 진실 공급원 역할을 해요."))), ownerId),
                Void.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return documentId;
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, UUID memberId) {
        return restTemplate.exchange(path, method, entity(body, memberId), Map.class);
    }

    private HttpEntity<Object> entity(Object body, UUID memberId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Member-Id", memberId.toString());
        return new HttpEntity<>(body, headers);
    }

    private UUID id(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private Map<String, Object> block(String type, String text) {
        return Map.of(
                "type", type,
                "content", type.startsWith("HEADING")
                        ? Map.of("type", "heading", "attrs", Map.of("level", 1), "content", List.of(Map.of("type", "text", "text", text)))
                        : Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text))),
                "sourceType", "MANUAL"
        );
    }

    static class FakeSlackDm implements SlackDmPort {
        final List<String> messages = new ArrayList<>();
        boolean fail;

        @Override
        public void sendDm(String slackUserId, String text) {
            if (fail) {
                throw new IllegalStateException("dm failed");
            }
            messages.add(text);
        }
    }

    static class FakeCorrectionClient implements CorrectionClient {
        final List<String> responses = new ArrayList<>();
        int index;

        @Override
        public String review(String systemPrompt, String userPrompt) {
            return responses.get(index++);
        }
    }
}
