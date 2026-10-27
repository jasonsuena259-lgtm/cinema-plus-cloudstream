#!/usr/bin/env python3
"""Detailed independent HLS probes for actual site films and episodes."""
import concurrent.futures, json, re, sys, urllib.parse
from pathlib import Path
from html.parser import HTMLParser
from probe_sources import inspect
root=Path(sys.argv[1]); samples=[]
for show in ['111110','106379','92749','246621','60059']:
 text=(root/'series'/f'{show}.html').read_text();body=re.search(r'const\s+episodeLinks\s*=\s*\{([\s\S]*?)\}',text).group(1)
 entries=re.findall(r'''["'](\d+-\d+)["']\s*:\s*["']([A-Za-z0-9]+)["']''',body)
 for key,mid in [entries[0], entries[-1]]:
  if (f'series/{show}/{key}',mid) not in samples:samples.append((f'series/{show}/{key}',mid))
class Cards(HTMLParser):
 def __init__(self): super().__init__();self.movies=[]
 def handle_starttag(self,t,a):
  href=dict(a).get('href','');u=urllib.parse.urlparse(href);q=urllib.parse.parse_qs(u.query)
  if t=='a' and u.path=='movie.html' and q.get('tmdb') and q.get('mux'):
   self.movies.append((q['tmdb'][0],q['mux'][0]))
p=Cards();p.feed((root/'discover.html').read_text());movies=list(dict.fromkeys(p.movies))
for i in (0,40,120,250,400):
 movie,mux=movies[i];samples.append((f'movie/{movie}',mux))
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
 for result in executor.map(lambda pair:inspect(*pair),samples): print(json.dumps(result,ensure_ascii=False),flush=True)
