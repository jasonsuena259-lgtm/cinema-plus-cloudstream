#!/usr/bin/env python3
"""Probe limited media samples exported by opt-in Kotlin integration tests.
Input contains temporary URLs and must stay outside the repository. Output does not.
"""
import json,urllib.request,urllib.parse,re,sys,tempfile,subprocess
from pathlib import Path
sources=json.loads(Path(sys.argv[1]).read_text());seen=set()
for item in sources:
 if item['name'] in seen:continue
 seen.add(item['name']);out={'host':item['name'],'app_playback':'NOT_TESTED','app_download':'NOT_TESTED'}
 headers={**item['headers'],'Referer':item['referer']}
 def get(url,limit=2000000,byte_range=False):
  h={**headers}
  if byte_range:h['Range']=f'bytes=0-{limit-1}'
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=20) as r:return r.url,r.read(limit)
 try:
  if item['type']=='M3U8':
   root,raw=get(item['url']);text=raw.decode();assert text.lstrip().startswith('#EXTM3U')
   if '#EXT-X-STREAM-INF:' in text:
    lines=text.splitlines();i=next(i for i,x in enumerate(lines) if x.startswith('#EXT-X-STREAM-INF:'))
    path=next(x for x in lines[i+1:] if x.strip() and not x.startswith('#'));root,raw=get(urllib.parse.urljoin(root,path));text=raw.decode()
   assert '#EXTINF:' in text
   out['manifest']='PASS'
   path=next(x for x in text.splitlines() if x.strip() and not x.startswith('#'))
   init=re.search(r'#EXT-X-MAP:.*URI="([^"]+)"',text)
   data=get(urllib.parse.urljoin(root,init.group(1)))[1] if init else b''
   data+=get(urllib.parse.urljoin(root,path),8000000)[1]
  else:data=get(item['url'],2000000,True)[1]
  assert data;out['partial_download']='PASS';out['bytes']=len(data)
  with tempfile.TemporaryDirectory() as tmp:
   f=Path(tmp)/'sample.bin';f.write_bytes(data)
   p=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_name,width,height','-of','json',str(f)],capture_output=True,text=True,timeout=20)
   tracks=json.loads(p.stdout or '{}').get('streams',[])
   out['metadata']='PASS' if tracks else 'NOT_TESTED';out['tracks']=tracks
 except Exception as e:out['error']=type(e).__name__;out['partial_download']=out.get('partial_download','FAIL')
 print(json.dumps(out),flush=True)
