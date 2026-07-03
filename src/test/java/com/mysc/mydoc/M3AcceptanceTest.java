package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.ingest.SystemMemberInitializer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
class M3AcceptanceTest {
    private static final String INTERNAL_TOKEN = "internal-test-token-32-bytes-long";
    private static final String COLLAB_SECRET = "collab-secret-32-bytes-minimum-value";
    private static final HttpServer kickServer = httpServer();
    private static final List<String> kickBodies = new CopyOnWriteArrayList<>();

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
        registry.add("mydoc.internal-service-token", () -> INTERNAL_TOKEN);
        registry.add("mydoc.collab-jwt-secret", () -> COLLAB_SECRET);
        registry.add("mydoc.editing-plane-url", () -> "http://localhost:" + kickServer.getAddress().getPort());
    }

    @BeforeAll
    static void startKickServer() {
        kickServer.start();
    }

    @AfterAll
    static void stopKickServer() {
        kickServer.stop(0);
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    UUID adminId;
    UUID memberId;
    UUID spaceId;

    @BeforeEach
    void setup() {
        kickBodies.clear();
        adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, adminId, "admin-m3@mysc.co.kr", "Admin M3", "ADMIN", Timestamp.from(Instant.now()));
        spaceId = id(exchange("/api/spaces", HttpMethod.POST, Map.of("slug", "m3-space", "name", "M3 Space"), memberEntity(Map.of("slug", "m3-space", "name", "M3 Space"), adminId)));
        memberId = id(exchange("/api/members", HttpMethod.POST, Map.of("email", "member-m3@mysc.co.kr", "displayName", "Member M3", "role", "MEMBER"), memberEntity(Map.of("email", "member-m3@mysc.co.kr", "displayName", "Member M3", "role", "MEMBER"), adminId)));
    }

    @Test
    void m3JavaAcceptanceScenario() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('yjs_update', 'yjs_snapshot')
                """, String.class)).containsExactlyInAnyOrder("yjs_update", "yjs_snapshot");

        UUID documentId = createDocument("편집 문서");

        ResponseEntity<Map> tokenResponse = exchange(
                "/api/internal/collab-tokens",
                HttpMethod.POST,
                Map.of("documentId", documentId.toString()),
                memberEntity(Map.of("documentId", documentId.toString()), memberId)
        );
        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResponse.getBody()).containsEntry("expiresInSeconds", 3600);
        Map<String, Object> payload = jwtPayload((String) tokenResponse.getBody().get("token"));
        assertThat(payload).containsEntry("sub", memberId.toString());
        assertThat(payload).containsEntry("doc", documentId.toString());
        assertThat(payload).containsEntry("perm", "write");

        ResponseEntity<Map> missingDocumentIdToken = exchange(
                "/api/internal/collab-tokens",
                HttpMethod.POST,
                Map.of(),
                memberEntity(Map.of(), memberId)
        );
        assertThat(missingDocumentIdToken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingDocumentIdToken.getBody()).containsEntry("status", 400);

        ResponseEntity<Map> forgedAuth = restTemplate.exchange(
                "/api/internal/collab-tokens",
                HttpMethod.GET,
                bearerEntity(null, INTERNAL_TOKEN),
                Map.class
        );
        assertThat(forgedAuth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Void> snapshot = restTemplate.exchange(
                "/api/internal/snapshots",
                HttpMethod.POST,
                bearerEntity(Map.of("documentId", documentId.toString(), "editorId", memberId.toString(), "blocks", List.of(block("PARAGRAPH", "스냅샷 본문"))), INTERNAL_TOKEN),
                Void.class
        );
        assertThat(snapshot.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> internalBlocks = restTemplate.exchange(
                "/api/internal/documents/" + documentId + "/blocks",
                HttpMethod.GET,
                bearerEntity(null, INTERNAL_TOKEN),
                List.class
        );
        assertThat(internalBlocks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(internalBlocks.getBody()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT cause FROM revision WHERE document_id = ?", String.class, documentId))
                .isEqualTo("SNAPSHOT_COMMIT");

        Map<String, Object> snapshotWithoutEditorBody = new HashMap<>();
        snapshotWithoutEditorBody.put("documentId", documentId.toString());
        snapshotWithoutEditorBody.put("editorId", null);
        snapshotWithoutEditorBody.put("blocks", List.of(block("PARAGRAPH", "시스템 멤버 스냅샷")));
        ResponseEntity<Void> snapshotWithoutEditor = restTemplate.exchange(
                "/api/internal/snapshots",
                HttpMethod.POST,
                bearerEntity(snapshotWithoutEditorBody, INTERNAL_TOKEN),
                Void.class
        );
        assertThat(snapshotWithoutEditor.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        UUID systemMemberId = jdbcTemplate.queryForObject(
                "SELECT id FROM member WHERE email = ?",
                UUID.class,
                SystemMemberInitializer.SYSTEM_MEMBER_EMAIL
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT editor_id
                FROM revision
                WHERE document_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, UUID.class, documentId)).isEqualTo(systemMemberId);

        List<HttpStatusCode> concurrentSnapshotStatuses = IntStream.range(0, 8)
                .parallel()
                .mapToObj(index -> restTemplate.exchange(
                        "/api/internal/snapshots",
                        HttpMethod.POST,
                        bearerEntity(Map.of(
                                "documentId", documentId.toString(),
                                "editorId", memberId.toString(),
                                "blocks", List.of(block("PARAGRAPH", "동시 스냅샷 " + index))
                        ), INTERNAL_TOKEN),
                        Void.class
                ).getStatusCode())
                .toList();
        assertThat(concurrentSnapshotStatuses).allMatch(HttpStatusCode::is2xxSuccessful);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM block WHERE document_id = ?", Integer.class, documentId))
                .isEqualTo(1);

        ResponseEntity<Void> archive = restTemplate.exchange(
                "/api/documents/" + documentId + "/archive",
                HttpMethod.POST,
                memberEntity(null, memberId),
                Void.class
        );
        assertThat(archive.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(kickBodies).anySatisfy(body -> {
            assertThat(body).contains(documentId.toString());
            assertThat(body).contains(memberId.toString());
        });
    }

    private UUID createDocument(String title) {
        return id(exchange("/api/documents", HttpMethod.POST, Map.of("spaceId", spaceId.toString(), "title", title), memberEntity(Map.of("spaceId", spaceId.toString(), "title", title), memberId)));
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, HttpEntity<Object> entity) {
        return restTemplate.exchange(path, method, entity, Map.class);
    }

    private HttpEntity<Object> memberEntity(Object body, UUID memberId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Member-Id", memberId.toString());
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Object> bearerEntity(Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    private UUID id(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private Map<String, Object> jwtPayload(String token) throws IOException {
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return objectMapper.readValue(payload, Map.class);
    }

    private Map<String, Object> block(String type, String text) {
        return Map.of(
                "type", type,
                "content", Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text))),
                "sourceType", "MANUAL"
        );
    }

    private static HttpServer httpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/kick", exchange -> {
                if (!("Bearer " + INTERNAL_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                kickBodies.add(new String(exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
