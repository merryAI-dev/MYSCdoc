package com.mysc.mydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.ai.EmbeddingPort;
import com.mysc.mydoc.domain.Block;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.Provenance;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.repository.BlockRepository;
import com.mysc.mydoc.repository.ChunkRepository;
import com.mysc.mydoc.service.ChunkingService;
import com.mysc.mydoc.service.DocumentService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M2AcceptanceTest {

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
        registry.add("mydoc.rechunk.debounce", () -> "PT0S");
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {
        static final AtomicInteger embedAllCalls = new AtomicInteger();
        static final AtomicBoolean embedAllSawTransaction = new AtomicBoolean();

        @Bean
        @Primary
        EmbeddingPort fakeEmbeddingPort() {
            return new EmbeddingPort() {
                @Override
                public float[] embed(String text) {
                    float[] vector = new float[1536];
                    String lower = text.toLowerCase();
                    vector[0] = lower.contains("account") ? 1.0f : 0.1f;
                    vector[1] = lower.contains("vacation") ? 1.0f : 0.1f;
                    for (String token : lower.split("[^a-z0-9가-힣]+")) {
                        if (!token.isBlank()) {
                            vector[Math.floorMod(token.hashCode(), vector.length - 2) + 2] += 0.000001f;
                        }
                    }
                    return vector;
                }

                @Override
                public List<float[]> embedAll(List<String> texts) {
                    embedAllCalls.incrementAndGet();
                    embedAllSawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
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
    DocumentService documentService;

    @Autowired
    BlockRepository blockRepository;

    @Autowired
    ChunkRepository chunkRepository;

    @Autowired
    ChunkingService chunkingService;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    UUID adminId;
    UUID memberId;
    UUID spaceId;
    String testSuffix;

    @BeforeEach
    void setup() {
        FakeEmbeddingConfig.embedAllCalls.set(0);
        FakeEmbeddingConfig.embedAllSawTransaction.set(false);
        testSuffix = UUID.randomUUID().toString();
        adminId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO member (id, email, display_name, role, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, adminId, "admin-" + testSuffix + "@mysc.co.kr", "Admin", "ADMIN", Timestamp.from(Instant.now()));

        spaceId = id(exchange("/api/spaces", HttpMethod.POST, Map.of("slug", "axr-team-" + testSuffix, "name", "AXR팀"), adminId));
        memberId = id(exchange("/api/members", HttpMethod.POST, Map.of("email", "member-" + testSuffix + "@mysc.co.kr", "displayName", "Member", "role", "MEMBER"), adminId));
    }

    @Test
    void m2AcceptanceScenario() throws Exception {
        UUID accountDoc = createDocument("온보딩 가이드");
        putBlocks(accountDoc, List.of(
                block("HEADING1", "계정 발급"),
                block("PARAGRAPH", "account setup은 IT팀에 요청하세요."),
                block("HEADING1", "휴가 신청"),
                block("PARAGRAPH", "vacation 신청은 HR에 요청하세요.")
        ));
        waitForChunkCount(accountDoc, 2);
        List<String> headingPaths = jdbcTemplate.queryForList("""
                SELECT heading_path FROM chunk WHERE document_id = ? ORDER BY heading_path
                """, String.class, accountDoc);
        assertThat(headingPaths).containsExactlyInAnyOrder("온보딩 가이드 > 계정 발급", "온보딩 가이드 > 휴가 신청");

        exchange("/api/documents/" + accountDoc + "/title", HttpMethod.PUT, Map.of("title", "온보딩 가이드 v2"), memberId);
        waitForHeadingPath(accountDoc, "온보딩 가이드 v2 > 계정 발급");

        UUID nestedDoc = createDocument("계층 문서");
        putBlocks(nestedDoc, List.of(
                block("HEADING1", "상위"),
                block("HEADING2", "중위"),
                block("HEADING3", "하위"),
                block("PARAGRAPH", "nested account")
        ));
        waitForChunkCount(nestedDoc, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT heading_path FROM chunk WHERE document_id = ?", String.class, nestedDoc))
                .isEqualTo("계층 문서 > 상위 > 중위");

        UUID longDoc = createDocument("긴 문서");
        putBlocks(longDoc, List.of(block("HEADING1", "긴 섹션"), block("PARAGRAPH", "가".repeat(3000))));
        waitForChunkCount(longDoc, 2);

        putBlocks(longDoc, List.of(block("HEADING1", "짧은 섹션"), block("PARAGRAPH", "짧다")));
        waitForChunkCount(longDoc, 1);

        putBlocks(longDoc, List.of());
        waitForChunkCount(longDoc, 0);

        UUID draftDoc = createDocument("초안 문서");
        jdbcTemplate.update("""
                INSERT INTO chunk (id, document_id, heading_path, text, embedding, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS vector), ?)
                """, UUID.randomUUID(), draftDoc, "초안 문서 > account", "account draft", vectorLiteral(1.0f, 0.1f), Timestamp.from(Instant.now()));

        ResponseEntity<Map> search = exchange("/api/search?q=account&limit=10", HttpMethod.GET, null, memberId);
        assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> hits = (List<Map<String, Object>>) search.getBody().get("hits");
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0)).containsEntry("documentId", accountDoc.toString());
        assertThat(hits).extracting(hit -> hit.get("documentId")).doesNotHaveDuplicates();
        assertThat(hits).extracting(hit -> hit.get("documentId")).doesNotContain(draftDoc.toString());

        ResponseEntity<Map> missing = exchange("/api/search", HttpMethod.GET, null, memberId);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> blank = restTemplate.exchange(
                "/api/search?q={q}",
                HttpMethod.GET,
                entity(null, memberId),
                Map.class,
                "  "
        );
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        UUID otherSpaceId = id(exchange("/api/spaces", HttpMethod.POST, Map.of("slug", "support-team-" + testSuffix, "name", "지원팀"), adminId));
        UUID otherSpaceDoc = createDocument(otherSpaceId, "지원팀 계정 문서");
        putBlocks(otherSpaceDoc, List.of(block("HEADING1", "계정 처리"), block("PARAGRAPH", "account routing은 지원팀에서 확인하세요.")));
        waitForChunkCount(otherSpaceDoc, 1);

        ResponseEntity<Map> limitedSpaceSearch = exchange(
                "/api/search?q=account&spaceId=" + spaceId + "&limit=1",
                HttpMethod.GET,
                null,
                memberId
        );
        List<Map<String, Object>> limitedHits = (List<Map<String, Object>>) limitedSpaceSearch.getBody().get("hits");
        assertThat(limitedSpaceSearch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(limitedHits).hasSize(1);
        assertThat(limitedHits.get(0)).containsEntry("documentId", accountDoc.toString());

        ResponseEntity<Map> otherSpaceSearch = exchange(
                "/api/search?q=account&spaceId=" + otherSpaceId,
                HttpMethod.GET,
                null,
                memberId
        );
        List<Map<String, Object>> otherSpaceHits = (List<Map<String, Object>>) otherSpaceSearch.getBody().get("hits");
        assertThat(otherSpaceSearch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherSpaceHits).extracting(hit -> hit.get("documentId"))
                .contains(otherSpaceDoc.toString())
                .doesNotContain(accountDoc.toString(), nestedDoc.toString());
    }

    @Test
    void rechunk_skipsDraftDocuments() {
        UUID documentId = createDocument("검증 전 회의록");
        blockRepository.save(new Block(
                documentId,
                0,
                BlockType.PARAGRAPH,
                objectMapper.valueToTree(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "draft text")))),
                new Provenance(SourceType.MANUAL, null, null)
        ));

        chunkingService.rechunk(documentId);

        assertThat(FakeEmbeddingConfig.embedAllCalls).hasValue(0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk WHERE document_id = ?", Integer.class, documentId))
                .isZero();
    }

    @Test
    void rechunk_reusesExistingEmbeddingsWhenTextIsUnchanged() {
        UUID documentId = createDocument("원래 제목");
        jdbcTemplate.update("UPDATE document SET status = 'ACTIVE', updated_at = now(), version = version + 1 WHERE id = ?", documentId);
        blockRepository.save(new Block(
                documentId,
                0,
                BlockType.PARAGRAPH,
                objectMapper.valueToTree(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "same text")))),
                new Provenance(SourceType.MANUAL, null, null)
        ));
        chunkingService.rechunk(documentId);
        assertThat(FakeEmbeddingConfig.embedAllCalls).hasValue(1);

        FakeEmbeddingConfig.embedAllCalls.set(0);
        jdbcTemplate.update("UPDATE document SET title = '바뀐 제목', updated_at = now(), version = version + 1 WHERE id = ?", documentId);
        chunkingService.rechunk(documentId);

        assertThat(FakeEmbeddingConfig.embedAllCalls).hasValue(0);
        assertThat(jdbcTemplate.queryForObject("SELECT heading_path FROM chunk WHERE document_id = ?", String.class, documentId))
                .isEqualTo("바뀐 제목");
    }

    @Test
    void rechunk_callsEmbeddingOutsideTransaction() {
        UUID documentId = createDocument("트랜잭션 분리 문서");
        jdbcTemplate.update("UPDATE document SET status = 'ACTIVE', updated_at = now(), version = version + 1 WHERE id = ?", documentId);
        blockRepository.save(new Block(
                documentId,
                0,
                BlockType.PARAGRAPH,
                objectMapper.valueToTree(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", "embed outside tx")))),
                new Provenance(SourceType.MANUAL, null, null)
        ));

        chunkingService.rechunk(documentId);

        assertThat(FakeEmbeddingConfig.embedAllCalls).hasValue(1);
        assertThat(FakeEmbeddingConfig.embedAllSawTransaction).isFalse();
    }

    @Test
    void rechunk_withoutEmbeddingPort_removesExistingChunks() throws Exception {
        UUID documentId = createDocument("임베딩 보존 문서");
        putBlocks(documentId, List.of(block("HEADING1", "보존"), block("PARAGRAPH", "preserved text")));
        waitForChunkCount(documentId, 1);

        ChunkingService withoutEmbedding = new ChunkingService(documentService, blockRepository, chunkRepository, noEmbeddings(), transactionManager);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> withoutEmbedding.rechunk(documentId));

        waitForChunkCount(documentId, 0);
    }

    private UUID createDocument(String title) {
        return createDocument(spaceId, title);
    }

    private UUID createDocument(UUID targetSpaceId, String title) {
        return id(exchange("/api/documents", HttpMethod.POST, Map.of("spaceId", targetSpaceId.toString(), "title", title), memberId));
    }

    private void putBlocks(UUID documentId, List<Map<String, Object>> blocks) {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/documents/" + documentId + "/blocks",
                HttpMethod.PUT,
                entity(Map.of("blocks", blocks), memberId),
                Void.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void waitForChunkCount(UUID documentId, int expected) throws Exception {
        for (int i = 0; i < 60; i++) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk WHERE document_id = ?", Integer.class, documentId);
            if (count != null && count == expected) {
                return;
            }
            Thread.sleep(250);
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk WHERE document_id = ?", Integer.class, documentId);
        assertThat(count).isEqualTo(expected);
    }

    private void waitForHeadingPath(UUID documentId, String expected) throws Exception {
        for (int i = 0; i < 60; i++) {
            List<String> paths = jdbcTemplate.queryForList("SELECT heading_path FROM chunk WHERE document_id = ?", String.class, documentId);
            if (paths.contains(expected)) {
                return;
            }
            Thread.sleep(250);
        }
        assertThat(jdbcTemplate.queryForList("SELECT heading_path FROM chunk WHERE document_id = ?", String.class, documentId))
                .contains(expected);
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
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        block.put("content", type.startsWith("HEADING")
                ? Map.of("type", "heading", "attrs", Map.of("level", headingLevel(type)), "content", List.of(Map.of("type", "text", "text", text)))
                : Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text))));
        block.put("sourceType", "MANUAL");
        block.put("sourceUrl", null);
        block.put("sourceRef", null);
        return block;
    }

    private int headingLevel(String type) {
        return switch (type) {
            case "HEADING2" -> 2;
            case "HEADING3" -> 3;
            default -> 1;
        };
    }

    private String vectorLiteral(float first, float second) {
        List<String> values = new ArrayList<>();
        values.add(Float.toString(first));
        values.add(Float.toString(second));
        for (int i = 2; i < 1536; i++) {
            values.add("0.0");
        }
        return "[" + String.join(",", values) + "]";
    }

    private ObjectProvider<EmbeddingPort> noEmbeddings() {
        return new ObjectProvider<>() {
            @Override
            public EmbeddingPort getObject(Object... args) {
                throw new NoSuchBeanDefinitionException(EmbeddingPort.class);
            }

            @Override
            public EmbeddingPort getIfAvailable() {
                return null;
            }

            @Override
            public EmbeddingPort getIfUnique() {
                return null;
            }

            @Override
            public EmbeddingPort getObject() {
                throw new NoSuchBeanDefinitionException(EmbeddingPort.class);
            }

            @Override
            public Iterator<EmbeddingPort> iterator() {
                return List.<EmbeddingPort>of().iterator();
            }
        };
    }
}
