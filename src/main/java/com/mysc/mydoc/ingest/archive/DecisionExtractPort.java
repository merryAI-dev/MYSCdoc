package com.mysc.mydoc.ingest.archive;

import com.mysc.mydoc.ingest.SlackMessage;
import java.util.List;
import java.util.Optional;

public interface DecisionExtractPort {
    /** 스레드에 그래프로 보존할 지식이 없으면 empty. */
    Optional<DecisionExtract> extract(List<SlackMessage> messages);
}
