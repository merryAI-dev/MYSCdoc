"""Install the local Muse runtime without overwriting credentials or starting services."""
import os
from pathlib import Path
import subprocess
import sys
import venv

ROOT = Path(__file__).resolve().parents[1]
MUSE = ROOT / 'integrations/nanomuse'

def main():
    if sys.version_info[:2] != (3, 13):
        raise SystemExit('Run this setup with Python 3.13 (python3.13 scripts/setup_muse.py).')
    if not (ROOT / '.env').exists():
        with (ROOT / '.env').open('x') as dest:
            dest.write((ROOT / '.env.muse.example').read_text())
        os.chmod(ROOT / '.env', 0o600)
    target = MUSE / '.venv'
    if not target.exists():
        venv.EnvBuilder(with_pip=True).create(target)
    python = target / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    subprocess.run([str(python), '-m', 'pip', 'install', '-r', str(MUSE / 'requirements.txt')], check=True)
    subprocess.run([str(python), '-m', 'playwright', 'install', 'chromium'], check=True)
    print('Muse installed. Set your key in .env. Follow integrations/nanomuse/README.md to start.')

if __name__ == '__main__':
    main()
