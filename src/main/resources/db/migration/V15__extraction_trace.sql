-- M23: 추출 학습 로그 — 교사(Gemini/32B) 호출의 (입력 원문 → 출력 JSON) 쌍을 남긴다.
--
-- 왜: 지금 제품 추출은 Gemini가 하지만 학생 모델(EXAONE 1.2B) 증류로 옮겨가는 중이다.
-- 매 호출이 곧 교사 데이터인데 지금은 응답을 파싱하고 버리고 있어, 나중에 학습셋을
-- 만들려면 Gemini를 다시 호출해야 한다(비용·재현성 문제). 흘려보내지 말고 적재한다.
--
-- source_kind: 어느 파이프라인에서 나왔는지 (slack/drive/tiro/meetily)
-- model: 교사 식별자 — 모델을 바꿔가며 쌓이므로 학습 시 필터 기준이 된다
-- ok: JSON 파싱 성공 여부. 실패 건도 남긴다(형식 실패 분석이 곧 개선 재료)
CREATE TABLE extraction_trace (
    id           uuid PRIMARY KEY,
    source_kind  varchar(20)  NOT NULL,
    channel_id   varchar(50),
    thread_ts    varchar(50),
    document_id  uuid REFERENCES document(id) ON DELETE SET NULL,
    model        varchar(60)  NOT NULL,
    prompt_hash  varchar(64)  NOT NULL,
    input_text   text         NOT NULL,
    output_json  text,
    ok           boolean      NOT NULL,
    error        varchar(500),
    created_at   timestamptz  NOT NULL
);

CREATE INDEX extraction_trace_kind_idx ON extraction_trace (source_kind, created_at);
CREATE INDEX extraction_trace_model_idx ON extraction_trace (model);
