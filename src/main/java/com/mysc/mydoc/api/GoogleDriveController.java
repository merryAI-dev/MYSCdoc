package com.mysc.mydoc.api;

import com.mysc.mydoc.config.HeaderAuthFilter;
import com.mysc.mydoc.ingest.drive.GoogleDriveGateway;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService;
import com.mysc.mydoc.ingest.drive.GoogleDriveIngestService.ImportJobView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** 임포트를 백그라운드 잡으로 시작하고 즉시 202 + 잡 스냅샷을 돌려준다. 진행상황은 status 폴링. */
    @PostMapping("/api/integrations/drive/import")
    ResponseEntity<ImportJobView> importFolder(
            @RequestBody ImportRequest request,
            @RequestAttribute(HeaderAuthFilter.MEMBER_ID_ATTRIBUTE) UUID memberId
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ingest.startImport(request.folderId(), request.spaceId(), memberId));
    }

    @GetMapping("/api/integrations/drive/import/status")
    ImportJobView status() {
        return ingest.status();
    }
}
