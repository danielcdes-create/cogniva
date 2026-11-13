"""Create a measured release manifest after APK signing."""
import argparse
import hashlib
import json
import pathlib

p = argparse.ArgumentParser()
p.add_argument('bundle', type=pathlib.Path)
p.add_argument('--image', required=True)
p.add_argument('--revision', required=True)
a = p.parse_args()

apk = a.bundle / 'cogniva.apk'
metadata = json.loads((a.bundle / 'output-metadata.json').read_text(encoding='utf-8-sig'))['elements'][0]
assert apk.is_file() and apk.stat().st_size > 0

manifest = {
    'revision': a.revision,
    'image': a.image,
    'version_code': metadata['versionCode'],
    'version_name': metadata['versionName'],
    'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'apk_size_bytes': apk.stat().st_size,
    'signing_policy': 'Private v2/v3 release signer on API 28+ (minSdk 28, no debug lineage)',
    'contract_version': 2,
}
(a.bundle / 'release.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(json.dumps(manifest))
