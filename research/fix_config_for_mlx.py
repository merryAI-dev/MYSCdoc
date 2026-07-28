#!/usr/bin/env python3
"""
병합 모델 config를 MLX가 읽는 형식으로 되돌린다.

원인: GPU 서버의 transformers 5.14.1이 rope_theta와 rope_scaling을 rope_parameters 하나로
합쳐 저장한다. mlx-lm 0.31.3의 exaone4 ModelArgs는 둘을 분리된 필드로 요구해서 변환이
TypeError로 죽는다. 값 자체는 rope_parameters 안에 그대로 있으므로 형식만 되돌리면 된다.

sliding_window_pattern도 정리한다. 병합본에는 0(int)이 들어있는데 mlx는 str을 기대한다.
지금은 falsy라 우연히 동작하지만(is_local=None → 전 층 RoPE, 1.2B에선 올바른 동작),
의도를 명시하려 upstream과 같이 null로 맞춘다. 1.2B는 30층 전부 full_attention이라
슬라이딩 윈도우 자체가 없다 (하이브리드 어텐션은 32B 전용).

사용: venv/bin/python fix_config_for_mlx.py mlx/merged-v4
"""
import json
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "mlx/merged-v4"
cfg_path = f"{path}/config.json"
cfg = json.load(open(cfg_path))

params = cfg.get("rope_parameters")
if params is None:
    print("[skip] rope_parameters 없음 — 이미 구형식이거나 예상과 다른 config")
    sys.exit(0)

params = dict(params)
theta = params.pop("rope_theta", None)
if theta is None:
    raise SystemExit("rope_parameters 안에 rope_theta가 없다 — 수동 확인 필요")

cfg["rope_theta"] = theta
cfg["rope_scaling"] = params          # factor/high_freq/low_freq/original_max/rope_type
if not isinstance(cfg.get("sliding_window_pattern"), str):
    cfg["sliding_window_pattern"] = None

json.dump(cfg, open(cfg_path, "w"), ensure_ascii=False, indent=2)
print(f"[fix] rope_theta={theta} · rope_scaling={cfg['rope_scaling']}")
print(f"[fix] sliding_window_pattern={cfg['sliding_window_pattern']} · 저장 완료")
