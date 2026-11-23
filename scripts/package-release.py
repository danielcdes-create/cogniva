"""Create a measured release manifest after APK signing."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib


def package_release(bundle: pathlib.Path, *, image: str, revision: str) -> dict:
    apk = bundle / 'cogniva.apk'
    metadata = json.loads((bundle / 'output-metadata.json').read_text(encoding='utf-8-sig'))['elements'][0]
    assert apk.is_file() and apk.stat().st_size > 0

    manifest = {
        'revision': revision,
        'image': image,
        'version_code': metadata['versionCode'],
        'version_name': metadata['versionName'],
        'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'apk_size_bytes': apk.stat().st_size,
        'signing_policy': 'Private v2/v3 release signer on API 28+ (minSdk 28, no debug lineage)',
        'contract_version': 5,
    }
    (bundle / 'release.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=pathlib.Path)
    parser.add_argument('--image', required=True)
    parser.add_argument('--revision', required=True)
    args = parser.parse_args(argv)
    manifest = package_release(args.bundle, image=args.image, revision=args.revision)
    print(json.dumps(manifest))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
