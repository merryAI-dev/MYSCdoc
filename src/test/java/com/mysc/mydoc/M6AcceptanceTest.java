package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.ai.EmbeddingPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.springframework.http.MediaType;
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
class M6AcceptanceTest {

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
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingPort fakeEmbeddingPort() {
            return new EmbeddingPort() {
                @Override
                public float[] embed(String text) {
                    float[] vector = new float[1536];
                    vector[0] = text.toLowerCase().contains("account") ? 1.0f : 0.1f;
                    return vector;
                }

                @Override
                public List<float[]> embedAll(List<String> texts) {
                    return texts.stream().map(this::embed).toList();
                }
            };
        }
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    Map<UUID, String> mcpSessions = new HashMap<>();

    UUID adminId;
    UUID ownerId;
    UUID otherId;
    UUID spaceId;

    @BeforeEach
    void setup() {
        adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, adminId, "admin-m6@mysc.co.kr", "Admin M6", "ADMIN", Timestamp.from(Instant.now()));
        spaceId = id(api("/api/spaces", HttpMethod.POST, Map.of("slug", "m6-space", "name", "M6 Space"), adminId));
        ownerId = id(api("/api/members", HttpMethod.POST, Map.of("email", "owner-m6@mysc.co.kr", "displayName", "Owner M6", "role", "MEMBER"), adminId));
        otherId = id(api("/api/members", HttpMethod.POST, Map.of("email", "other-m6@mysc.co.kr", "displayName", "Other M6", "role", "MEMBER"), adminId));
    }

    @Test
    void m6AcceptanceScenario() throws Exception {
        ResponseEntity<Map> unauthenticatedMcp = restTemplate.exchange(
                "/mcp",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of())),
                Map.class
        );
        assertThat(unauthenticatedMcp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthenticatedMcp.getBody()).containsEntry("status", 401);

        UUID documentId = createDocument("온보딩 가이드");
        putBlocks(documentId, List.of(
                block("HEADING1", "계정 발급"),
                block("PARAGRAPH", "account setup은 IT팀에 요청하세요."),
                block("BULLET_LIST", "체크리스트"),
                block("ORDERED_LIST", "첫 단계"),
                block("CODE", "echo hi"),
                block("QUOTE", "중요"),
                block("TABLE", "ignored"),
                block("IMAGE", "https://mydoc.test/image.png")
        ));
        waitForChunkCount(documentId, 1);
        jdbcTemplate.update("UPDATE document SET status = 'STALE' WHERE id = ?", documentId);

        Map<String, Object> tools = mcp("tools/list", Map.of(), ownerId);
        List<Map<String, Object>> toolList = (List<Map<String, Object>>) ((Map<String, Object>) tools.get("result")).get("tools");
        assertThat(toolList).extracting(tool -> tool.get("name"))
                .containsExactly("search_documents", "get_document", "create_draft", "verify_document");

        String searchText = toolText(mcp("tools/call", Map.of(
                "name", "search_documents",
                "arguments", Map.of("query", "account", "spaceSlug", "m6-space", "limit", 5)
        ), ownerId));
        assertThat(searchText).contains("URL: http://mydoc.test/d/" + documentId);
        assertThat(searchText).contains("⚠️ 이 문서는 오래됐을 수 있어요 (STALE)");

        String documentText = toolText(mcp("tools/call", Map.of("name", "get_document", "arguments", Map.of("documentId", documentId.toString())), ownerId));
        assertThat(documentText).contains("title: 온보딩 가이드");
        assertThat(documentText).contains("# 계정 발급");
        assertThat(documentText).contains("- 체크리스트");
        assertThat(documentText).contains("1. 첫 단계");
        assertThat(documentText).contains("```\necho hi\n```");
        assertThat(documentText).contains("> 중요");
        assertThat(documentText).contains("| 이름 | 값 |");
        assertThat(documentText).contains("| --- | --- |");
        assertThat(documentText).contains("![image](https://mydoc.test/image.png)");

        String draftText = toolText(mcp("tools/call", Map.of(
                "name", "create_draft",
                "arguments", Map.of(
                        "spaceSlug", "m6-space",
                        "title", "AI 초안",
                        "markdown", "# 초안\n본문입니다.\n- 항목\n1. 순서\n```\n코드\n```\n| 열 | 값 |\n| --- | --- |\n| A | B |\n> 인용\n![image](https://mydoc.test/draft.png)"
                )
        ), ownerId));
        assertThat(draftText).contains("초안이 생성됐어요: http://mydoc.test/d/");
        UUID draftId = jdbcTemplate.queryForObject("SELECT id FROM document WHERE title = 'AI 초안'", UUID.class);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, draftId)).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT source_type FROM block WHERE document_id = ?", String.class, draftId))
                .containsExactly("AI_DRAFT");
        assertThat(jdbcTemplate.queryForList("SELECT type FROM block WHERE document_id = ? ORDER BY position", String.class, draftId))
                .containsExactly("HEADING1", "PARAGRAPH", "BULLET_LIST", "ORDERED_LIST", "CODE", "TABLE", "QUOTE", "IMAGE");

        Map<String, Object> invalidDraft = mcp("tools/call", Map.of(
                "name", "create_draft",
                "arguments", Map.of("spaceSlug", "m6-space", "title", "인자 누락")
        ), ownerId);
        assertThat((Map<String, Object>) invalidDraft.get("result")).containsEntry("isError", true);
        assertThat(toolText(invalidDraft)).contains("markdown");

        String verifyText = toolText(mcp("tools/call", Map.of("name", "verify_document", "arguments", Map.of("documentId", documentId.toString())), otherId));
        assertThat(verifyText).isEqualTo("owner만 검증할 수 있어요");

        UUID missing = UUID.randomUUID();
        String missingText = toolText(mcp("tools/call", Map.of("name", "get_document", "arguments", Map.of("documentId", missing.toString())), ownerId));
        assertThat(missingText).contains("문서를 찾을 수 없어요");
    }

    private UUID createDocument(String title) {
        return id(api("/api/documents", HttpMethod.POST, Map.of("spaceId", spaceId.toString(), "title", title), ownerId));
    }

    private void putBlocks(UUID documentId, List<Map<String, Object>> blocks) {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/documents/" + documentId + "/blocks",
                HttpMethod.PUT,
                entity(Map.of("blocks", blocks), ownerId),
                Void.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private Map<String, Object> mcp(String method, Map<String, Object> params, UUID memberId) throws Exception {
        String sessionId = mcpSessions.computeIfAbsent(memberId, this::initializeMcp);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        request.put("params", params);
        ResponseEntity<String> response = restTemplate.exchange("/mcp", HttpMethod.POST, mcpEntity(request, memberId, sessionId), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parseMcpResponse(response);
    }

    private String initializeMcp(UUID memberId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "initialize");
        request.put("params", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "M6AcceptanceTest", "version", "0.0.1")
        ));
        ResponseEntity<String> response = restTemplate.exchange("/mcp", HttpMethod.POST, mcpEntity(request, memberId, null), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = response.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.put("params", Map.of());
        ResponseEntity<String> initialized = restTemplate.exchange("/mcp", HttpMethod.POST, mcpEntity(notification, memberId, sessionId), String.class);
        assertThat(initialized.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return sessionId;
    }

    private Map<String, Object> parseMcpResponse(ResponseEntity<String> response) throws Exception {
        String body = response.getBody();
        assertThat(body).isNotBlank();
        if (response.getHeaders().getContentType() != null
                && MediaType.APPLICATION_JSON.includes(response.getHeaders().getContentType())) {
            return objectMapper.readValue(body, Map.class);
        }
        String data = body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .collect(Collectors.joining("\n"));
        assertThat(data).isNotBlank();
        return objectMapper.readValue(data, Map.class);
    }

    private String toolText(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return (String) content.get(0).get("text");
    }

    private ResponseEntity<Map> api(String path, HttpMethod method, Object body, UUID memberId) {
        return restTemplate.exchange(path, method, entity(body, memberId), Map.class);
    }

    private HttpEntity<Object> entity(Object body, UUID memberId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Member-Id", memberId.toString());
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Object> mcpEntity(Object body, UUID memberId, String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Member-Id", memberId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        return new HttpEntity<>(body, headers);
    }

    private UUID id(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private Map<String, Object> block(String type, String text) {
        Map<String, Object> content = switch (type) {
            case "HEADING1", "HEADING2", "HEADING3" ->
                    Map.of("type", "heading", "attrs", Map.of("level", 1), "content", List.of(Map.of("type", "text", "text", text)));
            case "CODE" ->
                    Map.of("type", "codeBlock", "content", List.of(Map.of("type", "text", "text", text)));
            case "TABLE" ->
                    Map.of("type", "table", "rows", List.of(List.of("이름", "값"), List.of("계정", "IT")));
            case "IMAGE" ->
                    Map.of("type", "image", "attrs", Map.of("src", text));
            default ->
                    Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text)));
        };
        return Map.of(
                "type", type,
                "content", content,
                "sourceType", "MANUAL"
        );
    }

    private void waitForChunkCount(UUID documentId, int expected) throws Exception {
        for (int i = 0; i < 60; i++) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk WHERE document_id = ?", Integer.class, documentId);
            if (count != null && count == expected) {
                return;
            }
            Thread.sleep(250);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk WHERE document_id = ?", Integer.class, documentId)).isEqualTo(expected);
    }
}
