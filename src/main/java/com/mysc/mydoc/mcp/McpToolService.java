package com.mysc.mydoc.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysc.mydoc.common.ForbiddenException;
import com.mysc.mydoc.common.NotFoundException;
import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.domain.Block;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.ChangeCause;
import com.mysc.mydoc.domain.Document;
import com.mysc.mydoc.domain.SourceType;
import com.mysc.mydoc.repository.BlockRepository;
import com.mysc.mydoc.repository.SpaceRepository;
import com.mysc.mydoc.service.BlockPayload;
import com.mysc.mydoc.service.DocumentService;
import com.mysc.mydoc.service.SearchService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class McpToolService {
    private static final int DEFAULT_SEARCH_LIMIT = 5; // 08-mcp-server.md
    private static final int MAX_SEARCH_LIMIT = 20; // 08-mcp-server.md

    private final SearchService search;
    private final DocumentService documents;
    private final SpaceRepository spaces;
    private final BlockRepository blocks;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public McpToolService(
            SearchService search,
            DocumentService documents,
            SpaceRepository spaces,
            BlockRepository blocks,
            ObjectMapper objectMapper,
            @Value("${mydoc.document-base-url}") String baseUrl
    ) {
        this.search = search;
        this.documents = documents;
        this.spaces = spaces;
        this.blocks = blocks;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(
                tool(
                        "search_documents",
                        "사내 문서 플랫폼 mydoc에서 하이브리드 검색. 답변에는 반드시 문서 URL을 출처로 포함할 것.",
                        Map.of(
                                "query", prop("string", "검색어 (한국어 자연어)"),
                                "spaceSlug", prop("string", "특정 스페이스로 한정 (선택)"),
                                "limit", Map.of("type", "integer", "default", DEFAULT_SEARCH_LIMIT, "maximum", MAX_SEARCH_LIMIT)
                        ),
                        List.of("query")
                ),
                tool(
                        "get_document",
                        "문서 전체 내용을 마크다운으로 조회",
                        Map.of("documentId", prop("string", "문서 UUID")),
                        List.of("documentId")
                ),
                tool(
                        "create_draft",
                        "새 초안 문서 생성. 사람이 검토 후 활성화한다.",
                        Map.of(
                                "spaceSlug", prop("string", ""),
                                "title", prop("string", ""),
                                "markdown", prop("string", "문서 본문 (마크다운)")
                        ),
                        List.of("spaceSlug", "title", "markdown")
                ),
                tool(
                        "verify_document",
                        "문서 내용이 아직 유효함을 확인 도장. owner 또는 관리자만 가능.",
                        Map.of("documentId", prop("string", "")),
                        List.of("documentId")
                )
        );
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> inputSchema = Map.of("type", "object", "properties", properties, "required", required);
        McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema)
                .description(description)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::call)
                .build();
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String value;
        boolean isError = false;
        try {
            value = switch (request.name()) {
                case "search_documents" -> searchDocuments(request.arguments());
                case "get_document" -> getDocument(documentId(request.arguments()));
                case "create_draft" -> createDraft(request.arguments(), memberId(exchange));
                case "verify_document" -> verifyDocument(documentId(request.arguments()), memberId(exchange));
                default -> "알 수 없는 도구예요: " + request.name();
            };
        } catch (NotFoundException exception) {
            value = "문서를 찾을 수 없어요: " + exception.getMessage();
        } catch (ForbiddenException exception) {
            value = "owner만 검증할 수 있어요";
        } catch (ValidationException exception) {
            value = exception.getMessage();
            isError = true;
        } catch (RuntimeException exception) {
            value = exception.getMessage();
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(value)
                .isError(isError)
                .build();
    }

    private UUID memberId(McpSyncServerExchange exchange) {
        Object value = exchange.transportContext().get(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE);
        if (value instanceof UUID id) {
            return id;
        }
        if (value instanceof String text) {
            return UUID.fromString(text);
        }
        throw new IllegalStateException("MCP member context is missing");
    }

    private UUID documentId(Map<String, Object> arguments) {
        try {
            return UUID.fromString(requiredNonBlankString(arguments, "documentId"));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("documentId is invalid");
        }
    }

    private String searchDocuments(Map<String, Object> arguments) {
        String query = requiredNonBlankString(arguments, "query");
        int limit = Math.min(((Number) arguments.getOrDefault("limit", DEFAULT_SEARCH_LIMIT)).intValue(), MAX_SEARCH_LIMIT);
        UUID spaceId = null;
        if (arguments.get("spaceSlug") instanceof String slug && !slug.isBlank()) {
            spaceId = spaces.findBySlug(slug)
                    .orElseThrow(() -> new NotFoundException("space not found: " + slug))
                    .getId();
        }
        var hits = search.hybridSearch(query, spaceId, limit);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            Document document = documents.get(hit.documentId());
            builder.append("[%d] %s — %s\n".formatted(i + 1, hit.title(), hit.headingPath()));
            builder.append("    ").append(hit.snippet());
            if (document.getStatus().name().equals("STALE")) {
                builder.append(" ⚠️ 이 문서는 오래됐을 수 있어요 (STALE)");
            }
            builder.append("\n");
            builder.append("    상태: %s (검증: %s) | URL: %s/d/%s\n".formatted(
                    document.getStatus(),
                    document.getVerifiedAt(),
                    baseUrl,
                    document.getId()
            ));
        }
        return builder.toString();
    }

    private String getDocument(UUID documentId) {
        Document document = documents.get(documentId);
        StringBuilder builder = new StringBuilder();
        builder.append("---\n");
        builder.append("title: %s | owner: %s | status: %s | verifiedAt: %s\n".formatted(
                document.getTitle(),
                document.getOwner().getDisplayName(),
                document.getStatus(),
                document.getVerifiedAt()
        ));
        builder.append("---\n\n");
        for (Block block : blocks.findByDocumentIdOrderByPosition(documentId)) {
            builder.append(markdown(block)).append("\n\n");
        }
        return builder.toString().trim();
    }

    private String createDraft(Map<String, Object> arguments, UUID memberId) {
        String slug = requiredNonBlankString(arguments, "spaceSlug");
        String title = requiredNonBlankString(arguments, "title");
        String markdown = requiredString(arguments, "markdown");
        var space = spaces.findBySlug(slug).orElseThrow(() -> new NotFoundException("space not found: " + slug));
        Document document = documents.create(space.getId(), title, memberId);
        List<BlockPayload> payloads = markdownToBlocks(markdown);
        documents.replaceBlocks(document.getId(), payloads, memberId, ChangeCause.AI_SUGGESTION);
        return "초안이 생성됐어요: " + baseUrl + "/d/" + document.getId();
    }

    private String verifyDocument(UUID documentId, UUID memberId) {
        documents.verify(documentId, memberId);
        return "검증했어요: " + baseUrl + "/d/" + documentId;
    }

    private String markdown(Block block) {
        String text = text(block.getContent());
        return switch (block.getType()) {
            case HEADING1 -> "# " + text;
            case HEADING2 -> "## " + text;
            case HEADING3 -> "### " + text;
            case BULLET_LIST -> "- " + text;
            case ORDERED_LIST -> "1. " + text;
            case CODE -> "```\n" + text + "\n```";
            case TABLE -> tableMarkdown(block.getContent());
            case QUOTE -> "> " + text;
            case IMAGE -> "![image](" + block.getContent().path("attrs").path("src").asText() + ")";
            default -> text;
        };
    }

    private List<BlockPayload> markdownToBlocks(String markdown) {
        List<BlockPayload> payloads = new ArrayList<>();
        String[] lines = markdown.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("### ")) {
                payloads.add(payload(BlockType.HEADING3, heading(line.substring(4), 3)));
            } else if (line.startsWith("## ")) {
                payloads.add(payload(BlockType.HEADING2, heading(line.substring(3), 2)));
            } else if (line.startsWith("# ")) {
                payloads.add(payload(BlockType.HEADING1, heading(line.substring(2), 1)));
            } else if (line.startsWith("- ")) {
                payloads.add(payload(BlockType.BULLET_LIST, paragraph(line.substring(2))));
            } else if (line.matches("\\d+\\. .*")) {
                payloads.add(payload(BlockType.ORDERED_LIST, paragraph(line.replaceFirst("\\d+\\. ", ""))));
            } else if (line.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                while (++i < lines.length && !lines[i].startsWith("```")) {
                    if (!code.isEmpty()) {
                        code.append("\n");
                    }
                    code.append(lines[i]);
                }
                payloads.add(payload(BlockType.CODE, codeBlock(code.toString())));
            } else if (isTableStart(lines, i)) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(tableCells(line));
                i++;
                while (++i < lines.length && isTableLine(lines[i])) {
                    rows.add(tableCells(lines[i]));
                }
                i--;
                payloads.add(payload(BlockType.TABLE, tableNode(rows)));
            } else if (line.startsWith("> ")) {
                payloads.add(payload(BlockType.QUOTE, paragraph(line.substring(2))));
            } else if (line.startsWith("![") && line.contains("](") && line.endsWith(")")) {
                String url = line.substring(line.indexOf("](") + 2, line.length() - 1);
                payloads.add(payload(BlockType.IMAGE, objectMapper.valueToTree(Map.of("type", "image", "attrs", Map.of("src", url)))));
            } else {
                payloads.add(payload(BlockType.PARAGRAPH, paragraph(line)));
            }
        }
        return payloads;
    }

    private String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value instanceof String text) {
            return text;
        }
        throw new ValidationException(name + " is required");
    }

    private String requiredNonBlankString(Map<String, Object> arguments, String name) {
        String value = requiredString(arguments, name);
        if (StringUtils.hasText(value)) {
            return value;
        }
        throw new ValidationException(name + " is required");
    }

    private BlockPayload payload(BlockType type, JsonNode content) {
        return new BlockPayload(type, content, SourceType.AI_DRAFT, null, null);
    }

    private JsonNode heading(String text, int level) {
        return objectMapper.valueToTree(Map.of(
                "type", "heading",
                "attrs", Map.of("level", level),
                "content", List.of(Map.of("type", "text", "text", text))
        ));
    }

    private JsonNode paragraph(String text) {
        return objectMapper.valueToTree(Map.of(
                "type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", text))
        ));
    }

    private JsonNode codeBlock(String text) {
        return objectMapper.valueToTree(Map.of(
                "type", "codeBlock",
                "content", List.of(Map.of("type", "text", "text", text))
        ));
    }

    private JsonNode tableNode(List<List<String>> rows) {
        return objectMapper.valueToTree(Map.of("type", "table", "rows", rows));
    }

    private String tableMarkdown(JsonNode node) {
        List<List<String>> rows = tableRows(node);
        if (rows.isEmpty()) {
            return text(node);
        }
        StringBuilder builder = new StringBuilder();
        builder.append(tableLine(rows.get(0))).append("\n");
        builder.append(tableLine(rows.get(0).stream().map(cell -> "---").toList()));
        for (int i = 1; i < rows.size(); i++) {
            builder.append("\n").append(tableLine(rows.get(i)));
        }
        return builder.toString();
    }

    private String tableLine(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |";
    }

    private List<List<String>> tableRows(JsonNode node) {
        if (node.has("rows") && node.get("rows").isArray()) {
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode rowNode : node.get("rows")) {
                List<String> row = new ArrayList<>();
                rowNode.forEach(cell -> row.add(cell.asText()));
                rows.add(row);
            }
            return rows;
        }
        List<List<String>> rows = new ArrayList<>();
        collectTableRows(node, rows);
        return rows;
    }

    private void collectTableRows(JsonNode node, List<List<String>> rows) {
        if (node == null) {
            return;
        }
        if (node.isObject() && "tableRow".equals(node.path("type").asText())) {
            List<String> row = new ArrayList<>();
            node.path("content").forEach(cell -> row.add(text(cell)));
            rows.add(row);
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectTableRows(entry.getValue(), rows));
        } else if (node.isArray()) {
            node.forEach(child -> collectTableRows(child, rows));
        }
    }

    private boolean isTableStart(String[] lines, int index) {
        return index + 1 < lines.length && isTableLine(lines[index]) && isTableSeparator(lines[index + 1]);
    }

    private boolean isTableLine(String line) {
        return line.trim().startsWith("|") && line.trim().endsWith("|");
    }

    private boolean isTableSeparator(String line) {
        if (!isTableLine(line)) {
            return false;
        }
        return tableCells(line).stream().allMatch(cell -> cell.matches(":?-{3,}:?"));
    }

    private List<String> tableCells(String line) {
        String trimmed = line.trim();
        String withoutPipes = trimmed.substring(1, trimmed.length() - 1);
        return List.of(withoutPipes.split("\\|", -1)).stream()
                .map(String::trim)
                .toList();
    }

    private String text(JsonNode node) {
        StringBuilder builder = new StringBuilder();
        collectText(node, builder);
        return builder.toString();
    }

    private void collectText(JsonNode node, StringBuilder builder) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode text = node.get("text");
            if (text != null && text.isTextual()) {
                builder.append(text.asText());
            }
            node.fields().forEachRemaining(entry -> collectText(entry.getValue(), builder));
        } else if (node.isArray()) {
            node.forEach(child -> collectText(child, builder));
        }
    }

    private Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }
}
