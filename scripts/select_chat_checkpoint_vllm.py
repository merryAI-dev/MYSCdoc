#!/usr/bin/env python3
"""
체크포인트별 답변 생성 — vLLM LoRA 핫스왑. 채점은 judge_answers.py가 따로 한다.

왜 다시 썼나(속도): HF 판(select_chat_checkpoint.py)은 체크포인트마다 베이스 모델을 통째로
다시 올리고, 평가 케이스를 한 건씩 generate 한다. RL 실험에서 20개 체크포인트 평가에
1시간이 걸렸고 대부분이 모델 로딩과 배치 미사용 때문이었다. 여기서는 베이스를 한 번만
올리고 LoRA 어댑터만 갈아끼운다(vLLM이 Exaone4ForCausalLM에 LoRA를 지원한다).

왜 채점을 뗐나(정확성): 원래 이 자리에 "없어요" 같은 문자열로 거절을 세는 함수가 있었다.
골드 교사 답변으로 검증하니 정답 47건 중 28건(60%)을 거절로 오탐했고, 반대로 교사가
답을 지어낸 2건은 뒤에 '없어요'가 있다는 이유로 거절 성공으로 집계했다. 지어냄에 점수를
주는 지표였다. 자세한 실측과 대안은 research/judge_answers.py 문서화 참고.

  → 생성(이 파일)과 채점(judge_answers.py)을 분리했다. 부수 효과로 채점 모델을 바꿔도
    재생성이 필요 없고, 여러 체크포인트 답변을 한 배치로 채점할 수 있다.

두 판을 유지하는 이유: vLLM은 빠르지만 의존성이 무겁다. 공개 어댑터를 남이 검증할 때는
transformers만 있으면 되는 HF 판이 낫다.

사용:
  python select_chat_checkpoint_vllm.py --base <기반모델> --ckpt-root runs/chat_sft_v2 \
      --eval chat_eval_v2.jsonl --out ../runs/chat_answers_v2.json
  python judge_answers.py --model <32B> --answers ../runs/chat_answers_v2.json \
      --out ../runs/chat_judged_v2.json
"""
import argparse
import glob
import json
import os


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--ckpt-root", default=None)
    ap.add_argument("--label", default="base",
                    help="어댑터 없는 대상의 이름. 병합 모델을 잴 때 겹치지 않게 바꾼다")
    ap.add_argument("--eval", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-new-tokens", type=int, default=400)
    ap.add_argument("--max-lora-rank", type=int, default=32)
    ap.add_argument("--gpu-frac", type=float, default=0.55)
    args = ap.parse_args()

    from vllm import LLM, SamplingParams
    from vllm.lora.request import LoRARequest

    cases = [json.loads(l) for l in open(args.eval)]
    n_abstain = sum(1 for c in cases if c["label"].startswith("unanswerable"))
    print(f"[eval] {len(cases)}건 · 거절대상 {n_abstain} · 응답대상 {len(cases) - n_abstain}")
    if n_abstain < 50:
        # Prudence 한 건이 몇 %p씩 움직이는지 먼저 보여준다. n=9였던 적이 있다.
        print(f"[주의] 거절 대상이 {n_abstain}건뿐이다 — 1건이 {100/n_abstain:.1f}%p씩 "
              f"움직인다. 체크포인트 간 차이를 이 수로 단정하지 말 것")

    # 베이스는 한 번만 올린다. enable_lora는 **kwargs로 전달된다(이 vLLM 버전).
    llm = LLM(model=args.base, trust_remote_code=True, dtype="bfloat16",
              gpu_memory_utilization=args.gpu_frac, max_model_len=16384,
              enable_lora=True, max_lora_rank=args.max_lora_rank, max_loras=1)
    tok = llm.get_tokenizer()
    # 그리디 고정. 선택과 최종 보고가 다른 디코딩을 쓰면 순위가 디코딩 노이즈에 흔들린다.
    sampling = SamplingParams(temperature=0.0, max_tokens=args.max_new_tokens)
    prompts = [tok.apply_chat_template(c["prompt"], tokenize=False,
                                       add_generation_prompt=True,
                                       enable_thinking=False) for c in cases]
    # 채점기가 볼 '주어진 사실' — 프롬프트의 사용자 메시지를 그대로 넘긴다.
    facts = [[m for m in c["prompt"] if m["role"] == "user"][-1]["content"] for c in cases]

    targets = [(args.label, None)]
    if args.ckpt_root:
        for path in sorted(glob.glob(f"{args.ckpt_root}/checkpoint-*"),
                           key=lambda p: int(p.rsplit("-", 1)[1])):
            targets.append((f"step{path.rsplit('-', 1)[1]}", os.path.abspath(path)))

    dump = {}
    for i, (name, adapter) in enumerate(targets):
        req = LoRARequest(name, i + 1, adapter) if adapter else None
        outs = llm.generate(prompts, sampling, lora_request=req)
        dump[name] = [{"label": c["label"], "question": c["question"],
                       "facts": f, "answer": o.outputs[0].text.strip()}
                      for c, f, o in zip(cases, facts, outs)]
        print(f"[생성] {name} · {len(outs)}건")

    json.dump(dump, open(args.out, "w"), ensure_ascii=False, indent=1)
    print(f"[저장] {args.out} — judge_answers.py로 채점할 것")


if __name__ == "__main__":
    main()
