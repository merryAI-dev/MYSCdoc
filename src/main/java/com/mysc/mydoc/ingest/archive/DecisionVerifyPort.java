package com.mysc.mydoc.ingest.archive;

import com.mysc.mydoc.ingest.SlackMessage;
import java.util.List;

/**
 * 추출 에이전트가 뽑은 결과를 원문과 대조해 검증하는 회의론자 에이전트.
 * Generator(추출)–Evaluator(검증) 분리 — 추출이 지어냈거나 기록 가치가 없는 것을 반려한다.
 */
public interface DecisionVerifyPort {
    record Verdict(boolean approved, String reason) {}

    Verdict verify(List<SlackMessage> messages, DecisionExtract extract);
}
