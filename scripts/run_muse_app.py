"""Start the shared local demo with an empty corpus unless explicitly provided."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
env = dict(os.environ)
env.setdefault('MARINE_CORPUS', str(ROOT / 'integrations/nanomuse/empty-corpus.json'))
raise SystemExit(subprocess.call([sys.executable, str(ROOT / 'scripts/run_marine_local.py')], cwd=ROOT, env=env))
