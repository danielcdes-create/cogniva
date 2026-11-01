"""Read-only deployment inventory. Never print credentials or connection strings."""
import json, pathlib, subprocess
core=json.loads(subprocess.check_output(['docker','inspect','api-rust-core']))[0]
env=dict(item.split('=',1) for item in core['Config']['Env'] if '=' in item)
print(json.dumps({'image':core['Config']['Image'],'image_id':core['Image'],
 'secret_lengths':{key:len(env.get(key,'')) for key in ['JWT_SECRET','MONGODB_URI']},
 'database':env.get('MONGODB_DB','CNU'),'apk_paths':{key:env.get(key) for key in ['APK_STORAGE_PATH','APK_METADATA_PATH']},
 'mounts':core['Mounts'],'ports':core['NetworkSettings']['Ports']},indent=2))
for row in subprocess.check_output(['docker','ps','--format','{{json .}}'],text=True).splitlines():
    item=json.loads(row)
    print(json.dumps({key:item.get(key) for key in ['Names','Ports','Status']}))
