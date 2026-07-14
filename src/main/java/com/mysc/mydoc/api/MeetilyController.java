package com.mysc.mydoc.api;

import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.ingest.meetily.MeetilyIngestService;
import com.mysc.mydoc.ingest.meetily.MeetilyMeeting;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeetilyController {
    private final MeetilyIngestService ingest;

    public MeetilyController(MeetilyIngestService ingest) {
        this.ingest = ingest;
    }

    public record BrowseResponse(List<MeetilyMeeting> meetings) {}
    public record ImportRequest(String meetingId, UUID spaceId) {}
    public record ImportResponse(UUID documentId) {}

    @PostMapping("/api/integrations/meetily/browse")
    BrowseResponse browse() {
        return new BrowseResponse(ingest.browse());
    }

    @PostMapping("/api/integrations/meetily/import")
    ImportResponse importMeeting(
            @RequestBody ImportRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        return new ImportResponse(ingest.importMeeting(request.meetingId(), request.spaceId(), memberId));
    }
}
