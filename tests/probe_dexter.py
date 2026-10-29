import collections, json, re, urllib.parse
import requests
from bs4 import BeautifulSoup

ROOT="https://dexter.pw"
sample="/watch/429617-spider-man-far-from-home"
session=requests.Session()
session.headers.update({"User-Agent":"Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122 Safari/537.36"})
def path(v,base=ROOT):
    return urllib.parse.urlparse(urllib.parse.urljoin(base,v or "")).path
def report(name,data):
    print("DEXTER_REPORT "+json.dumps({"page":name,**data},ensure_ascii=False))
for name, route in [("home","/"),("movies","/movies"),("tv","/tv"),
                    ("movie",sample),("show","/show/1405-dexter"),
                    ("episode","/watch/show/1405-dexter/1/1"),
                    ("search","/search?q=spider")]:
    try:
        r=session.get(ROOT+route,timeout=15)
        soup=BeautifulSoup(r.text[:1000000],"html.parser")
        anchors=soup.select("a[href]")
        paths=[path(a.get("href"),r.url) for a in anchors]
        counts=collections.Counter(p.split("/")[1] for p in paths if p.startswith("/") and len(p.split("/"))>1)
        samples=[{"path":p,"text":a.get_text(" ",strip=True)[:65],
                  "class":a.get("class",[]),"parent":a.parent.get("class",[]) if a.parent else [],
                  "hasImg":bool(a.select_one("img"))} for a,p in zip(anchors,paths)
                 if re.match(r"^/(watch|show|movie|tv|movies)/",p)][:16]
        inputs=[{"name":x.get("name"),"type":x.get("type")} for x in soup.select("input[name]")[:12]]
        sections=[{"tag":x.name,"class":x.get("class",[]),"count":len(x.select("a[href]"))}
                  for x in soup.select("main,section,article")[:10]]
        media=[{"tag":x.name,"host":urllib.parse.urlparse(urllib.parse.urljoin(r.url,x.get("src",""))).hostname}
               for x in soup.select("iframe[src],video[src],source[src]")[:10]]
        report(name,{"http":r.status_code,"size":len(r.content),
          "title":soup.title.get_text(" ",strip=True)[:90] if soup.title else "",
          "h1":[x.get_text(" ",strip=True)[:80] for x in soup.select("h1")[:2]],
          "groups":dict(counts.most_common(12)),"linkSamples":samples,
          "inputSamples":inputs,"sections":sections,"media":media,
          "externalScripts":len(soup.select("script[src]")),
          "inlinePlayerReferences":len(re.findall(r"(?i)m3u8|neonvase|stream|iframe|source|server|subtitle",r.text))})
    except Exception as e:report(name,{"error":type(e).__name__,"detail":str(e)[:110]})

# Inspect only the first bytes of the user-supplied HLS playlist; never download film segments.
stream="https://v.neonvase.top/v/1d013cc4175c81a1a47680e6/1920x1080/index.m3u8"
try:
    with session.get(stream,timeout=12,stream=True,
                     headers={"Range":"bytes=0-2047","Referer":ROOT+sample}) as r:
        body=next(r.iter_content(chunk_size=2048),b"")
        tags=[line.split(":",1)[0] for line in body.decode("utf8","replace").splitlines() if line.startswith("#EXT")]
        report("userHls",{"http":r.status_code,"mime":r.headers.get("Content-Type"),
             "isHls":body.startswith(b"#EXTM3U"),"tags":dict(collections.Counter(tags)),
             "streamUrlsRedacted":True})
except Exception as e:report("userHls",{"error":type(e).__name__,"detail":str(e)[:110]})
