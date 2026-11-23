"""Promote a verified immutable bundle with flock isolation and rollback safety.

Supports both live DigitalOcean promotion (against shared /opt/apps/docker-compose.yml)
and isolated Docker Compose rollback drills in GitHub Actions CI.
Usage: python3 promote.py /opt/apps/cogniva-releases/<git-sha> [--exercise-rollback]
"""
try:
    import fcntl
except ImportError:  # pragma: no cover - Windows unit test runner compatibility
    fcntl = None  # type: ignore[assignment]
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(os.environ.get('COGNIVA_APP_ROOT', '/opt/apps')).resolve()
COMPOSE = ROOT / 'docker-compose.yml'
OVERRIDE = ROOT / 'cogniva-runtime.override.json'
PROJECT = os.environ.get('COGNIVA_COMPOSE_PROJECT', 'apps')
SERVICE = os.environ.get('COGNIVA_COMPOSE_SERVICE', 'api-rust-core')
LOCAL_API = os.environ.get('COGNIVA_LOCAL_API', 'http://127.0.0.1:8001').rstrip('/')
PUBLIC_ORIGIN = os.environ.get('COGNIVA_PUBLIC_ORIGIN', 'https://198.199.65.103').rstrip('/')
SKIP_NGINX_GATE = os.environ.get('COGNIVA_SKIP_NGINX_GATE', '0') == '1'
GATE_PATH = pathlib.Path(os.environ.get('COGNIVA_NGINX_GATE_PATH', '/etc/nginx/cogniva-gate.conf'))
LOCK_PATH = ROOT / '.cogniva-deploy.lock'

IMAGE_RE = re.compile(
    r'^ghcr\.io/danielcdes-create/(cogniva-api|api-rust-core)@sha256:[0-9a-f]{64}$'
)


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def inspect_service() -> dict:
    return json.loads(run('docker', 'inspect', SERVICE))[0]


def sha(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def atomic_json(path: pathlib.Path, value: dict) -> None:
    temp = path.with_name(path.name + '.tmp')
    temp.write_text(json.dumps(value, indent=2), encoding='utf-8')
    os.replace(temp, path)


def symlink(target: str, path: pathlib.Path) -> None:
    temp = path.with_name(path.name + '.next')
    temp.unlink(missing_ok=True)
    temp.symlink_to(target)
    os.replace(temp, path)


def link_or_copy(src: pathlib.Path, dst: pathlib.Path) -> None:
    if dst.exists():
        assert sha(dst) == sha(src), f'An immutable release differs: {dst.name}'
        return
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)
    dst.chmod(0o644)


def prune_old_releases(protected_revisions: set[str], keep_latest: int = 3) -> None:
    protected = {rev for rev in protected_revisions if rev and re.fullmatch(r'[0-9a-f]{40}', rev)}
    for base_dir in (ROOT / 'cogniva-releases', ROOT / 'apk' / 'releases'):
        if not base_dir.is_dir():
            continue
        entries = [
            entry
            for entry in base_dir.iterdir()
            if entry.is_dir() and re.fullmatch(r'[0-9a-f]{40}', entry.name)
        ]
        entries.sort(key=lambda p: p.stat().st_mtime, reverse=True)
        keep = {entry.name for entry in entries[:keep_latest]} | protected
        for entry in entries:
            if entry.name not in keep:
                shutil.rmtree(entry, ignore_errors=True)

    incoming = ROOT / 'cogniva-incoming'
    if incoming.is_dir():
        cutoff = time.time() - 3600
        for entry in incoming.iterdir():
            try:
                if entry.stat().st_mtime < cutoff:
                    if entry.is_dir():
                        shutil.rmtree(entry, ignore_errors=True)
                    else:
                        entry.unlink(missing_ok=True)
            except OSError:
                pass


def compose_up() -> None:
    # Named service only: sibling containers on the shared VPS remain untouched.
    print(
        run(
            'docker',
            'compose',
            '-p',
            PROJECT,
            '-f',
            str(COMPOSE),
            '-f',
            str(OVERRIDE),
            'up',
            '-d',
            '--no-deps',
            '--force-recreate',
            SERVICE,
        )
    )


def snapshot_persistent_neighbors() -> dict[str, str]:
    ids = run('docker', 'ps', '-q').split()
    if not ids:
        return {}
    result: dict[str, str] = {}
    for cid in ids:
        try:
            item = json.loads(run('docker', 'inspect', cid))[0]
        except (subprocess.CalledProcessError, IndexError, KeyError, json.JSONDecodeError):
            # Container exited between `docker ps -q` and `docker inspect` (e.g. certbot --rm).
            continue
        name = item.get('Name', '')
        if name == '/' + SERVICE:
            continue
        if item.get('HostConfig', {}).get('AutoRemove'):
            continue
        image_cfg = item.get('Config', {}).get('Image', '')
        if image_cfg.startswith('certbot/certbot'):
            continue
        result[name] = item['Id']
    return result


def verify_neighbors(expected: dict[str, str]) -> None:
    for name, container_id in expected.items():
        current = json.loads(run('docker', 'inspect', name))[0]['Id']
        assert current == container_id, f'Sibling container changed unexpectedly: {name}'


def gate(paused: bool) -> None:
    if SKIP_NGINX_GATE:
        return
    path = GATE_PATH
    temp = path.with_name(path.name + '.tmp')
    temp.write_text(f'set $cogniva_maintenance {1 if paused else 0};\n', encoding='utf-8')
    os.replace(temp, path)
    run('nginx', '-t')
    run('systemctl', 'reload', 'nginx')
    expected_code = 503 if paused else 401
    for _ in range(25):
        try:
            urllib.request.urlopen(f'{PUBLIC_ORIGIN}/api/v1/exams', timeout=3)
        except urllib.error.HTTPError as error:
            if error.code == expected_code:
                return
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
        time.sleep(0.3)
    raise RuntimeError(f'Nginx gate did not reach HTTP {expected_code}')


def wait_ready(revision: str) -> None:
    for _ in range(45):
        try:
            with urllib.request.urlopen(f'{LOCAL_API}/api/v1/ready', timeout=5) as resp:
                value = json.load(resp)
            if value.get('status') == 'ready' and value.get('revision') == revision:
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('Candidate did not become ready with the expected revision')


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    assert args, 'Missing release directory argument'
    release = pathlib.Path(args[0]).resolve()
    assert (
        release.parent == ROOT / 'cogniva-releases'
        and re.fullmatch(r'[0-9a-f]{40}', release.name)
    ), 'Invalid release directory'
    exercise_rollback = '--exercise-rollback' in args

    LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    with LOCK_PATH.open('w', encoding='utf-8') as lock_file:
        if fcntl is not None:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        _promote_locked(release, exercise_rollback=exercise_rollback)
    return 0


def _promote_locked(release: pathlib.Path, *, exercise_rollback: bool | None = None) -> None:
    manifest = json.loads((release / 'release.json').read_text(encoding='utf-8'))
    assert manifest['revision'] == release.name
    image = manifest['image']
    assert IMAGE_RE.fullmatch(image), f'Immutable digest required, got: {image}'

    apk = release / 'cogniva.apk'
    metadata = release / 'output-metadata.json'
    assert sha(apk) == manifest['apk_sha256'] and apk.stat().st_size == manifest['apk_size_bytes']
    element = json.loads(metadata.read_text(encoding='utf-8'))['elements'][0]
    assert (
        element['versionCode'] == manifest['version_code']
        and element['versionName'] == manifest['version_name']
    )

    before = inspect_service()
    assert before['Config']['Labels'].get('com.docker.compose.project') == PROJECT
    assert before['Config']['Labels'].get('com.docker.compose.service') == SERVICE

    others = snapshot_persistent_neighbors()
    compose_hash = sha(COMPOSE)
    current = ROOT / 'apk' / 'current'
    old_target = os.readlink(current) if current.is_symlink() else None
    old_target_rev = pathlib.Path(old_target).name if old_target else ''
    old_override = OVERRIDE.read_bytes() if OVERRIDE.exists() else None
    old_image = before['Image']
    old_details = json.loads(run('docker', 'image', 'inspect', old_image))[0]
    old_runtime_ref = next(iter(old_details.get('RepoDigests', [])), before['Config']['Image'])

    previous = {
        'image_id': old_image,
        'image': before['Config']['Image'],
        'current_target': old_target,
        'compose_sha256': compose_hash,
        'promoted_revision': release.name,
    }
    atomic_json(release / 'previous.json', previous)
    os.chmod(release / 'previous.json', 0o600)

    if os.environ.get('COGNIVA_SKIP_PULL', '0') != '1':
        print(run('docker', 'pull', image))
    size = json.loads(run('docker', 'image', 'inspect', image))[0]['Size']
    assert size < 50_000_000, f'Runtime exceeds 50 MB: {size}'

    missions_videos = ROOT / 'missoes' / 'videos'
    service_override: dict = {
        'image': image,
        'environment': {
            'APK_STORAGE_PATH': '/app/apk/current/cogniva.apk',
            'APK_METADATA_PATH': '/app/apk/current/output-metadata.json',
            'MISSIONS_MEDIA_DIR': '/app/missoes/videos',
        },
        'read_only': True,
        'tmpfs': ['/tmp:size=16m'],
        'security_opt': ['no-new-privileges:true'],
        'cap_drop': ['ALL'],
        'mem_limit': '192m',
        'cpus': 0.75,
        'pids_limit': 128,
        'logging': {
            'driver': 'json-file',
            'options': {'max-size': '10m', 'max-file': '3'},
        },
    }
    if missions_videos.is_dir():
        service_override['volumes'] = [f'{missions_videos}:/app/missoes/videos:ro']
    if old_override is not None:
        try:
            prev_doc = json.loads(old_override.decode('utf-8'))
            prev_env_file = prev_doc.get('services', {}).get(SERVICE, {}).get('env_file')
            if prev_env_file:
                service_override['env_file'] = prev_env_file
        except Exception:
            pass

    override = {'services': {SERVICE: service_override}}

    controlled_failure = False
    runtime_mutated = False
    try:
        gate(True)
        runtime_mutated = True
        atomic_json(OVERRIDE, override)
        apk_release = ROOT / 'apk' / 'releases' / release.name
        apk_release.mkdir(parents=True, exist_ok=True)
        for file in [apk, metadata]:
            link_or_copy(file, apk_release / file.name)
        symlink('releases/' + release.name, current)
        compose_up()
        wait_ready(release.name)

        with urllib.request.urlopen(f'{LOCAL_API}/api/v1/apk/info', timeout=10) as resp:
            info = json.load(resp)
        assert (
            info['sha256_checksum'] == manifest['apk_sha256']
            and info['version_code'] == manifest['version_code']
        )
        if not SKIP_NGINX_GATE:
            with urllib.request.urlopen(f'{PUBLIC_ORIGIN}/api/v1/health', timeout=10) as resp:
                assert json.load(resp)['revision'] == release.name

        assert sha(COMPOSE) == compose_hash, 'Shared Compose changed'
        verify_neighbors(others)

        do_exercise_rollback = (
            exercise_rollback if exercise_rollback is not None else ('--exercise-rollback' in sys.argv)
        )
        if do_exercise_rollback:
            controlled_failure = True
            raise RuntimeError('CONTROLLED_SMOKE_FAILURE_FOR_ROLLBACK')

        print(run('docker', 'exec', SERVICE, '/usr/local/bin/cogniva-api', '--prepare-storage'))
        gate(False)
        atomic_json(
            ROOT / 'cogniva-current-release.json',
            {
                **manifest,
                'runtime_size_bytes': size,
                'previous_image_id': old_image,
                'previous_image': old_runtime_ref,
            },
        )
        prune_old_releases({release.name, old_target_rev})
        print(
            json.dumps(
                {
                    'promoted': release.name,
                    'runtime_size_bytes': size,
                    'other_containers_preserved': True,
                }
            )
        )
    except BaseException as primary_error:
        if not runtime_mutated:
            if not SKIP_NGINX_GATE:
                try:
                    gate_path = GATE_PATH
                    gate_tmp = gate_path.with_name(gate_path.name + '.tmp')
                    gate_tmp.write_text('set $cogniva_maintenance 0;\n', encoding='utf-8')
                    os.replace(gate_tmp, gate_path)
                except BaseException:
                    pass
                try:
                    run('nginx', '-t')
                    run('systemctl', 'reload', 'nginx')
                except BaseException:
                    pass
            raise
        restored_previous = False
        try:
            try:
                gate(True)
            except BaseException:
                pass
            if old_target is not None:
                symlink(old_target, current)
            else:
                current.unlink(missing_ok=True)
            if old_override is not None:
                tmp_override = OVERRIDE.with_name(OVERRIDE.name + '.tmp')
                tmp_override.write_bytes(old_override)
                os.replace(tmp_override, OVERRIDE)
            else:
                atomic_json(OVERRIDE, {'services': {SERVICE: {'image': old_runtime_ref}}})
            compose_up()
            for retry in range(30):
                try:
                    with urllib.request.urlopen(f'{LOCAL_API}/api/v1/health', timeout=5) as resp:
                        assert resp.status == 200
                    restored_previous = True
                    print('ROLLBACK_HEALTHY')
                    break
                except Exception:
                    if retry == 29:
                        raise RuntimeError('Rollback requires operator attention') from primary_error
                    time.sleep(2)
            if restored_previous:
                gate(False)
        except BaseException:
            raise
        if controlled_failure:
            assert inspect_service()['Image'] == old_image, 'Rollback did not restore the previous image'
            assert (os.readlink(current) if current.is_symlink() else None) == old_target
            assert sha(COMPOSE) == compose_hash
            verify_neighbors(others)
            print('CONTROLLED_ROLLBACK_VERIFIED')
        else:
            raise


if __name__ == '__main__':
    raise SystemExit(main())
