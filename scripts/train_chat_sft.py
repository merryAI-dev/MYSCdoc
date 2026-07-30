#!/usr/bin/env python3
"""EXAONE 챗 SFT 학습기.

기본 학습법은 v2와 같다: completion-only LoRA, 6 epochs, epoch별 checkpoint 저장.
``--protocol v4``는 학습법을 바꾸지 않고 사전 등록한 데이터 변경만 허용한다.

v4 새 행 형식::

  {"prompt": [...], "completion": [...],
   "meta": {"pair_id": "boundary-001", "variant": "positive|negative",
            "question": "..."}}

사용::

  CUDA_VISIBLE_DEVICES=0 python scripts/train_chat_sft.py \
    --model /data/tta/EXAONE/exaone-4.0-1.2B-local \
    --data-dir corpus/chat_sft_v4 --out runs/chat_sft_v4 \
    --protocol v4 \
    --baseline-data-dir corpus/chat_sft_v4_control \
    --baseline-run-config runs/chat_sft_v4_control/run_config.json \
    --audit-questions scripts/chat_test_v3.jsonl \
    --selection-questions scripts/chat_select_v2.jsonl \
    --sealed-questions scripts/chat_test_v4_sealed.jsonl
"""

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import platform
import re
import subprocess
import sys
import tempfile
import time
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path


TARGET_MODULES = [
    "q_proj", "k_proj", "v_proj", "o_proj",
    "gate_proj", "up_proj", "down_proj",
]
V4_RECIPE = {
    "epochs": 6.0,
    "lr": 2e-4,
    "max_seq_len": 8192,
    "lora_r": 16,
    "lora_dropout": 0.1,
    "seeds": (42, 43, 44),
    "train_rows": 449,
    "dev_rows": 79,
    "replaced_train_rows": 64,
    "boundary_pairs": 32,
}
QUESTION_RE = re.compile(r"(?:^|\n)질문:\s*(.+?)(?:\n\s*\n|$)", re.S)
FACT_NUMBER_RE = re.compile(r"^\s*\d+\.\s*")


class PreflightError(ValueError):
    pass


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sample_hash(row):
    return sha256_bytes(canonical_json({
        "prompt": row["prompt"], "completion": row["completion"],
    }).encode("utf-8"))


def normalize_question(text):
    return " ".join(unicodedata.normalize("NFKC", text).split()).casefold()


def question_from_row(row, where):
    prompt_question = None
    if isinstance(row.get("prompt"), list):
        users = [m.get("content", "") for m in row["prompt"]
                 if isinstance(m, dict) and m.get("role") == "user"]
        match = QUESTION_RE.search(users[-1]) if users else None
        prompt_question = match.group(1) if match else None
    metadata_questions = [row.get("question")]
    if isinstance(row.get("meta"), dict):
        metadata_questions.append(row["meta"].get("question"))
    metadata_questions = [value for value in metadata_questions if value]
    if prompt_question:
        normalized = normalize_question(prompt_question)
        if any(normalize_question(str(value)) != normalized for value in metadata_questions):
            raise PreflightError(f"{where}: metadata 질문과 prompt 질문이 다르다")
        return normalized
    if not metadata_questions or not isinstance(metadata_questions[0], str):
        raise PreflightError(f"{where}: 질문을 찾을 수 없다")
    normalized = normalize_question(metadata_questions[0])
    if any(normalize_question(str(value)) != normalized for value in metadata_questions[1:]):
        raise PreflightError(f"{where}: question metadata끼리 다르다")
    return normalized


def read_jsonl(path):
    path = Path(path)
    if not path.is_file():
        raise PreflightError(f"파일이 없다: {path}")
    rows = []
    with path.open(encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            if not line.strip():
                raise PreflightError(f"{path}:{line_no}: 빈 줄")
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise PreflightError(f"{path}:{line_no}: JSON 오류: {exc.msg}") from exc
            if not isinstance(row, dict):
                raise PreflightError(f"{path}:{line_no}: JSON object가 아님")
            rows.append(row)
    if not rows:
        raise PreflightError(f"{path}: 데이터가 비어 있다")
    return rows


def validate_sample(row, where):
    prompt = row.get("prompt")
    completion = row.get("completion")
    if not isinstance(prompt, list) or len(prompt) < 2:
        raise PreflightError(f"{where}: prompt는 system과 하나 이상의 user 메시지가 필요하다")
    roles = [m.get("role") for m in prompt if isinstance(m, dict)]
    expected = ["system"] + ["user" if i % 2 == 0 else "assistant"
                             for i in range(len(prompt) - 1)]
    if len(roles) != len(prompt) or roles != expected or roles[-1] != "user":
        raise PreflightError(f"{where}: prompt 역할은 system 뒤 user/assistant가 교대하고 user로 끝나야 한다")
    if not isinstance(completion, list) or len(completion) != 1:
        raise PreflightError(f"{where}: completion은 assistant 메시지 하나여야 한다")
    if not isinstance(completion[0], dict) or completion[0].get("role") != "assistant":
        raise PreflightError(f"{where}: completion 역할은 assistant여야 한다")
    for message in prompt + completion:
        if not isinstance(message.get("content"), str) or not message["content"].strip():
            raise PreflightError(f"{where}: 빈 메시지")
    question_from_row(row, where)


def validate_dataset_dir(data_dir):
    data_dir = Path(data_dir)
    result = {}
    for split in ("train", "dev"):
        path = data_dir / f"{split}.jsonl"
        rows = read_jsonl(path)
        hashes = []
        questions = set()
        for i, row in enumerate(rows, 1):
            where = f"{path}:{i}"
            validate_sample(row, where)
            hashes.append(sample_hash(row))
            questions.add(question_from_row(row, where))
        hash_counts = Counter(hashes)
        duplicates = {h: n for h, n in hash_counts.items() if n > 1}
        result[split] = {
            "path": str(path), "rows": rows, "hashes": hash_counts,
            "questions": questions, "file_sha256": sha256_file(path),
            "duplicate_examples": duplicates,
        }
    overlap = result["train"]["questions"] & result["dev"]["questions"]
    if overlap:
        raise PreflightError(f"train/dev 질문 누수 {len(overlap)}건")
    return result


def fact_parts(row, where):
    user = row["prompt"][1]["content"]
    marker = "지식그래프 사실:\n"
    suffix_marker = "\n\n위 사실만 근거로"
    if marker not in user or suffix_marker not in user:
        raise PreflightError(f"{where}: 사실 목록 경계를 찾을 수 없다")
    prefix, rest = user.split(marker, 1)
    facts_text, suffix = rest.split(suffix_marker, 1)
    facts = tuple(" ".join(FACT_NUMBER_RE.sub("", line).split())
                  for line in facts_text.splitlines() if line.strip())
    if not facts or len(facts) != len(set(facts)):
        raise PreflightError(f"{where}: 사실 목록이 비었거나 중복 사실이 있다")
    return " ".join(prefix.split()), facts, " ".join(suffix.split())


def validate_pair_prompts(positive, negative, pair_id):
    if question_from_row(positive, pair_id) != question_from_row(negative, pair_id):
        raise PreflightError(f"{pair_id}: 질문이 다르다")
    if positive["prompt"][0] != negative["prompt"][0]:
        raise PreflightError(f"{pair_id}: system prompt가 다르다")
    p_prefix, p_facts, p_suffix = fact_parts(positive, pair_id)
    n_prefix, n_facts, n_suffix = fact_parts(negative, pair_id)
    if p_prefix != n_prefix or p_suffix != n_suffix:
        raise PreflightError(f"{pair_id}: 사실 목록 외의 입력이 달라졌다")
    removals = [i for i in range(len(p_facts))
                if p_facts[:i] + p_facts[i + 1:] == n_facts]
    if len(removals) != 1:
        raise PreflightError(f"{pair_id}: positive에서 결정적 사실 하나만 제자리에서 제거해야 한다")


def validate_boundary_pairs(rows, expected):
    pairs = defaultdict(dict)
    for i, row in enumerate(rows, 1):
        meta = row.get("meta")
        if not isinstance(meta, dict):
            raise PreflightError(f"v4 추가행 {i}: meta가 없다")
        pair_id, variant = meta.get("pair_id"), meta.get("variant")
        if not isinstance(pair_id, str) or variant not in ("positive", "negative"):
            raise PreflightError(f"v4 추가행 {i}: pair_id/variant 오류")
        expected_target = "answerable" if variant == "positive" else "unanswerable"
        if meta.get("target") != expected_target:
            raise PreflightError(f"{pair_id}: {variant}의 meta.target은 {expected_target}이어야 한다")
        if variant in pairs[pair_id]:
            raise PreflightError(f"{pair_id}: {variant} 중복")
        if normalize_question(str(meta.get("question", ""))) != question_from_row(row, pair_id):
            raise PreflightError(f"{pair_id}: meta.question과 prompt 질문이 다르다")
        pairs[pair_id][variant] = row
    if len(pairs) != expected:
        raise PreflightError(f"경계쌍 {len(pairs)}개, 기대값 {expected}개")
    for pair_id, pair in pairs.items():
        if set(pair) != {"positive", "negative"}:
            raise PreflightError(f"{pair_id}: positive/negative가 모두 필요하다")
        positive, negative = pair["positive"], pair["negative"]
        validate_pair_prompts(positive, negative, pair_id)
        if positive["completion"] == negative["completion"]:
            raise PreflightError(f"{pair_id}: 양성/음성 답변이 같다")
    return set(pairs)


def validate_eval_pairs(rows, expected):
    pairs = defaultdict(dict)
    for i, row in enumerate(rows, 1):
        meta = row.get("meta")
        if not isinstance(meta, dict):
            raise PreflightError(f"봉인셋 {i}: meta가 없다")
        pair_id, variant = meta.get("pair_id"), meta.get("variant")
        if not isinstance(pair_id, str) or variant not in ("positive", "negative"):
            raise PreflightError(f"봉인셋 {i}: pair_id/variant 오류")
        if variant in pairs[pair_id]:
            raise PreflightError(f"봉인셋 {pair_id}: {variant} 중복")
        pairs[pair_id][variant] = row
    if len(pairs) != expected:
        raise PreflightError(f"봉인 경계쌍 {len(pairs)}개, 기대값 {expected}개")
    for pair_id, pair in pairs.items():
        if set(pair) != {"positive", "negative"}:
            raise PreflightError(f"봉인셋 {pair_id}: positive/negative가 모두 필요하다")
        validate_pair_prompts(pair["positive"], pair["negative"], pair_id)


def load_question_file(path, expected_rows, expected_unique, paired=False,
                       expected_duplicate_pairs=0):
    rows = read_jsonl(path)
    normalized = [question_from_row(row, f"{path}:{i}") for i, row in enumerate(rows, 1)]
    questions = set(normalized)
    if len(rows) != expected_rows or len(questions) != expected_unique:
        raise PreflightError(
            f"{path}: {len(rows)}행/{len(questions)}질문, "
            f"기대값 {expected_rows}행/{expected_unique}질문")
    if paired:
        if any(count != 2 for count in Counter(normalized).values()):
            raise PreflightError(f"{path}: 각 질문은 정확히 두 번 나와야 한다")
        validate_eval_pairs(rows, expected_unique)
    if expected_duplicate_pairs:
        counts = Counter(normalized)
        distribution = Counter(counts.values())
        expected_distribution = Counter({1: expected_unique - expected_duplicate_pairs,
                                         2: expected_duplicate_pairs})
        if distribution != expected_distribution:
            raise PreflightError(
                f"{path}: 단일/쌍 질문 구조가 다르다: {dict(distribution)} != "
                f"{dict(expected_distribution)}")
        labels = defaultdict(list)
        for question, row in zip(normalized, rows):
            labels[question].append(row.get("label"))
        if any(set(labels[question]) != {"answerable", "unanswerable_hard"}
               for question, count in counts.items() if count == 2):
            raise PreflightError(f"{path}: 중복 질문은 answerable/unanswerable_hard 쌍이어야 한다")
    return {"path": str(path), "rows": len(rows), "questions": questions,
            "file_sha256": sha256_file(path)}


def validate_v4(args, current):
    for key in ("epochs", "lr", "max_seq_len", "lora_r", "lora_dropout"):
        if getattr(args, key) != V4_RECIPE[key]:
            raise PreflightError(f"v4는 {key}={V4_RECIPE[key]}로 고정한다")
    if args.seed not in V4_RECIPE["seeds"]:
        raise PreflightError(f"v4 seed는 {V4_RECIPE['seeds']} 중 하나여야 한다")
    required = {
        "baseline_data_dir": args.baseline_data_dir,
        "baseline_run_config": args.baseline_run_config,
        "audit_questions": args.audit_questions,
        "selection_questions": args.selection_questions,
        "sealed_questions": args.sealed_questions,
    }
    missing = [name for name, value in required.items() if not value]
    if missing:
        raise PreflightError(f"v4 필수 인자 누락: {', '.join(missing)}")

    baseline = validate_dataset_dir(args.baseline_data_dir)
    if len(current["train"]["rows"]) != V4_RECIPE["train_rows"]:
        raise PreflightError("v4 train은 449행이어야 한다")
    if len(current["dev"]["rows"]) != V4_RECIPE["dev_rows"]:
        raise PreflightError("v4 dev는 79행이어야 한다")
    if current["dev"]["file_sha256"] != baseline["dev"]["file_sha256"]:
        raise PreflightError("v4 dev는 control dev와 byte-equivalent여야 한다")

    old_train, new_train = baseline["train"]["hashes"], current["train"]["hashes"]
    removed, added = old_train - new_train, new_train - old_train
    expected = V4_RECIPE["replaced_train_rows"]
    if removed.total() != expected or added.total() != expected:
        raise PreflightError(
            f"v2→v4 train 변경은 -64/+64여야 한다: -{removed.total()}/+{added.total()}")
    old_order = [sample_hash(row) for row in baseline["train"]["rows"]]
    new_order = [sample_hash(row) for row in current["train"]["rows"]]
    changed_positions = [i for i, (old, new) in enumerate(zip(old_order, new_order))
                         if old != new]
    if len(changed_positions) != expected:
        raise PreflightError(
            f"control의 동일 위치 64행만 교체해야 한다: 실제 변경 위치 {len(changed_positions)}개")

    def target_class(row, where):
        target = row.get("meta", {}).get("target")
        if target in ("answerable", "partial"):
            return "answerable"
        if target == "unanswerable":
            return "unanswerable"
        raise PreflightError(f"{where}: 감사된 meta.target이 없다")

    baseline_classes = Counter(target_class(row, f"control train {i}")
                               for i, row in enumerate(baseline["train"]["rows"], 1))
    current_classes = Counter(target_class(row, f"treatment train {i}")
                              for i, row in enumerate(current["train"]["rows"], 1))
    if current_classes != baseline_classes:
        raise PreflightError(
            f"control/treatment 전체 클래스 수가 다르다: {dict(baseline_classes)} != {dict(current_classes)}")
    for i, row in enumerate(baseline["dev"]["rows"], 1):
        target_class(row, f"control dev {i}")
    for i, (old_row, new_row) in enumerate(
            zip(baseline["train"]["rows"], current["train"]["rows"]), 1):
        if sample_hash(old_row) == sample_hash(new_row):
            if old_row.get("meta", {}).get("target") != new_row.get("meta", {}).get("target"):
                raise PreflightError(f"동일한 train {i}행의 meta.target이 바뀌었다")

    removed_classes = Counter()
    baseline_rows = defaultdict(list)
    for row in baseline["train"]["rows"]:
        baseline_rows[sample_hash(row)].append(row)
    for digest, count in removed.items():
        candidates = baseline_rows[digest]
        if len(candidates) != count:
            raise PreflightError(f"중복 예시 {digest[:12]}의 일부만 교체할 수 없다")
        for row in candidates:
            removed_classes[target_class(row, f"교체 대상 {digest[:12]}")] += 1
    if removed_classes != Counter({"answerable": 32, "unanswerable": 32}):
        raise PreflightError(f"교체 전 클래스가 32/32가 아니다: {dict(removed_classes)}")

    remaining_added = added.copy()
    added_rows = []
    for row in current["train"]["rows"]:
        digest = sample_hash(row)
        if remaining_added[digest]:
            added_rows.append(row)
            remaining_added[digest] -= 1
    pair_ids = validate_boundary_pairs(added_rows, V4_RECIPE["boundary_pairs"])
    baseline_questions = baseline["train"]["questions"] | baseline["dev"]["questions"]
    pair_questions = {question_from_row(row, "v4 pair") for row in added_rows}
    if pair_questions & baseline_questions:
        raise PreflightError("v4 경계쌍 질문이 v2 train/dev에 이미 있다")

    eval_sets = {
        "audit": load_question_file(args.audit_questions, 100, 80,
                                     expected_duplicate_pairs=20),
        "selection": load_question_file(args.selection_questions, 199, 167,
                                         expected_duplicate_pairs=32),
        "sealed": load_question_file(args.sealed_questions, 300, 150, paired=True),
    }
    names = list(eval_sets)
    for i, left in enumerate(names):
        for right in names[i + 1:]:
            overlap = eval_sets[left]["questions"] & eval_sets[right]["questions"]
            if overlap:
                raise PreflightError(f"{left}/{right} 질문 누수 {len(overlap)}건")
    all_training_questions = current["train"]["questions"] | current["dev"]["questions"]
    for name, value in eval_sets.items():
        overlap = all_training_questions & value["questions"]
        if overlap:
            raise PreflightError(f"학습/{name} 질문 누수 {len(overlap)}건")

    with open(args.baseline_run_config, encoding="utf-8") as f:
        baseline_run = json.load(f)
    old_args = baseline_run.get("args", {})
    for key in ("model", "epochs", "lr", "max_seq_len", "lora_r", "lora_dropout", "seed"):
        if old_args.get(key) != getattr(args, key):
            raise PreflightError(f"v2 run_config와 {key}가 다르다")
    old_systems = {canonical_json(row["prompt"][0]) for split in baseline.values()
                   for row in split["rows"]}
    new_systems = {canonical_json(row["prompt"][0]) for split in current.values()
                   for row in split["rows"]}
    if old_systems != new_systems or len(new_systems) != 1:
        raise PreflightError("v4 system prompt가 v2와 다르다")

    return {
        "baseline_file_sha256": {
            split: baseline[split]["file_sha256"] for split in ("train", "dev")},
        "unchanged_train_rows": (old_train & new_train).total(),
        "removed_classes": dict(removed_classes),
        "train_classes": dict(current_classes),
        "changed_train_positions": changed_positions,
        "boundary_pairs": len(pair_ids),
        "baseline_run_signature": baseline_run.get("run_signature"),
        "evaluation_sets": {
            name: {k: v for k, v in value.items() if k != "questions"}
            for name, value in eval_sets.items()
        },
    }


def nearest_rank(values, proportion):
    return sorted(values)[max(0, math.ceil(len(values) * proportion) - 1)]


def token_stats(rows, tokenizer, max_len):
    lengths = []
    for row in rows:
        tokens = tokenizer.apply_chat_template(
            row["prompt"] + row["completion"], tokenize=True,
            add_generation_prompt=False)
        if hasattr(tokens, "keys"):
            tokens = tokens["input_ids"]
        lengths.append(len(tokens))
    return {
        "n": len(lengths),
        "tokens_median": nearest_rank(lengths, 0.5),
        "tokens_p95": nearest_rank(lengths, 0.95),
        "tokens_max": max(lengths),
        "truncated_at_max_len": sum(n > max_len for n in lengths),
    }


def model_fingerprint(model_path):
    root = Path(model_path)
    if not root.is_dir():
        raise PreflightError("논문용 실행은 로컬 모델 디렉터리가 필요하다")
    weights = sorted(root.glob("*.safetensors")) or sorted(root.glob("*.bin"))
    files = sorted(path for path in root.rglob("*") if path.is_file())
    if not weights or not (root / "config.json").is_file():
        raise PreflightError(f"모델 가중치/config.json을 찾을 수 없다: {root}")
    hashes = {str(path.relative_to(root)): sha256_file(path) for path in files}
    return {
        "path": str(root.resolve()), "files": hashes,
        "sha256": sha256_bytes(canonical_json(hashes).encode("utf-8")),
    }


def git_revision():
    try:
        commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], stderr=subprocess.DEVNULL,
            text=True).strip()
        dirty = bool(subprocess.check_output(
            ["git", "status", "--porcelain"], stderr=subprocess.DEVNULL,
            text=True).strip())
        return {"commit": commit, "dirty": dirty}
    except (OSError, subprocess.CalledProcessError):
        return {"commit": "unknown", "dirty": None}


def atomic_json(path, value):
    path = Path(path)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, path)


def prepare_output(out, manifest, resume):
    out = Path(out)
    if resume:
        config_path = out / "run_config.json"
        if not config_path.is_file():
            raise PreflightError("재개할 run_config.json이 없다")
        with config_path.open(encoding="utf-8") as f:
            previous = json.load(f)
        if previous.get("status") != "prepared":
            raise PreflightError("완료된 실행은 재개할 수 없다")
        if previous.get("run_signature") != manifest["run_signature"]:
            raise PreflightError("데이터·모델·코드·설정이 원래 실행과 달라 재개할 수 없다")
        if resume == "latest":
            if not any(path.is_dir() for path in out.glob("checkpoint-*")):
                raise PreflightError("재개할 checkpoint가 없다")
        else:
            checkpoint = Path(resume).resolve()
            if checkpoint.parent != out.resolve() or not checkpoint.is_dir():
                raise PreflightError("재개 checkpoint는 --out 바로 아래에 있어야 한다")
        return True if resume == "latest" else resume
    if out.exists() and any(out.iterdir()):
        raise PreflightError(f"출력 디렉터리가 비어 있지 않다: {out}")
    out.mkdir(parents=True, exist_ok=True)
    atomic_json(out / "run_config.json", manifest)
    return None


def validate_control_manifest(path, treatment, baseline_data_sha256):
    with open(path, encoding="utf-8") as f:
        control = json.load(f)
    if control.get("status") not in ("prepared", "completed"):
        raise PreflightError("v4-control의 prepared/completed run_config.json이 필요하다")
    control_signature_input = control.get("signature_input", {})
    expected_signature = sha256_bytes(canonical_json(control_signature_input).encode("utf-8"))
    if control.get("run_signature") != expected_signature:
        raise PreflightError("v4-control run_signature가 manifest 내용과 맞지 않는다")
    if control_signature_input.get("data_sha256") != baseline_data_sha256:
        raise PreflightError("v4-control manifest가 --baseline-data-dir의 파일과 다르다")
    comparisons = {
        "model": (control_signature_input.get("model_sha256"),
                  treatment["signature_input"]["model_sha256"]),
        "code": (control_signature_input.get("code_sha256"),
                 treatment["signature_input"]["code_sha256"]),
        "recipe": (control_signature_input.get("recipe"),
                   treatment["signature_input"]["recipe"]),
        "SFT config": (control.get("resolved_sft_config"),
                       treatment["resolved_sft_config"]),
        "dependencies": (control.get("versions"), treatment["versions"]),
        "runtime": (control.get("runtime"), treatment["runtime"]),
    }
    mismatches = [name for name, (left, right) in comparisons.items() if left != right]
    if mismatches:
        raise PreflightError("v4-control과 실행 조건이 다르다: " + ", ".join(mismatches))
    return {"run_signature": control.get("run_signature"), "matched": sorted(comparisons)}


def self_check():
    def row(question, facts, answer, pair_id=None, variant=None, target=None):
        value = {
            "prompt": [
                {"role": "system", "content": "system"},
                {"role": "user", "content":
                 f"질문: {question}\n\n지식그래프 사실:\n" +
                 "\n".join(f"{i}. {fact}" for i, fact in enumerate(facts, 1)) +
                 "\n\n위 사실만 근거로 답하세요."},
            ],
            "completion": [{"role": "assistant", "content": answer}],
        }
        if pair_id or target:
            value["meta"] = {"pair_id": pair_id, "variant": variant,
                             "question": question,
                             "target": target or
                             ("answerable" if variant == "positive" else "unanswerable")}
        return value

    multi = row("후속 질문", ["이전 근거"], "후속 답변")
    multi["prompt"] = [multi["prompt"][0],
                       {"role": "user", "content": "첫 질문"},
                       {"role": "assistant", "content": "첫 답변"},
                       multi["prompt"][1]]
    validate_sample(multi, "multi")
    broken_multi = {**multi, "prompt": multi["prompt"][:-1]}
    try:
        validate_sample(broken_multi, "broken-multi")
        raise AssertionError("assistant로 끝나는 prompt를 허용했다")
    except PreflightError:
        pass

    positive = row("무엇을 정했나?", ["관련 사실", "결정적 사실"], "결정했어요.", "p1", "positive")
    negative = row("무엇을 정했나?", ["관련 사실"], "근거가 없어요.", "p1", "negative")
    validate_boundary_pairs([positive, negative], 1)
    with tempfile.TemporaryDirectory() as tmp:
        for split, rows in (("train", [positive, negative]),
                            ("dev", [row("다른 질문?", ["다른 사실"], "답해요.")])):
            with open(Path(tmp) / f"{split}.jsonl", "w", encoding="utf-8") as f:
                for value in rows:
                    f.write(json.dumps(value, ensure_ascii=False) + "\n")
        assert len(validate_dataset_dir(tmp)["train"]["rows"]) == 2
    broken = row("무엇을 정했나?", ["관련 사실", "다른 사실"], "근거가 없어요.", "p1", "negative")
    try:
        validate_boundary_pairs([positive, broken], 1)
    except PreflightError:
        pass
    else:
        raise AssertionError("사실 하나 제거 검문이 실패를 놓쳤다")
    reordered_positive = row(
        "순서도 같나?", ["첫째", "결정적", "셋째"], "답해요.", "p2", "positive")
    reordered_negative = row(
        "순서도 같나?", ["셋째", "첫째"], "거절해요.", "p2", "negative")
    try:
        validate_boundary_pairs([reordered_positive, reordered_negative], 1)
    except PreflightError:
        pass
    else:
        raise AssertionError("사실 순서 변경을 놓쳤다")
    stale = row("prompt 질문", ["사실"], "답해요.")
    stale["question"] = "다른 metadata 질문"
    try:
        question_from_row(stale, "stale")
    except PreflightError:
        pass
    else:
        raise AssertionError("stale question metadata를 놓쳤다")
    class FakeTokenizer:
        def apply_chat_template(self, *args, **kwargs):
            return {"input_ids": [1, 2, 3], "attention_mask": [1, 1, 1]}
    assert token_stats([positive], FakeTokenizer(), 4)["tokens_max"] == 3

    from types import SimpleNamespace
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        baseline = [row(f"baseline-{i}", [f"fact-{i}"], f"answer-{i}",
                        target="answerable" if i < 32 else
                        "unanswerable" if i < 64 else "answerable")
                    for i in range(449)]
        dev = [row(f"dev-{i}", [f"dev-fact-{i}"], f"dev-answer-{i}",
                   target="answerable") for i in range(79)]
        pairs = []
        for i in range(32):
            pairs += [
                row(f"pair-{i}", [f"near-{i}", f"key-{i}"], f"yes-{i}",
                    f"p-{i}", "positive"),
                row(f"pair-{i}", [f"near-{i}"], f"no-{i}", f"p-{i}", "negative"),
            ]
        treatment = pairs + baseline[64:]

        def write_jsonl(path, values):
            with open(path, "w", encoding="utf-8") as f:
                for value in values:
                    f.write(json.dumps(value, ensure_ascii=False) + "\n")

        for directory, train in ((tmp / "control", baseline), (tmp / "treatment", treatment)):
            directory.mkdir()
            write_jsonl(directory / "train.jsonl", train)
            write_jsonl(directory / "dev.jsonl", dev)
        audit = [{"question": f"audit-{i}", "label": "answerable"} for i in range(60)]
        for i in range(20):
            audit += [
                {"question": f"audit-pair-{i}", "label": "answerable"},
                {"question": f"audit-pair-{i}", "label": "unanswerable_hard"},
            ]
        write_jsonl(tmp / "audit.jsonl", audit)
        selection = [{"question": f"selection-{i}", "label": "answerable"}
                     for i in range(135)]
        for i in range(32):
            selection += [
                {"question": f"selection-pair-{i}", "label": "answerable"},
                {"question": f"selection-pair-{i}", "label": "unanswerable_hard"},
            ]
        write_jsonl(tmp / "selection.jsonl", selection)
        sealed = []
        for i in range(150):
            sealed += [
                row(f"sealed-{i}", [f"near-{i}", f"key-{i}"], "", f"s-{i}", "positive"),
                row(f"sealed-{i}", [f"near-{i}"], "", f"s-{i}", "negative"),
            ]
        write_jsonl(tmp / "sealed.jsonl", sealed)
        baseline_config = {"args": {
            "model": "model", "epochs": 6.0, "lr": 2e-4, "max_seq_len": 8192,
            "lora_r": 16, "lora_dropout": 0.1, "seed": 42,
        }}
        with open(tmp / "run_config.json", "w", encoding="utf-8") as f:
            json.dump(baseline_config, f)
        args = SimpleNamespace(
            model="model", epochs=6.0, lr=2e-4, max_seq_len=8192,
            lora_r=16, lora_dropout=0.1, seed=42,
            baseline_data_dir=str(tmp / "control"),
            baseline_run_config=str(tmp / "run_config.json"),
            audit_questions=str(tmp / "audit.jsonl"),
            selection_questions=str(tmp / "selection.jsonl"),
            sealed_questions=str(tmp / "sealed.jsonl"),
        )
        report = validate_v4(args, validate_dataset_dir(tmp / "treatment"))
        assert report["boundary_pairs"] == 32 and report["unchanged_train_rows"] == 385
        treatment[64], treatment[65] = treatment[65], treatment[64]
        write_jsonl(tmp / "treatment" / "train.jsonl", treatment)
        try:
            validate_v4(args, validate_dataset_dir(tmp / "treatment"))
        except PreflightError:
            pass
        else:
            raise AssertionError("train 순서 변경을 놓쳤다")
        treatment[64], treatment[65] = treatment[65], treatment[64]
        write_jsonl(tmp / "treatment" / "train.jsonl", treatment)
        manifest = {"status": "prepared", "run_signature": "test"}
        prepare_output(tmp / "run", manifest, None)
        (tmp / "run" / "checkpoint-1").mkdir()
        assert prepare_output(tmp / "run", manifest, "latest") is True
        try:
            prepare_output(tmp / "run", manifest, None)
        except PreflightError:
            pass
        else:
            raise AssertionError("비어 있지 않은 output 보호가 실패했다")
        control_signature_input = {
            "model_sha256": "m", "code_sha256": "c", "recipe": {},
            "data_sha256": {"train": "t", "dev": "d"},
        }
        control_manifest = {
            "status": "completed",
            "run_signature": sha256_bytes(canonical_json(control_signature_input).encode("utf-8")),
            "signature_input": control_signature_input,
            "resolved_sft_config": {}, "versions": {}, "runtime": {},
        }
        with open(tmp / "control-run.json", "w", encoding="utf-8") as f:
            json.dump(control_manifest, f)
        assert validate_control_manifest(
            tmp / "control-run.json", control_manifest,
            {"train": "t", "dev": "d"})["run_signature"] == control_manifest["run_signature"]
        control_manifest["status"] = "prepared"
        with open(tmp / "control-run.json", "w", encoding="utf-8") as f:
            json.dump(control_manifest, f)
        assert validate_control_manifest(
            tmp / "control-run.json", control_manifest,
            {"train": "t", "dev": "d"})["run_signature"] == control_manifest["run_signature"]
        control_manifest["versions"] = {"trl": "wrong"}
        try:
            validate_control_manifest(
                tmp / "control-run.json", control_manifest,
                {"train": "t", "dev": "d"})
        except PreflightError:
            pass
        else:
            raise AssertionError("control/treatment 환경 차이를 놓쳤다")
    print("SELF_CHECK_OK")


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-check", action="store_true")
    ap.add_argument("--model")
    ap.add_argument("--data-dir", help="train.jsonl + dev.jsonl")
    ap.add_argument("--out")
    ap.add_argument("--epochs", type=float, default=6.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--max-seq-len", type=int, default=8192)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--lora-dropout", type=float, default=0.1)
    ap.add_argument("--train-batch-size", type=int, default=2)
    ap.add_argument("--gradient-accumulation-steps", type=int, default=4)
    ap.add_argument("--dataloader-num-workers", type=int, default=0)
    ap.add_argument("--no-gradient-checkpointing", action="store_true")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--protocol", choices=("standard", "v4"), default="standard")
    ap.add_argument("--baseline-data-dir")
    ap.add_argument("--baseline-run-config")
    ap.add_argument("--audit-questions")
    ap.add_argument("--selection-questions")
    ap.add_argument("--sealed-questions")
    ap.add_argument("--check-only", action="store_true")
    ap.add_argument("--resume-from-checkpoint", nargs="?", const="latest")
    args = ap.parse_args()
    if args.train_batch_size < 1 or args.gradient_accumulation_steps < 1:
        ap.error("batch size와 gradient accumulation은 1 이상이어야 한다")
    if not args.self_check:
        missing = [name for name in ("model", "data_dir", "out") if not getattr(args, name)]
        if missing:
            ap.error("필수 인자: " + ", ".join("--" + x.replace("_", "-") for x in missing))
    return args


def main():
    args = parse_args()
    if args.self_check:
        self_check()
        return

    started = time.time()
    current = validate_dataset_dir(args.data_dir)
    protocol_report = validate_v4(args, current) if args.protocol == "v4" else {}

    import datasets
    import peft
    import torch
    import transformers
    import trl
    from datasets import load_dataset
    from peft import LoraConfig
    from transformers import AutoTokenizer, enable_full_determinism
    from trl import SFTConfig, SFTTrainer

    if not torch.cuda.is_available() or not torch.cuda.is_bf16_supported():
        raise PreflightError("bf16 CUDA GPU가 필요하다")
    if args.protocol == "v4" and torch.cuda.device_count() != 1:
        raise PreflightError("v4는 CUDA_VISIBLE_DEVICES로 GPU 한 장만 노출해야 한다")
    enable_full_determinism(args.seed)

    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    stats = {
        split: token_stats(current[split]["rows"], tokenizer, args.max_seq_len)
        for split in ("train", "dev")
    }
    bad = [split for split, value in stats.items() if value["truncated_at_max_len"]]
    if bad:
        raise PreflightError(f"{bad}에 max_length 절단이 있다")

    train_rows = len(current["train"]["rows"])
    updates_per_epoch = math.ceil(
        math.ceil(train_rows / args.train_batch_size) / args.gradient_accumulation_steps)
    total_steps = math.ceil(updates_per_epoch * args.epochs)
    warmup_steps = math.ceil(total_steps * 0.1)
    if args.protocol == "v4" and (total_steps, warmup_steps) != (342, 35):
        raise PreflightError(f"v4 step 수가 달라졌다: total={total_steps}, warmup={warmup_steps}")

    peft_config = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_r * 2,
        lora_dropout=args.lora_dropout,
        target_modules=TARGET_MODULES,
        bias="none",
        task_type="CAUSAL_LM",
    )
    config = SFTConfig(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=warmup_steps,
        per_device_train_batch_size=args.train_batch_size,
        gradient_accumulation_steps=args.gradient_accumulation_steps,
        max_length=args.max_seq_len,
        packing=False,
        completion_only_loss=True,
        bf16=True,
        tf32=False,
        optim="adamw_torch_fused",
        weight_decay=0.0,
        adam_beta1=0.9,
        adam_beta2=0.999,
        adam_epsilon=1e-8,
        max_grad_norm=1.0,
        gradient_checkpointing=not args.no_gradient_checkpointing,
        loss_type="chunked_nll",
        model_init_kwargs={"dtype": "float32"},
        use_cache=False,
        logging_steps=5,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=None,
        save_only_model=False,
        load_best_model_at_end=False,
        report_to=[],
        seed=args.seed,
        data_seed=args.seed,
        full_determinism=True,
        dataloader_num_workers=args.dataloader_num_workers,
        train_sampling_strategy="random",
        shuffle_dataset=False,
        restore_callback_states_from_checkpoint=True,
    )

    resolved_sft_config = config.to_dict()
    for path_key in ("output_dir", "logging_dir", "run_name"):
        resolved_sft_config.pop(path_key, None)
    dependency_versions = {
        package: importlib.metadata.version(package)
        for package in ("torch", "transformers", "trl", "peft", "datasets", "accelerate")
    }
    runtime = {
        "python": platform.python_version(),
        "gpu": torch.cuda.get_device_name(0),
        "gpu_count": torch.cuda.device_count(),
        "cuda": torch.version.cuda,
        "matmul_tf32": torch.backends.cuda.matmul.allow_tf32,
        "cudnn_tf32": torch.backends.cudnn.allow_tf32,
        "deterministic_algorithms": torch.are_deterministic_algorithms_enabled(),
    }
    model_info = model_fingerprint(args.model)
    code_sha = sha256_file(__file__)
    signature_input = {
        "protocol": args.protocol,
        "model_sha256": model_info["sha256"],
        "data_sha256": {split: current[split]["file_sha256"] for split in ("train", "dev")},
        "code_sha256": code_sha,
        "recipe": {
            "epochs": args.epochs, "lr": args.lr, "max_seq_len": args.max_seq_len,
            "lora_r": args.lora_r, "lora_dropout": args.lora_dropout,
            "train_batch_size": args.train_batch_size,
            "gradient_accumulation_steps": args.gradient_accumulation_steps,
            "dataloader_num_workers": args.dataloader_num_workers,
            "gradient_checkpointing": not args.no_gradient_checkpointing,
            "seed": args.seed, "total_steps": total_steps, "warmup_steps": warmup_steps,
            "target_modules": TARGET_MODULES,
        },
        "evaluation_file_sha256": {
            name: value["file_sha256"]
            for name, value in protocol_report.get("evaluation_sets", {}).items()
        },
        "control_run_signature": protocol_report.get("baseline_run_signature"),
        "resolved_sft_config": resolved_sft_config,
        "versions": dependency_versions,
        "runtime": runtime,
    }
    manifest = {
        "status": "prepared",
        "created_at_unix": time.time(),
        "command": sys.argv,
        "args": {
            "model": args.model, "epochs": args.epochs, "lr": args.lr,
            "max_seq_len": args.max_seq_len, "lora_r": args.lora_r,
            "lora_dropout": args.lora_dropout, "seed": args.seed,
            "train_batch_size": args.train_batch_size,
            "gradient_accumulation_steps": args.gradient_accumulation_steps,
            "dataloader_num_workers": args.dataloader_num_workers,
            "gradient_checkpointing": not args.no_gradient_checkpointing,
        },
        "run_signature": sha256_bytes(canonical_json(signature_input).encode("utf-8")),
        "signature_input": signature_input,
        "git": git_revision(),
        "model": model_info,
        "dataset_stats": stats,
        "protocol_report": protocol_report,
        "resolved_sft_config": resolved_sft_config,
        "versions": dependency_versions,
        "runtime": runtime,
    }
    if args.protocol == "v4":
        protocol_report["control_manifest"] = validate_control_manifest(
            args.baseline_run_config, manifest,
            protocol_report["baseline_file_sha256"])
    canonical_json(manifest)  # check-only에서도 manifest 직렬화 실패를 잡는다.
    print("[preflight]", json.dumps({
        "dataset_stats": stats, "protocol": protocol_report,
        "run_signature": manifest["run_signature"],
    }, ensure_ascii=False))

    def make_trainer():
        data = load_dataset("json", data_files={
            "train": current["train"]["path"], "dev": current["dev"]["path"],
        })
        for split in ("train", "dev"):
            extras = [name for name in data[split].column_names
                      if name not in ("prompt", "completion")]
            if extras:
                data[split] = data[split].remove_columns(extras)
        return SFTTrainer(
            model=args.model,
            args=config,
            train_dataset=data["train"],
            eval_dataset=data["dev"],
            processing_class=tokenizer,
            peft_config=peft_config,
        )

    if args.check_only:
        make_trainer()
        print("PREFLIGHT_AND_TRAINER_INIT_OK")
        return

    resume = prepare_output(args.out, manifest, args.resume_from_checkpoint)
    trainer = make_trainer()
    result = trainer.train(resume_from_checkpoint=resume)
    manifest["status"] = "completed"
    manifest["completed_at_unix"] = time.time()
    manifest["elapsed_seconds"] = round(time.time() - started, 3)
    manifest["training_loss"] = result.training_loss
    manifest["global_step"] = result.global_step
    atomic_json(Path(args.out) / "run_config.json", manifest)
    print(f"[done] step={result.global_step} train_loss={result.training_loss:.4f} → {args.out}")
    print("       checkpoint 선택은 별도 selection set의 행동 지표로 수행할 것")


if __name__ == "__main__":
    try:
        main()
    except PreflightError as exc:
        sys.exit(f"[중단] {exc}")
