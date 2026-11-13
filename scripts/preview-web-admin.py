"""Loopback-only browser QA with disposable synthetic Mongo; never uses a production URI."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import uuid

from bson import json_util
from pymongo import MongoClient

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8876)
    parser.add_argument('--minutes', type=int, default=20)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535 or not 1 <= args.minutes <= 60:
        raise ValueError('Invalid loopback port or duration')

    binary = ROOT / 'backend-rust/target/debug' / ('cogniva-api.exe' if os.name == 'nt' else 'cogniva-api')
    if not binary.is_file():
        raise RuntimeError('Build locally first: cargo build --manifest-path backend-rust/Cargo.toml --locked')

    client = MongoClient('mongodb://127.0.0.1:27048/', serverSelectionTimeoutMS=5000)
    client.admin.command('ping')
    name = 'cogniva_test_web_preview_' + uuid.uuid4().hex
    db = client[name]
    temporary = Path(tempfile.gettempdir()) / name
    temporary.mkdir()
    ready = temporary / 'ready.json'
    stop = temporary / 'stop'
    admin_email = 'admin@cogniva.local'
    env = dict(
        os.environ,
        MONGODB_URI='mongodb://127.0.0.1:27048/',
        DATABASE_NAME=name,
        JWT_SECRET=uuid.uuid4().hex + uuid.uuid4().hex,
        ADMIN_EMAILS=admin_email,
        BIND_ADDRESS=f'127.0.0.1:{args.port}',
        XAI_API_KEY='',
    )
    process = None
    try:
        subprocess.run(
            [str(binary), '--prepare-storage'],
            env=env,
            cwd=temporary,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        for filename in ['question-multiple-choice.json', 'question-true-false.json', 'question-open-manual.json']:
            db.todasQuestoes.insert_one(
                json_util.loads((ROOT / 'shared/examples/question-contracts' / filename).read_text(encoding='utf-8'))
            )
        log = (temporary / 'backend.log').open('w', encoding='utf-8')
        process = subprocess.Popen([str(binary)], env=env, cwd=temporary, stdout=log, stderr=log)
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError('Preview backend exited; inspect synthetic backend.log')
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{args.port}/api/v1/health', timeout=1):
                    break
            except OSError:
                time.sleep(0.2)
        else:
            raise RuntimeError('Loopback preview did not become ready')

        ready.write_text(
            json.dumps(
                {
                    'url': f'http://127.0.0.1:{args.port}/admin/',
                    'admin_email': admin_email,
                    'database': name,
                    'stop_file': str(stop),
                    'synthetic_only': True,
                }
            ),
            encoding='utf-8',
        )
        print(json.dumps({'ready_file': str(ready), 'url': f'http://127.0.0.1:{args.port}/admin/'}), flush=True)
        deadline = time.monotonic() + args.minutes * 60
        while time.monotonic() < deadline and not stop.exists() and process.poll() is None:
            time.sleep(1)
    finally:
        if process and process.poll() is None:
            process.terminate()
            process.wait(timeout=15)
        assert name.startswith('cogniva_test_web_preview_')
        client.drop_database(name)
        client.close()
        ready.unlink(missing_ok=True)
        stop.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
