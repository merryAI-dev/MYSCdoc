package com.mysc.mydoc.service;

import com.mysc.mydoc.ai.KnowledgeAnswerClient;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

/** 선택적으로 API를 이용해 검색어를 재작성한다. 실패하면 원 질문으로 검색한다. */
@Service
public class QueryRewriteClient {
    private static final Logger log = LoggerFactory.getLogger(QueryRewriteClient.class);
    private static final String SYSTEM = "사내 회의록 지식그래프를 검색합니다. 질문의 인명·기관명·날짜를 "
            + "추측하거나 바꾸지 말고 핵심 검색어만 2~80자의 한 줄로 출력하세요. 설명은 출력하지 마세요.";
    private final ObjectProvider<KnowledgeAnswerClient> clients;

    public QueryRewriteClient(ObjectProvider<KnowledgeAnswerClient> clients) {
        this.clients = clients;
    }

    public Optional<String> rewrite(String question) {
        if (!StringUtils.hasText(question)) return Optional.empty();
        KnowledgeAnswerClient client = clients.getIfAvailable();
        if (client == null) return Optional.empty();
        try {
            String result = client.answer(SYSTEM, question);
            if (!StringUtils.hasText(result)) return Optional.empty();
            String query = result.strip();
            if (query.lines().count() != 1 || query.length() < 2 || query.length() > 80) {
                return Optional.empty();
            }
            return Optional.of(query);
        } catch (RestClientException failure) {
            // 응답 본문에는 원문이나 인증 정보가 들어갈 수 있으므로 로그에 남기지 않는다.
            log.warn("검색어 재작성 API 호출 실패 — 원 질문으로 검색");
            return Optional.empty();
        }
    }
}
