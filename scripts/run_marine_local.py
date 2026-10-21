"""Run the existing MYSCdoc UI against the isolated marine corpus database."""
import os,sys,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
env=dict(os.environ,DB_URL='jdbc:postgresql://127.0.0.1:55436/myscdoc_marine',DB_USERNAME='mydoc',DB_PASSWORD='marine-local-only',SERVER_ADDRESS='127.0.0.1',SERVER_PORT='8086',SPRING_PROFILES_ACTIVE='marine-local',MARINE_CORPUS=os.environ.get('MARINE_CORPUS',str(root.parent/'experiments/marine-context-pilot/corpus.json')))
# Credentials are read from the environment or gitignored .env.
raise SystemExit(subprocess.call([sys.executable,str(root/'scripts/run_claude_local.py'),'bootRun'],cwd=root,env=env))
