#!/usr/bin/env python3
"""Structural probe of public ArabSeed pages. Never log full third-party URLs or cookies."""
from urllib.request import Request, urlopen
from urllib.parse import urlsplit, quote
from bs4 import BeautifulSoup
from collections import Counter
import json
base="https://arabsed.cam"
episode="/مسلسل-ingobernable-الموسم-الاول-الحلقة-10-مترجمة/"
season="/series/مسلسل-ingobernable-ingobernable-الموسم-الاول/"
paths=[("home","/main/"),("recent","/recently/"),("movies","/movies/"),("series","/series/"),("category","/category/مسلسلات-اجنبية/"),("episode",episode),("season",season),("watch",episode+"watch/"),("download",episode+"download/"),("search","/?s=ingobernable")]
def clean(url):
 if not url:return ""
 if url.startswith(("https://","http://","//")):
  u=urlsplit(url if not url.startswith("//") else "https:"+url)
  return (u.path[:125] if u.hostname=="arabsed.cam" else (u.hostname or "external")+"/[REDACTED]")
 return url[:125]
def info(e):
 if not e:return {}
 return {"tag":e.name,"class":" ".join(e.get("class",[]))[:100],"text":e.get_text(" ",strip=True)[:85],
         "href":clean(e.get("href","")),"src":clean(e.get("src","")),"data-src":clean(e.get("data-src","")),
         "data-url":clean(e.get("data-url","")),"data-embed":bool(e.get("data-embed")),
         "id":e.get("id","")[:80]}
for label,path in paths:
 try:
  req=Request(base+quote(path,safe="/?=&%"),headers={"User-Agent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/125 Safari/537.36"})
  with urlopen(req,timeout=18) as r: html=r.read(1400000).decode("utf-8","replace");status=r.status
  soup=BeautifulSoup(html,"html.parser")
  print("ARAB_PAGE",label,status,len(html),"h1",[(x.get_text(" ",strip=True))[:90] for x in soup.select("h1")[:2]])
  classes=Counter(" ".join(x.get("class",[])) for x in soup.find_all(["article","section","div","li"]) if x.get("class"))
  print("ARAB_CLASSES",label,json.dumps(classes.most_common(28),ensure_ascii=False))
  sel={"home":[".item__contents",".series__box","a[href*=category]","form"],
       "recent":[".item__contents","a[href*='/page/']"],
       "movies":[".item__contents"],
       "series":[".series__box","a[href*='/series/']"],
       "category":[".item__contents",".series__box"],
       "episode":["a[href*='/series/']","a[href*='/watch/']","a[href*='/download/']","a[href*='الحلقة']"],
       "season":["a[href*='الحلقة']","a[href*='/series/']"],
       "watch":["button","iframe","[data-src]","[data-embed]","a[href]"],
       "download":["a[href]"],"search":[".item__contents"]}[label]
  for selector in sel:
   matches=soup.select(selector)
   print("ARAB_SELECT",label,selector,len(matches))
   for e in matches[:5]:
    print("ARAB_NODE",label,selector,json.dumps({"node":info(e),"parent":info(e.parent),"grand":info(e.parent.parent if e.parent else None),"image":info(e.select_one("img"))},ensure_ascii=False))
  if label in ("home","recent","movies","series","category","search"):
   for e in soup.select(".item__contents, .series__box")[:2]:
    print("ARAB_CARD_DETAIL",label,json.dumps({"anchors":[info(a) for a in e.select("a[href]")[:5]],"headings":[info(a) for a in e.select("h2,h3,h4,.post__info,.title___")[:6]]},ensure_ascii=False))
  if label in ("episode","season"):
   for key in (".epi__num",".episodes__ul",".season__list",".seasons__list",".seasons__container",".episodes__container",".watch__and__download","h1",".post__image",".single__poster"):
    for e in soup.select(key)[:2]:
     print("ARAB_EP_DETAIL",label,key,json.dumps({"node":info(e),"parent":info(e.parent),"ancestors":[info(a) for a in list(e.parents)[:4]]},ensure_ascii=False))
  if label=="watch":
   for e in soup.find_all(["button","li","a","iframe"]):
    cls=" ".join(e.get("class",[])).lower()
    if any(k in cls for k in ["server","player","watch","embed"]):print("ARAB_SERVER",json.dumps(info(e),ensure_ascii=False))
  if label=="download":
   for e in soup.select("a[href]"):
    u=urlsplit(e.get("href",""))
    if u.hostname and u.hostname!="arabsed.cam":print("ARAB_DL",json.dumps({"node":info(e),"parent":info(e.parent)},ensure_ascii=False))
  if label in ("episode","season"):
   for e in soup.select("a[href]"):
    if "الحلقة" in e.get_text(" ",strip=True) or "/series/" in e.get("href",""):
     print("ARAB_EP",json.dumps({"node":info(e),"parent":info(e.parent)},ensure_ascii=False))
 except Exception as e:print("ARAB_ERROR",label,type(e).__name__,str(e)[:140])
