package com.mysc.mydoc.ingest.drive;

import java.util.List;

public interface GoogleDriveGateway {
    record DriveDoc(String fileId, String name) {}

    /** 폴더 안의 네이티브 Google Docs만 하위 폴더까지 재귀적으로 찾아 반환한다. */
    List<DriveDoc> listGoogleDocs(String folderId);

    /** 문서 본문을 일반 텍스트로 내보낸다. */
    String exportText(String fileId);
}
