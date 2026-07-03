package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
class M1AcceptanceTest {

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

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID adminId;

    @BeforeEach
    void seedAdmin() {
        adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, adminId, "admin@mysc.co.kr", "Admin", "ADMIN", Timestamp.from(Instant.now()));
    }

    @Test
    void m1AcceptanceScenario() {
        ResponseEntity<Map> space = exchange(
                "/api/spaces",
                HttpMethod.POST,
                Map.of("slug", "axr-team", "name", "AXR팀"),
                adminId
        );
        assertThat(space.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID spaceId = id(space);

        ResponseEntity<Map> duplicateSpace = exchange(
                "/api/spaces",
                HttpMethod.POST,
                Map.of("slug", "axr-team", "name", "AXR팀 중복"),
                adminId
        );
        assertThat(duplicateSpace.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(duplicateSpace.getBody()).containsEntry("status", 400);

        ResponseEntity<Map> member = exchange(
                "/api/members",
                HttpMethod.POST,
                Map.of("email", "member@mysc.co.kr", "displayName", "Member", "role", "MEMBER"),
                adminId
        );
        assertThat(member.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID memberId = id(member);

        ResponseEntity<Map> me = exchange("/api/members/me", HttpMethod.GET, null, memberId);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("email", "member@mysc.co.kr");

        ResponseEntity<Map> forbiddenSpaceCreate = exchange(
                "/api/spaces",
                HttpMethod.POST,
                Map.of("slug", "member-space", "name", "Member Space"),
                memberId
        );
        assertThat(forbiddenSpaceCreate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> forbiddenMemberCreate = exchange(
                "/api/members",
                HttpMethod.POST,
                Map.of("email", "forbidden@mysc.co.kr", "displayName", "Forbidden", "role", "MEMBER"),
                memberId
        );
        assertThat(forbiddenMemberCreate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> document = exchange(
                "/api/documents",
                HttpMethod.POST,
                Map.of("spaceId", spaceId.toString(), "title", "온보딩 가이드"),
                memberId
        );
        assertThat(document.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(document.getBody()).containsEntry("status", "DRAFT");
        UUID documentId = id(document);

        Map<String, Object> nullSpaceDocumentBody = new LinkedHashMap<>();
        nullSpaceDocumentBody.put("spaceId", null);
        nullSpaceDocumentBody.put("title", "스페이스 누락 문서");
        ResponseEntity<Map> nullSpaceDocument = exchange(
                "/api/documents",
                HttpMethod.POST,
                nullSpaceDocumentBody,
                memberId
        );
        assertThat(nullSpaceDocument.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nullSpaceDocument.getBody()).containsEntry("status", 400);

        UUID emptyDocumentId = id(exchange(
                "/api/documents",
                HttpMethod.POST,
                Map.of("spaceId", spaceId.toString(), "title", "빈 문서"),
                memberId
        ));
        ResponseEntity<Void> emptyBlocks = exchangeVoid(
                "/api/documents/" + emptyDocumentId + "/blocks",
                HttpMethod.PUT,
                Map.of("blocks", List.of()),
                memberId
        );
        assertThat(emptyBlocks.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map> emptyDocument = exchange("/api/documents/" + emptyDocumentId, HttpMethod.GET, null, memberId);
        assertThat(emptyDocument.getBody()).containsEntry("status", "ACTIVE");
        assertThat((List<Map<String, Object>>) emptyDocument.getBody().get("blocks")).isEmpty();

        ResponseEntity<Map> renamed = exchange(
                "/api/documents/" + documentId + "/title",
                HttpMethod.PUT,
                Map.of("title", "온보딩 가이드 v2"),
                memberId
        );
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody()).containsEntry("title", "온보딩 가이드 v2");

        ResponseEntity<Void> firstBlocks = exchangeVoid(
                "/api/documents/" + documentId + "/blocks",
                HttpMethod.PUT,
                Map.of("blocks", blocks("계정 발급", "IT팀에 요청하세요.")),
                memberId
        );
        assertThat(firstBlocks.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> fetched = exchange("/api/documents/" + documentId, HttpMethod.GET, null, memberId);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).containsEntry("status", "ACTIVE");
        List<Map<String, Object>> fetchedBlocks = (List<Map<String, Object>>) fetched.getBody().get("blocks");
        assertThat(fetchedBlocks).hasSize(2);
        assertThat(fetchedBlocks.get(0)).containsEntry("position", 0);
        assertThat(fetchedBlocks.get(1)).containsEntry("position", 1);

        ResponseEntity<Void> secondBlocks = exchangeVoid(
                "/api/documents/" + documentId + "/blocks",
                HttpMethod.PUT,
                Map.of("blocks", blocks("계정 재발급", "계정은 매니저에게 요청하세요.")),
                memberId
        );
        assertThat(secondBlocks.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> revisions = exchange("/api/documents/" + documentId + "/revisions", HttpMethod.GET, null, memberId);
        assertThat(revisions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revisions.getBody()).containsEntry("totalElements", 2);
        List<Map<String, Object>> revisionSummaries = (List<Map<String, Object>>) revisions.getBody().get("content");
        assertThat(revisionSummaries.get(0)).doesNotContainKey("snapshot");
        String revisionId = (String) revisionSummaries.get(0).get("id");
        ResponseEntity<Map> revisionDetail = exchange("/api/revisions/" + revisionId, HttpMethod.GET, null, memberId);
        assertThat(revisionDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revisionDetail.getBody()).containsKey("snapshot");

        Map<String, Object> nullBlocksBody = new LinkedHashMap<>();
        nullBlocksBody.put("blocks", null);
        ResponseEntity<Map> nullBlocks = exchange(
                "/api/documents/" + documentId + "/blocks",
                HttpMethod.PUT,
                nullBlocksBody,
                memberId
        );
        assertThat(nullBlocks.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nullBlocks.getBody()).containsEntry("status", 400);

        List<Object> blocksWithNull = new ArrayList<>();
        blocksWithNull.add(null);
        ResponseEntity<Map> nullBlock = exchange(
                "/api/documents/" + documentId + "/blocks",
                HttpMethod.PUT,
                Map.of("blocks", blocksWithNull),
                memberId
        );
        assertThat(nullBlock.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nullBlock.getBody()).containsEntry("status", 400);

        ResponseEntity<Map> invalidBlockType = exchange(
                "/api/documents/" + documentId + "/blocks",
                HttpMethod.PUT,
                Map.of("blocks", List.of(block("UNKNOWN", Map.of("type", "paragraph")))),
                memberId
        );
        assertThat(invalidBlockType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalidBlockType.getBody()).containsEntry("status", 400);

        ResponseEntity<Map> otherMember = exchange(
                "/api/members",
                HttpMethod.POST,
                Map.of("email", "other@mysc.co.kr", "displayName", "Other", "role", "MEMBER"),
                adminId
        );
        UUID otherMemberId = id(otherMember);
        ResponseEntity<Map> forbiddenVerify = exchange(
                "/api/documents/" + documentId + "/verify",
                HttpMethod.POST,
                null,
                otherMemberId
        );
        assertThat(forbiddenVerify.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> ownerVerify = exchange(
                "/api/documents/" + documentId + "/verify",
                HttpMethod.POST,
                null,
                memberId
        );
        assertThat(ownerVerify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerVerify.getBody().get("verifiedAt")).isNotNull();

        ResponseEntity<Map> changedOwner = exchange(
                "/api/documents/" + documentId + "/owner",
                HttpMethod.PUT,
                Map.of("ownerId", otherMemberId.toString()),
                memberId
        );
        assertThat(changedOwner.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) changedOwner.getBody().get("owner")).containsEntry("id", otherMemberId.toString());

        Map<String, Object> nullOwnerBody = new LinkedHashMap<>();
        nullOwnerBody.put("ownerId", null);
        ResponseEntity<Map> nullOwner = exchange(
                "/api/documents/" + documentId + "/owner",
                HttpMethod.PUT,
                nullOwnerBody,
                otherMemberId
        );
        assertThat(nullOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nullOwner.getBody()).containsEntry("status", 400);

        ResponseEntity<Map> missing = exchange("/api/documents/" + UUID.randomUUID(), HttpMethod.GET, null, memberId);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> blankTitle = exchange(
                "/api/documents",
                HttpMethod.POST,
                Map.of("spaceId", spaceId.toString(), "title", " "),
                memberId
        );
        assertThat(blankTitle.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Void> archive = exchangeVoid(
                "/api/documents/" + documentId + "/archive",
                HttpMethod.POST,
                null,
                otherMemberId
        );
        assertThat(archive.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> list = exchange("/api/documents?spaceId=" + spaceId, HttpMethod.GET, null, memberId);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> listedDocuments = (List<Map<String, Object>>) list.getBody().get("content");
        assertThat(listedDocuments).extracting(item -> item.get("id")).doesNotContain(documentId.toString());

        ResponseEntity<Map> archived = exchange("/api/documents/" + documentId, HttpMethod.GET, null, memberId);
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archived.getBody()).containsEntry("status", "ARCHIVED");
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, UUID memberId) {
        return restTemplate.exchange(path, method, entity(body, memberId), Map.class);
    }

    private ResponseEntity<Void> exchangeVoid(String path, HttpMethod method, Object body, UUID memberId) {
        return restTemplate.exchange(path, method, entity(body, memberId), Void.class);
    }

    private HttpEntity<Object> entity(Object body, UUID memberId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Member-Id", memberId.toString());
        return new HttpEntity<>(body, headers);
    }

    private UUID id(ResponseEntity<Map> response) {
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private List<Map<String, Object>> blocks(String heading, String paragraph) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(block("HEADING1", Map.of(
                "type", "heading",
                "attrs", Map.of("level", 1),
                "content", List.of(Map.of("type", "text", "text", heading))
        )));
        blocks.add(block("PARAGRAPH", Map.of(
                "type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", paragraph))
        )));
        return blocks;
    }

    private Map<String, Object> block(String type, Map<String, Object> content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        block.put("content", content);
        block.put("sourceType", "MANUAL");
        block.put("sourceUrl", null);
        block.put("sourceRef", null);
        return block;
    }
}
