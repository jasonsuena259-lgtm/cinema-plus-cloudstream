import requests,json,urllib.parse
tests={
 'dexter_vdrk':'https://sub.vdrk.site/v1/tv/259909/1/1',
 'movie_vdrk':'https://sub.vdrk.site/v1/movie/429617',
 'resurrection_vdrk':'https://sub.vdrk.site/v1/tv/259909/1/1',
 'spiderman_vdrk':'https://sub.vdrk.site/v1/movie/429617'
}
for name,url in tests.items():
 try:
  r=requests.get(url,timeout=12,headers={'Accept':'application/json','User-Agent':'Mozilla/5.0'})
  obj={'name':name,'status':r.status_code,'mime':r.headers.get('content-type','').split(';')[0]}
  if r.ok and 'json' in r.headers.get('content-type',''):
   data=r.json()
   obj['count']=len(data) if isinstance(data,list) else 0
   obj['sample_keys']=list(data[0]) if isinstance(data,list) and data and isinstance(data[0],dict) else []
   obj['languages']=[str(x.get('label') or x.get('language') or '')[:25] for x in data[:16]] if isinstance(data,list) else []
  if r.ok and isinstance(data,list):
   import urllib.parse,collections
   sample=[x for x in data if 'arabic' in str(x.get('label','')).lower()][:5]
   obj['arabic_link_properties']=[{'host':urllib.parse.urlparse(str(x.get('file',''))).hostname,'ext':urllib.parse.urlparse(str(x.get('file',''))).path.rsplit('.',1)[-1][:12],'https':str(x.get('file','')).startswith('https://')} for x in sample]
  print('DEXTER_SUBTITLE_PROBE',json.dumps(obj,ensure_ascii=False))
 except Exception as e:print('DEXTER_SUBTITLE_PROBE',json.dumps({'name':name,'error':type(e).__name__}))
