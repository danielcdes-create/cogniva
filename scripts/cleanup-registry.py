"""Keep five recent Cogniva image versions plus the current/rollback digests.

Paginates across all GHCR package versions; uses GITHUB_TOKEN/GH_TOKEN without logging it.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

PACKAGE = os.environ.get('COGNIVA_GHCR_PACKAGE', 'cogniva-api')
OWNER = os.environ.get('COGNIVA_GHCR_OWNER', 'danielcdes-create')
BASE = f'https://api.github.com/users/{OWNER}/packages/container/{PACKAGE}/versions'
HEADERS = {
    'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
}

with open(sys.argv[1], encoding='utf-8') as f:
    current = json.load(f)
protected = {
    value.split('@', 1)[-1]
    for value in [current.get('image', ''), current.get('previous_image', '')]
    if '@' in value
}


def fetch_all_versions() -> list[dict]:
    url: str | None = BASE + '?per_page=100'
    items: list[dict] = []
    while url:
        req = urllib.request.Request(url, headers=HEADERS, method='GET')
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


def delete_version(version_id: int) -> None:
    req = urllib.request.Request(f'{BASE}/{version_id}', headers=HEADERS, method='DELETE')
    with urllib.request.urlopen(req, timeout=30):
        return


versions = fetch_all_versions()
versions.sort(key=lambda v: v['created_at'], reverse=True)
deleted = []
for version in versions[5:]:
    tags = version.get('metadata', {}).get('container', {}).get('tags', [])
    if version['name'] in protected or 'latest' in tags:
        continue
    delete_version(version['id'])
    deleted.append(version['id'])

print(
    json.dumps(
        {
            'package': PACKAGE,
            'retained_recent': min(5, len(versions)),
            'protected_current_and_rollback': True,
            'deleted_old_versions': deleted,
        }
    )
)
