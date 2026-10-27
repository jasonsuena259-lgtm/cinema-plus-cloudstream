#!/usr/bin/env python3
"""Network audit: one real Mux master per series and a spread of film cards.
A valid master alone is not proof of playback or downloading.
Pass the cloned source repository as the first argument.
"""
import concurrent.futures, json, re, sys, urllib.request, urllib.parse
from pathlib import Path
from html.parser import HTMLParser

root=Path(sys.argv[1]); selected=[]
for p in sorted((root/'series').glob('*.html')):
 m=re.search(r'const\s+episodeLinks\s*=\s*\{([\s\S]*?)\}',p.read_text())
 if m:
  pair=re.search(r'''["'](\d+-\d+)["']\s*:\s*["']([A-Za-z0-9]+)["']''',m.group(1))
  if pair:selected.append((f'series/{p.stem}/{pair.group(1)}',pair.group(2)))
class Cards(HTMLParser):
 def __init__(self):super().__init__();self.movies=[]
 def handle_starttag(self,t,attrs):
  a=dict(attrs); h=a.get('href','');u=urllib.parse.urlparse(h);q=urllib.parse.parse_qs(u.query)
  if t=='a' and u.path=='movie.html' and q.get('tmdb') and q.get('mux') and re.fullmatch('[A-Za-z0-9]+',q['mux'][0]):self.movies.append((q['tmdb'][0],q['mux'][0]))
p=Cards();p.feed((root/'discover.html').read_text());movies=list(dict.fromkeys(p.movies))
for i in range(0,len(movies),max(1,len(movies)//30)):
 t,m=movies[i]
 selected.append((f'movie/{t}',m))
def check(v):
 name,mux=v;out={'item':name}
 try:
  request=urllib.request.Request(f'https://stream.mux.com/{mux}.m3u8',headers={'Referer':'https://ffrrx-2000.github.io/cinema-plas-bot/','User-Agent':'CinemaPlus-audit/1.0'})
  with urllib.request.urlopen(request,timeout=15) as res:
   s=res.read(600000).decode('utf8');out.update(http=res.status,manifest='PASS' if s.lstrip().startswith('#EXTM3U') and ('#EXT-X-STREAM-INF:' in s or '#EXTINF:' in s) else 'FAIL')
 except Exception as e:out.update(manifest='FAIL',error=str(e))
 return out
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
 for result in executor.map(check,selected):print(json.dumps(result,ensure_ascii=False),flush=True)
