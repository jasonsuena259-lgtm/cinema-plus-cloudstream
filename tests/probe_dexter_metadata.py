import json,requests
s=requests.Session()
s.headers['User-Agent']='Mozilla/5.0 (Android 13; Mobile) AppleWebKit/537.36 Chrome/122 Safari/537.36'
targets={
 'cinemeta_movies':'https://v3-cinemeta.strem.io/catalog/movie/top.json',
 'cinemeta_tv':'https://v3-cinemeta.strem.io/catalog/series/top.json',
 'cinemeta_search':'https://v3-cinemeta.strem.io/catalog/movie/top/search=spider-man.json',
 'cinemeta_details':'https://v3-cinemeta.strem.io/meta/movie/tt6320628.json',
 'wikidata':'https://query.wikidata.org/sparql?query=SELECT%20%3Fitem%20%3Ftmdb%20WHERE%20%7B%20%3Fitem%20wdt%3AP345%20%22tt6320628%22%3B%20wdt%3AP4947%20%3Ftmdb.%20%7D%20LIMIT%201&format=json',
 'tmdb_images':'https://www.themoviedb.org/movie/429617-spider-man-far-from-home',
 'kinocheck':'https://api.kinocheck.com/movies?tmdb_id=429617'
}
for name,url in targets.items():
 try:
  r=s.get(url,timeout=12,headers={'Accept':'application/json'})
  out={'name':name,'http':r.status_code,'mime':r.headers.get('content-type','').split(';')[0]}
  if 'json' in r.headers.get('content-type','') and r.status_code==200:
   d=r.json()
   out['keys']=list(d)[:25] if isinstance(d,dict) else []
   metas=d.get('metas',[]) if isinstance(d,dict) else []
   out['count']=len(metas) if isinstance(metas,list) else 0
   out['sample_keys']=list(metas[0]) if metas else []
   md=d.get('meta',{}) if isinstance(d,dict) else {}
   out['meta_keys']=list(md)[:30] if isinstance(md,dict) else []
   out['query_results']=len(d.get('results',{}).get('bindings',[])) if 'query.wikidata.org' in url and isinstance(d,dict) else 0
  print('DEXTER_METADATA_PROBE',json.dumps(out))
 except Exception as e: print('DEXTER_METADATA_PROBE',json.dumps({'name':name,'error':type(e).__name__}))
