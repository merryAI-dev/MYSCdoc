package com.mysc.mydoc.api;

import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.ingest.tiro.TiroIngestService;
import com.mysc.mydoc.ingest.tiro.TiroNoteSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TiroController {
    private final TiroIngestService ingest;

    public TiroController(TiroIngestService ingest) {
        this.ingest = ingest;
    }

    @GetMapping("/api/integrations/tiro/notes")
    List<TiroNoteSummary> browse(@RequestParam(required = false) String keyword) {
        return ingest.browse(keyword);
    }

    @PostMapping("/api/integrations/tiro/notes/{noteGuid}/import")
    ResponseEntity<Map<String, UUID>> importNote(
            @PathVariable String noteGuid,
            @RequestBody ImportRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        UUID documentId = ingest.importNote(noteGuid, request.spaceId(), memberId);
        return ResponseEntity.ok(Map.of("documentId", documentId));
    }

    record ImportRequest(UUID spaceId) {}
}
