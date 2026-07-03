package com.mysc.mydoc.api;

import static com.mysc.mydoc.api.dto.ApiDtos.SearchHitResponse;
import static com.mysc.mydoc.api.dto.ApiDtos.SearchResponse;

import com.mysc.mydoc.service.SearchHit;
import com.mysc.mydoc.service.SearchService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {
    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/api/search")
    SearchResponse search(
            @RequestParam String q,
            @RequestParam(required = false) UUID spaceId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return new SearchResponse(search.hybridSearch(q, spaceId, limit).stream().map(SearchController::toResponse).toList());
    }

    private static SearchHitResponse toResponse(SearchHit hit) {
        return new SearchHitResponse(hit.documentId(), hit.title(), hit.headingPath(), hit.snippet(), hit.score());
    }
}
