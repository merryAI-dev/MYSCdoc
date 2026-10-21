"""Run Claude using environment or local .env credentials; never print them."""
import os, subprocess, sys
from pathlib import Path
root = Path(__file__).resolve().parents[1]
env = dict(os.environ)
config = root / '.env'
if config.exists():
    for line in config.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#') or '=' not in line: continue
        name, value = line.split('=', 1)
        if name.strip() in ('ANTHROPIC_API_KEY', 'ANTHROPIC_CHAT_MODEL', 'ANTHROPIC_MAX_TOKENS'):
            env.setdefault(name.strip(), value.strip().strip('"').strip("'"))
key = env.get('ANTHROPIC_API_KEY', '')
if not key.startswith('sk-ant-') or any(c.isspace() for c in key):
    raise SystemExit('Configure ANTHROPIC_API_KEY in .env or the environment first.')
env.update(ANTHROPIC_API_KEY=key, LLM_PROVIDER='anthropic')
raise SystemExit(subprocess.call(['./gradlew', '--no-daemon', *(sys.argv[1:] or ['bootRun'])], cwd=root, env=env))
