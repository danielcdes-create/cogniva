"""Check production web session lifetime and logout without editing content.

Uses the sole already-authorized admin account. Codes and cookies remain in memory.
Creates and revokes one diagnostic web session; never changes ADMIN_USER_IDS.
"""
import argparse
import datetime
import http.cookiejar
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
       '-o', 'ConnectTimeout=5', 'root@198.199.65.103']
remote = r'''
import json, subprocess
core=json.loads(subprocess.check_output(['docker','inspect','api-rust-core']))[0]
env=dict(row.split('=',1) for row in core['Config']['Env'] if '=' in row)
ids=[value.strip() for value in env.get('ADMIN_USER_IDS','').split(',') if value.strip()]
assert len(ids)==1 and ids[0].isdigit(), 'Expected the sole previously verified admin'
code=subprocess.check_output(['docker','exec','api-rust-core','/usr/local/bin/app','--issue-web-code',ids[0]],text=True).strip()
print(json.dumps({'user_id':ids[0],'code':code}))
'''
credential = json.loads(subprocess.check_output(ssh + ['python3', '-'], input=remote, text=True))
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
base = 'https://198.199.65.103'


def request(path, body=None):
    headers = {}
    if body is not None:
        headers = {'Origin': base, 'X-Cogniva-CSRF': '1', 'Content-Type': 'application/json'}
    req = urllib.request.Request(base + path, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    with opener.open(req, timeout=45) as response:
        return response.headers, json.load(response)


started = int(time.time())
logged_in = False
try:
    headers, login = request('/api/v1/auth/web-session', {'code': credential.pop('code')})
    logged_in = True
    cookie = headers.get('Set-Cookie', '')
    assert 'Max-Age=2592000' in cookie
    assert all(flag in cookie for flag in ['Secure', 'HttpOnly', 'SameSite=Strict'])
    lifetime = login['expires_at'] - started
    assert 2591990 <= lifetime <= 2592090, 'JWT and cookie lifetime differ'
    _, profile = request('/api/v1/admin/web/me')
    assert str(profile['user_id']) == credential['user_id']
finally:
    if logged_in:
        request('/api/v1/auth/web-logout', {})
try:
    request('/api/v1/admin/web/me')
    raise AssertionError('Session still authenticated after logout')
except urllib.error.HTTPError as error:
    assert error.code == 401
result = {
    'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'login_verified': True, 'cookie_max_age_seconds': 2592000,
    'jwt_lifetime_matches_cookie': True, 'secure_http_only_same_site_cookie': True,
    'admin_profile_matched_configured_verified_account': True,
    'logout_verified': True, 'post_logout_status': 401,
    'content_mutations': False, 'credentials_stored_in_evidence': False,
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
