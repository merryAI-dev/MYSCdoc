#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec "$root/research/venv/bin/mlx_lm.server" \
  --model "$root/research/mlx/h10c-s43-merged" \
  --host 127.0.0.1 --port 8091 \
  --allowed-origins http://127.0.0.1:8080,http://localhost:8080 \
  --temp 0 --max-tokens 128 \
  --decode-concurrency 1 --prompt-concurrency 1 \
  --prompt-cache-size 2 --prompt-cache-bytes 512M
