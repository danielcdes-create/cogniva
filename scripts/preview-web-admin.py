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

from bson import ObjectId, json_util
from pymongo import MongoClient

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8876)
    parser.add_argument('--minutes', type=int, default=20)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535 or not 1 <= args.minutes <= 60:
        raise ValueError('Invalid loopback port or duration')
    binary = ROOT / 'backend-rust/target/debug' / ('api-rust-core.exe' if os.name == 'nt' else 'api-rust-core')
    if not binary.is_file():
        raise RuntimeError('Build locally first: cd backend-rust && cargo build --locked')
    client = MongoClient('mongodb://127.0.0.1:27048/', serverSelectionTimeoutMS=5000)
    client.admin.command('ping')
    name = 'cogniva_test_web_preview_' + uuid.uuid4().hex
    db = client[name]
    temporary = Path(tempfile.gettempdir()) / name
    temporary.mkdir()
    ready = temporary / 'ready.json'
    stop = temporary / 'stop'
    env = dict(os.environ, MONGODB_URI='mongodb://127.0.0.1:27048/', MONGODB_DB=name,
               JWT_SECRET=uuid.uuid4().hex + uuid.uuid4().hex, ADMIN_USER_IDS='123',
               PORT=str(args.port), BIND_ADDRESS='127.0.0.1', XAI_API_KEY='',
               OPENAI_API_KEY='', GEMINI_API_KEY='', COGNIVA_LEGACY_AUTO_CLASSIFY='0')
    process = None
    try:
        db.usuarios.insert_one({'user_id': 123, 'display_name': 'Admin de demonstração', 'active_contest_ids': ['CPNU']})
        subprocess.run([str(binary), '--prepare-question-storage'], env=env, cwd=temporary, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        for filename in ['question-multiple-choice.json', 'question-true-false.json', 'question-open-manual.json']:
            db.todasQuestoes.insert_one(json_util.loads((ROOT / 'shared/examples/question-contracts' / filename).read_text(encoding='utf-8')))
        db.todasQuestoes.insert_one({
            # The production-shaped guard only permits legacy shapes at frozen IDs.
            # Reuse an allowed identity exclusively in this disposable synthetic DB.
            '_id': ObjectId(json.loads((ROOT / 'shared/legacy-classification-cohort.json').read_text(encoding='utf-8'))['members'][0]['question_id']),
            'question': 'Questão legada sintética: quanto é 2 + 2?',
            'question_type': 'MULTIPLE_CHOICE', 'answers': ['1', '2', '3', '4', '5'],
            'correct_answers': ['4'], 'source': 'QA local',
            'unknown_extra': {'preserve': True},
        })
        code = subprocess.check_output([str(binary), '--issue-web-code', '123'], env=env, cwd=temporary, text=True).strip()
        log = (temporary / 'backend.log').open('w', encoding='utf-8')
        process = subprocess.Popen([str(binary)], env=env, cwd=temporary, stdout=log, stderr=log)
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError('Preview backend exited; inspect the synthetic backend.log')
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{args.port}/api/v1/health', timeout=1):
                    break
            except OSError:
                time.sleep(.2)
        else:
            raise RuntimeError('Loopback preview did not become ready')
        ready.write_text(json.dumps({'url': f'http://127.0.0.1:{args.port}/admin/', 'code': code,
                                    'database': name, 'stop_file': str(stop), 'synthetic_only': True}), encoding='utf-8')
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
        # Keep synthetic logs for diagnostics, remove the single-use demonstration credential.
        ready.unlink(missing_ok=True)
        stop.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
