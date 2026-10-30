#!/usr/bin/env python3
"""Probe only public embed-page metadata for ArabSeed mirrors (no media downloads)."""
import re, json, urllib.request, urllib.parse, collections
from bs4 import BeautifulSoup
base="https://arabsed.cam"
paths=["/مسلسل-ingobernable-الموسم-الاول-الحلقة-10-مترجمة/watch/","/مسلسل-ingobernable-الموسم-الاول-الحلقة-10-مترجمة/download/"]
ua="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/125 Safari/537.36"
def get(url,ref=None):
 try:
  request=urllib.request.Request(url,headers={"User-Agent":ua,"Referer":ref or base+"/","Accept":"text/html,*/*"})
  with urllib.request.urlopen(request,timeout=9) as response:
   return response.status,response.geturl(),response.read(300000).decode("utf8","replace")
 except Exception as e:
  return type(e).__name__,"",""
for path in paths:
 status,url,html=get(base+urllib.parse.quote(path,safe="/"))
 soup=BeautifulSoup(html,"html.parser")
 print("MIRROR_PAGE",path.split("/")[-2:],status,"bytes",len(html))
 nodes=soup.select(".arabsed-watch-server[data-src], .arabsed-download-card[href],.arabsed-download-hero-link[href]")
 unique={}
 for element in nodes:
  href=element.get("data-src") or element.get("href")
  if not href:continue
  parsed=urllib.parse.urlparse(urllib.parse.urljoin(url or base,href))
  if parsed.hostname=="arabsed.cam" or not parsed.hostname: continue
  name=element.get_text(" ",strip=True)[:30] or parsed.hostname
  key=(name,parsed.hostname)
  unique.setdefault(key,[]).append(parsed.geturl())
 print("MIRROR_COUNTS",[(n,h,len(urls)) for (n,h),urls in unique.items()])
 for (name,host),urls in list(unique.items())[:18]:
  input_url=urls[0]
  parsed=urllib.parse.urlparse(input_url)
  attempts=[input_url]
  if parsed.path.startswith(("/d/","/f/")):attempts.append(input_url.replace(parsed.path,parsed.path.replace("/d/","/e/").replace("/f/","/e/")))
  for attempt in attempts:
   s,redirected,body=get(attempt,url)
   final_host=urllib.parse.urlparse(redirected).hostname if redirected else ""
   flags={word:bool(re.search(pattern,body,re.I)) for word,pattern in {
    "video":r"<video|<source|player\.src|sources\s*:",
    "hls":r"m3u8|application/vnd.apple",
    "mp4":r"mp4|video/mp4",
    "packed":r"eval\(function\(p,a,c,k,e",
    "md5":r"/pass_md5/",
    "wurl":r"wurl\s*=",
    "iframe":r"<iframe",
    "challenge":r"cf-chl|cloudflare|captcha|access denied"
   }.items()}
   print("MIRROR_HOST",name,host,"pathKind",parsed.path[:7],"attempt",urllib.parse.urlparse(attempt).path[:7],"status",s,"redirectHost",final_host,"length",len(body),"signals",json.dumps(flags))

# Inspect two more public result pages; no signed video URLs are logged.
for query in ["The Final Problem","Bleach"]:
 search=base+"/?s="+urllib.parse.quote_plus(query)
 status,search_url,body=get(search)
 soup=BeautifulSoup(body,"html.parser")
 cards=soup.select(".item__contents a.movie__block[href], .item__contents a[href]")
 seen=set()
 for a in cards:
  href=urllib.parse.urljoin(base,a.get("href",""))
  if href in seen:continue
  seen.add(href)
  if len(seen)>3:break
  status,link,html=get(href)
  watch=link.rstrip("/")+"/watch/"
  code,page_url,watch_html=get(watch)
  d=BeautifulSoup(watch_html,"html.parser")
  nodes=d.select(".arabsed-watch-server[data-src]")
  hosts=collections.Counter((urllib.parse.urlparse(n.get("data-src","")).hostname or "-") for n in nodes)
  print("MIRROR_DISCOVERY",query,urllib.parse.urlparse(href).path[:80],code,sorted(hosts.items()))
# Dood metadata health probe: never print token, media prefix or final signed URL.
from urllib.parse import urlparse,urljoin
status,doc_url,html=get(base+urllib.parse.quote(paths[0],safe="/"))
s=BeautifulSoup(html,"html.parser")
dood=s.select_one("button.arabsed-watch-server[data-src*='dood']")
if dood:
 status,final,body=get(dood["data-src"],doc_url)
 m=re.search(r"/pass_md5/[a-zA-Z0-9_/?=&%-]+",body)
 print("DOOD_EMBED_PROBE",status,"redirect",urlparse(final).hostname,"md5_present",bool(m))
 if m:
  api=urljoin(final,m.group())
  status,final_response,content=get(api,final)
  print("DOOD_MD5_PROBE",status,"bytes",len(content),"is_https_prefix",content.strip().startswith("https://"),"contains_query",("?" in content))
