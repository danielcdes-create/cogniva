"""Move a staged bundle into its immutable source-revision directory, without clobbering."""
import hashlib,json,pathlib,re,sys,os
source=pathlib.Path(__file__).resolve().parent
assert source.parent==pathlib.Path('/opt/apps/cogniva-incoming')
revision=json.loads((source/'release.json').read_text())['revision']
assert re.fullmatch('[0-9a-f]{40}',revision)
target=pathlib.Path('/opt/apps/cogniva-releases')/revision
target.parent.mkdir(parents=True,exist_ok=True)
if target.exists():
    for path in source.iterdir():
        assert path.is_file()
        existing=target/path.name
        assert existing.is_file() and hashlib.sha256(path.read_bytes()).digest()==hashlib.sha256(existing.read_bytes()).digest(),'Immutable bundle already exists with different bytes'
    for path in source.iterdir():path.unlink()
    source.rmdir()
else:os.rename(source,target)
print('IMMUTABLE_BUNDLE_STAGED',revision)
