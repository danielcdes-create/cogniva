"""Keep five recent Cogniva image versions plus the current/rollback digests.
Uses the job token; never logs it. Run only after successful promotion.
"""
import json,os,sys,urllib.request
base='https://api.github.com/users/danielcdes-create/packages/container/api-rust-core/versions'
headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'}
current=json.load(open(sys.argv[1],encoding='utf-8'))
protected={value.split('@',1)[-1] for value in [current.get('image',''),current.get('previous_image','')]}
def request(url,method='GET'):
    with urllib.request.urlopen(urllib.request.Request(url,headers=headers,method=method),timeout=30) as response:
        return json.loads(response.read()) if method=='GET' else None
versions=request(base+'?per_page=100')
versions.sort(key=lambda v:v['created_at'],reverse=True)
deleted=[]
for version in versions[5:]:
    tags=version.get('metadata',{}).get('container',{}).get('tags',[])
    if version['name'] in protected or 'latest' in tags:continue
    request(base+'/'+str(version['id']),method='DELETE');deleted.append(version['id'])
print(json.dumps({'retained_recent':5,'protected_current_and_rollback':True,'deleted_old_versions':deleted}))
