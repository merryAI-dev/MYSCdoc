package com.mysc.mydoc.api;

import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.ingest.drive.GoogleDriveGateway;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService.ImportSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoogleDriveController {
    private final GoogleDriveIngestService ingest;

    public GoogleDriveController(GoogleDriveIngestService ingest) {
        this.ingest = ingest;
    }

    public record BrowseRequest(String folderId) {}
    public record BrowseResponse(List<GoogleDriveGateway.DriveDoc> docs) {}
    public record ImportRequest(String folderId, UUID spaceId) {}

    @PostMapping("/api/integrations/drive/browse")
    BrowseResponse browse(@RequestBody BrowseRequest request) {
        return new BrowseResponse(ingest.browse(request.folderId()));
    }

    @PostMapping("/api/integrations/drive/import")
    ImportSummary importFolder(
            @RequestBody ImportRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        return ingest.importFolder(request.folderId(), request.spaceId(), memberId);
    }
}
