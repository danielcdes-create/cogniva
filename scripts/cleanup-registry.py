"""Keep five recent Cogniva image versions plus the current/rollback digests.

Paginates across all GHCR package versions; uses GITHUB_TOKEN/GH_TOKEN without logging it.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import urllib.error
import urllib.request

DEFAULT_PACKAGE = 'cogniva-api'
DEFAULT_OWNER = 'danielcdes-create'


def build_base_url(owner: str | None = None, package: str | None = None) -> str:
    eff_owner = owner or os.environ.get('COGNIVA_GHCR_OWNER', DEFAULT_OWNER)
    eff_package = package or os.environ.get('COGNIVA_GHCR_PACKAGE', DEFAULT_PACKAGE)
    return f'https://api.github.com/users/{eff_owner}/packages/container/{eff_package}/versions'


def build_headers(token: str | None = None) -> dict[str, str]:
    eff_token = token if token is not None else (os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN'))
    if not eff_token:
        raise KeyError('GH_TOKEN')
    return {
        'Authorization': 'Bearer ' + eff_token,
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
    }


def fetch_all_versions(base_url: str, headers: dict[str, str]) -> list[dict]:
    url: str | None = base_url + '?per_page=100'
    items: list[dict] = []
    while url:
        req = urllib.request.Request(url, headers=headers, method='GET')
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                items.extend(json.loads(resp.read()))
                link = resp.headers.get('Link', '')
                match = re.search(r'<([^>]+)>;\s*rel="next"', link)
                url = match.group(1) if match else None
        except urllib.error.HTTPError as err:
            if err.code == 404:
                return []
            raise
    return items


def delete_version(base_url: str, headers: dict[str, str], version_id: int) -> None:
    req = urllib.request.Request(f'{base_url}/{version_id}', headers=headers, method='DELETE')
    with urllib.request.urlopen(req, timeout=30):
        return


def _extract_tag(image_ref: str) -> str | None:
    if not image_ref or '@' in image_ref or image_ref.startswith('sha256:'):
        return None
    tail = image_ref.rsplit('/', 1)[-1]
    if ':' in tail:
        tag = tail.rsplit(':', 1)[-1].strip()
        return tag or None
    return None


def cleanup_registry(
    release_manifest_path: pathlib.Path,
    *,
    token: str | None = None,
    owner: str | None = None,
    package: str | None = None,
    keep_recent: int = 5,
) -> dict:
    eff_package = package or os.environ.get('COGNIVA_GHCR_PACKAGE', DEFAULT_PACKAGE)
    base_url = build_base_url(owner=owner, package=eff_package)
    headers = build_headers(token=token)

    current = json.loads(release_manifest_path.read_text(encoding='utf-8'))
    image_ref = str(current.get('image') or '')
    prev_ref = str(current.get('previous_image') or '')

    protected_digests = {
        value.split('@', 1)[-1]
        for value in (image_ref, prev_ref)
        if '@' in value
    }
    protected_tags = {'latest'}
    for ref in (image_ref, prev_ref):
        tag = _extract_tag(ref)
        if tag:
            protected_tags.add(tag)

    versions = fetch_all_versions(base_url, headers)
    versions.sort(key=lambda v: v['created_at'], reverse=True)
    deleted: list[int] = []
    for version in versions[keep_recent:]:
        tags = set(version.get('metadata', {}).get('container', {}).get('tags', []))
        if version['name'] in protected_digests or bool(tags.intersection(protected_tags)):
            continue
        delete_version(base_url, headers, version['id'])
        deleted.append(version['id'])

    return {
        'package': eff_package,
        'retained_recent': min(keep_recent, len(versions)),
        'protected_current_and_rollback': ('@' in image_ref and '@' in prev_ref),
        'deleted_old_versions': deleted,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('release_manifest', type=pathlib.Path)
    args = parser.parse_args(argv)
    summary = cleanup_registry(args.release_manifest)
    print(json.dumps(summary))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
