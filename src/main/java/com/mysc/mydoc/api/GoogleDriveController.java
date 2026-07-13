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
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * 이미 가져온 문서들(Drive + Tiro)을 다시 훑어 지식그래프에 연결한다(원본 API 재호출 없이
     * 저장된 블록에서 재구성). import와 같은 잡 상태를 공유하므로 진행상황은 위 status()로 폴링한다.
     * force=true면 이미 트리플이 있는 문서도 재추출한다 — 추출 스키마 변경 후 전체 재구축용.
     */
    @PostMapping("/api/integrations/drive/sync-knowledge")
    ResponseEntity<ImportJobView> syncKnowledge(@RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingest.startKnowledgeSync(force));
    }
}
