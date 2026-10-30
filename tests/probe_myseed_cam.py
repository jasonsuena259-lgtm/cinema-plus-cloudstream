#!/usr/bin/env python3
"""Public markup probe for MySeed Cam. Never log full embed URLs or cookies."""
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, urlopen
import json, re
URLS = [
"https://myseed.cam/",
"https://myseed.cam/films",
"https://myseed.cam/seriestv",
"https://myseed.cam/category/%D8%A7%D9%81%D9%84%D8%A7%D9%85-%D8%A7%D8%AC%D9%86%D8%A8%D9%8A",
"https://myseed.cam/goodfellas/",
"https://myseed.cam/series/doctor-x-mafia-in-white",
"https://myseed.cam/doctor-x-mafia-in-white-n1/",
"https://myseed.cam/watch/goodfellas/servers",
"https://myseed.cam/download/goodfellas",
]
class P(HTMLParser):
 def __init__(self):
  super().__init__();self.els=[]; self.current=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag in ('a','iframe','button','source','video','input','form','img','script'):
   interesting=tag in ('iframe','source','video','button') or any(k in ('data-src','data-url','data-embed','data-link','data-player','data-server','data-id','data-video') for k in a) or (tag=='a' and ('watch/' in a.get('href','') or 'series/' in a.get('href','') or 'download/' in a.get('href',''))) or (tag=='script' and 'src' not in a)
   if interesting:
    d={'tag':tag, 'class':a.get('class','')[:120],'id':a.get('id','')[:80], 'type':a.get('type',''), 'attr':{}}
    for k,v in a.items():
     if k in ('href','src','data-src','data-url','data-embed','data-link','data-player','data-server','data-id','data-video','onclick','action','name','value'):
      if k in ('href','src','data-src','data-url','data-embed','data-link','data-player','onclick','action'):
       d['attr'][k]={'length':len(v),'host':urlsplit(v).hostname,'prefix':v[:22] if v.startswith('/') else 'url'}
      else:d['attr'][k]=str(v)[:80]
    self.els.append(d)
for u in URLS:
 try:
  req=Request(u,headers={'User-Agent':'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/125.0 Safari/537.36','Accept-Language':'ar,en;q=0.8'})
  with urlopen(req,timeout=17) as resp: raw=resp.read(1000000).decode('utf-8','replace');status=resp.status
  parser=P();parser.feed(raw)
  signals={x:len(re.findall(x,raw,re.I)) for x in ('iframe','data-embed','data-url','data-link','server','player','base64','m3u8','watch/','series/')}
  print('MYSEED_PROBE',json.dumps({'url':u,'status':status,'length':len(raw),'title':re.findall(r'<title[^>]*>(.*?)</title>',raw,re.I|re.S)[:1], 'elements':parser.els[:65], 'script_signals':signals},ensure_ascii=False))

  for pattern in ('tt-card-link','tt-watch-server','tt-download','myseed-episode','myseed-season','season-episode','tt-pagination','<form','<article','tt-card','<h1'):
   match=re.search(re.escape(pattern),raw,re.I)
   if match:
    excerpt=raw[max(0,match.start()-270):min(len(raw),match.start()+680)]
    excerpt=re.sub(r'https?://[^"\\s<>]+',lambda m:'[URL_HOST:'+str(urlsplit(m.group()).hostname)+']',excerpt)
    excerpt=re.sub(r'\\s+',' ',excerpt)
    print('MYSEED_STRUCTURE',u,pattern,excerpt[:800])
 except Exception as e: print('MYSEED_PROBE_ERROR',u,type(e).__name__,str(e)[:150])
