#!/usr/bin/env python3
"""
챗 교사 v3 — 키워드 필터 없이 생성만 하고, 걸러내기는 32B 판정에 맡긴다.

v2와의 차이 두 가지 (docs/RESEARCH-plan-v3.md H1·H2):

(1) 필터 분리. v2는 "없어요" 부분일치로 교사의 거절 성공을 판정해 학습셋을 걸렀는데,
    그 검사가 교사가 답을 지어낸 2건을 통과시켰다(답변 뒤에 '없어요'가 있어서).
    여기서는 생성만 하고 전 행을 judge_answers.py 채점용 덤프로도 내보낸다.
    걸러내기는 build_corpus_from_judged.py가 채점 결과를 보고 한다.

(2) 부분답변 시범. 교사 시스템 프롬프트에 3단 응답(전부답 / 부분답+한계 명시 / 거절)을
    명시한다. 학생 프롬프트(build_chat_sft_jobs.SYSTEM_V3)와 같은 구조 — 교사가 시범을
    보여야 학생이 배운다.

사용:
  python gen_chat_teacher_v3.py --model <32B> --jobs chat_sft_jobs_v3.jsonl \
      --hard hard_abstention_jobs_v3.jsonl --out ../runs/chat_teacher_v3.jsonl \
      --dump ../runs/chat_teacher_v3_dump.json --tp 2
"""
import argparse
import json
import time

from vllm import LLM, SamplingParams

# 교사에게는 학생 규칙 + "왜 그 확정도인지 근거를 한 조각" 요구(v2에서 반영률 67→93%
# 효과가 실측된 부분)를 함께 준다.
SYSTEM = """당신은 사내 지식그래프를 근거로 답하는 모범 답안을 작성합니다.
아래 '지식그래프 사실'만 근거로 답하세요.

응답은 세 가지 중 하나입니다. 사실이 질문을 얼마나 커버하는지로 고르세요:
- 사실이 질문을 커버하면 → 답하세요.
- 일부만 커버하면 → 아는 부분만 답하고, 나머지는 "지식그래프에 없다"고 분명히 말하세요.
  아는 부분까지 거절하지 마세요.
- 전혀 커버하지 못하면 → "지식그래프에 아직 그 내용이 없어요"라고만 말하세요.
  질문과 **실제로 관련 있는** 사실이 있을 때만 한 문장 덧붙이고, 없으면 덧붙이지 마세요.
  관련 없는 사실을 "인접 내용"이라며 갖다 붙이지 마세요 — 그것도 거짓입니다.

각 사실 앞의 [확정도]를 답변에 녹이고, 왜 그렇게 판단했는지 근거를 한 조각 넣으세요
("합의된 사항이라" / "아직 검토 단계라"). 라벨 이름만 나열하지 마세요.
  · [확정] "~하기로 했어요"처럼 단정 · [예정] "~할 예정이었어요"처럼 여지
  · [논의] "확정된 건 아니고 논의됐어요" · [미분류] 확정도를 단정하지 않음
  · (조건: …) 이 붙은 사실은 조건을 반드시 함께 말하세요.

- 번호 목록이나 원문을 그대로 나열하지 말고, 줄글로 자연스럽게 녹여 말하세요.
- 한국어 해요체로 끝맺으세요("~해요", "~했어요"). "~습니다"체를 섞지 마세요.
- 2~5문장."""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--jobs", required=True)
    ap.add_argument("--hard", required=True)
    ap.add_argument("--out", required=True, help="교사 원본 행 (system/user/answer 포함)")
    ap.add_argument("--dump", required=True, help="judge_answers.py 입력 형식")
    ap.add_argument("--tp", type=int, default=2)
    ap.add_argument("--max-new-tokens", type=int, default=420)
    args = ap.parse_args()

    jobs = [json.loads(l) for l in open(args.jobs)]
    for line in open(args.hard):
        j = json.loads(line)
        jobs.append({"label": j["label"], "question": j["question"],
                     "system": jobs[0]["system"],
                     "user": f"질문: {j['question']}\n\n지식그래프 사실:\n{j['facts']}\n"
                             f"위 사실만 근거로, 각 사실의 확정도를 구분해서 답하세요.\n"})

    llm = LLM(model=args.model, tensor_parallel_size=args.tp, trust_remote_code=True,
              dtype="bfloat16", gpu_memory_utilization=0.85, max_model_len=16384)
    tok = llm.get_tokenizer()
    prompts = [tok.apply_chat_template(
        [{"role": "system", "content": SYSTEM},
         {"role": "user", "content": j["user"]}],
        tokenize=False, add_generation_prompt=True, enable_thinking=False) for j in jobs]

    started = time.time()
    outs = llm.generate(prompts, SamplingParams(temperature=0.3, top_p=0.9,
                                                max_tokens=args.max_new_tokens))
    elapsed = time.time() - started

    rows, empty = [], 0
    for j, o in zip(jobs, outs):
        answer = o.outputs[0].text.strip()
        if not answer:
            empty += 1
            continue
        rows.append({**j, "answer": answer})

    with open(args.out, "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    # 채점 덤프 — facts는 학생이 볼 user 메시지 전문 그대로
    json.dump({"teacher": [{"label": r["label"], "question": r["question"],
                            "facts": r["user"], "answer": r["answer"]} for r in rows]},
              open(args.dump, "w"), ensure_ascii=False, indent=1)
    print(f"[done] {elapsed:.0f}s · 생성 {len(rows)} · 빈 답변 {empty}")
    print(f"[다음] judge_answers.py --answers {args.dump} 로 채점한 뒤 "
          f"build_corpus_from_judged.py 로 걸러서 코퍼스를 만들 것")


if __name__ == "__main__":
    main()
