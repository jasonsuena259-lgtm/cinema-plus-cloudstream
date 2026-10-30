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
