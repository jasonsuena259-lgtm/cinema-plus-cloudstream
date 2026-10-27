#!/usr/bin/env python3
"""Opt-in live check of normal AkWam watch resolution and Lulu HLS; no challenges solved."""
import http.cookiejar,json,re,sys,urllib.parse,urllib.request
from html.parser import HTMLParser
from lxml import html
base='https://akwam.day'
under_paris = '--under-paris' in sys.argv
slug = 'فيلم-under-paris-2024-مترجم' if under_paris else 'فيلم-star-one-2026-مترجم'
host_name = 'fastved.cam' if under_paris else 'luluvido.com'
button_name = 'fsdcmo' if under_paris else 'luluvido'
watch=base+'/watch/'+urllib.parse.quote(slug)
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
headers={'User-Agent':'Mozilla/5.0','Referer':watch}
def get(url, extra=None):
 with opener.open(urllib.request.Request(url,headers={**headers,**(extra or {})}),timeout=20) as r:return r.url,r.read()
_,raw=get(watch); doc=html.fromstring(raw); text=raw.decode(); params={name:re.search(r'const\s+'+name+r'\s*=\s*"([^"]+)"',text).group(1) for name in ('watchResolveKey','csrfToken')}
button=next(x for x in doc.xpath('//button[@data-token]') if button_name in x.text_content().lower())
body=json.dumps({'key':params['watchResolveKey'],'token':button.get('data-token'),'human_signal':None}).encode()
h={**headers,'Accept':'application/json','Content-Type':'application/json','X-CSRF-TOKEN':params['csrfToken'],'X-Requested-With':'XMLHttpRequest'}
with opener.open(urllib.request.Request(base+'/watch/resolve',data=body,headers=h),timeout=20) as r:resolved=json.load(r)
url,host=get(urllib.parse.urljoin(base,resolved['url']));page=host.decode(errors='replace')
if re.search('recaptcha|hcaptcha|cf-turnstile',page,re.I):raise SystemExit('BLOCKED: host requested challenge')
script=re.search(r'<script[^>]*>(eval\(function\(p,a,c,k,e,d\).*?)</script>',page,re.S).group(1)
m=re.search(r"\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)",script,re.S)
payload,radix,count,symbols=m.groups(); radix=int(radix); symbols=symbols.split('|');alphabet='0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
def decode(match):
 word=match.group()
 try:
  n=0
  for c in word:n=n*radix+alphabet.index(c)
  return symbols[n] if n<len(symbols) and symbols[n] else word
 except (ValueError,IndexError):return word
unpacked=re.sub(r'\b[a-zA-Z0-9_]+\b',decode,payload.replace("\\'", "'"))
urls=re.findall(r'''(?:file|src)\s*:\s*["'](https?://[^"']+)["']''',unpacked)
hls = [x for x in urls if '.m3u8' in x]
if not hls: hls = re.findall(r'''[:=]\s*["'](https?://[^"'\s]+\.m3u8[^"'\s]*)''',unpacked)
url=hls[0];referer='https://'+host_name+'/'
root,manifest=get(url,{'Referer':referer});manifest=manifest.decode();assert manifest.lstrip().startswith('#EXTM3U')
lines=[x.strip() for x in manifest.splitlines() if x.strip() and not x.startswith('#')];assert lines
if '#EXT-X-STREAM-INF:' in manifest:
 root,manifest=get(urllib.parse.urljoin(root,lines[0]),{'Referer':referer});manifest=manifest.decode();lines=[x.strip() for x in manifest.splitlines() if x.strip() and not x.startswith('#')]
assert '#EXTINF:' in manifest and lines
_,segment=get(urllib.parse.urljoin(root,lines[0]),{'Referer':referer,'Range':'bytes=0-1023'});assert segment
print(json.dumps({'item':'Under Paris' if under_paris else 'Star One','host':host_name,'resolve':'PASS','manifest':'PASS','segment':'PASS','partial_bytes':len(segment),'cloudstream':'NOT_TESTED'}))
