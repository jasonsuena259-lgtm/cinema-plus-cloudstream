#!/usr/bin/env python3
"""Validate MySeed HLS and progressive MP4 samples. Signed captures must remain private."""
import argparse,json,pathlib,urllib.request,urllib.parse,subprocess,hashlib,re
p=argparse.ArgumentParser();p.add_argument('capture',type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);p.add_argument('--samples',required=True,type=pathlib.Path);a=p.parse_args();a.samples.mkdir(parents=True,exist_ok=True)
results=[]
for host in json.loads(a.capture.read_text()):
 row={'source':host['name'],'page_http':host.get('status'),'browser_requests':len(host['found']),'hls_validation':'NOT_TESTED','sample_download':'NOT_TESTED','sample_decode':'NOT_TESTED','android_playback':'NOT_TESTED'}
 for candidate in host['found'][:2]:
  try:
   headers={k:v for k,v in candidate['headers'].items() if k.lower() not in ('host','content-length','accept-encoding','range')}
   def get(url,limit=12000000):
    with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=20) as r:return r.status,r.read(limit)
   url=candidate['url']
   if candidate.get('type')=='VIDEO':
    target=a.samples/(host['name']+'.mp4');header_string=''.join(k+': '+v+'\r\n' for k,v in headers.items())
    cmd=['ffmpeg','-v','error','-rw_timeout','15000000','-headers',header_string,'-i',url,'-t','3','-c','copy','-y',str(target)]
    r=subprocess.run(cmd,capture_output=True,timeout=60);assert r.returncode==0
    r=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_type,codec_name,width,height','-of','json',str(target)],capture_output=True,text=True,timeout=15);assert r.returncode==0
    row['probe']=json.loads(r.stdout);assert any(x.get('codec_type')=='video' for x in row['probe'].get('streams',[]))
    row['hls_validation']='UNSUPPORTED';row['mp4_validation']='PASS';row['sample_download']='PASS'
    r=subprocess.run(['ffmpeg','-v','error','-i',str(target),'-t','2','-f','null','-'],capture_output=True,timeout=20);row['sample_decode']='PASS' if r.returncode==0 else 'FAIL';break
   status,raw=get(url,2000000);manifest=raw.decode();assert manifest.lstrip().startswith('#EXTM3U')
   for _ in range(3):
    if '#EXT-X-STREAM-INF:' not in manifest:break
    line=next(x.strip() for x in manifest.splitlines() if x.strip() and not x.startswith('#'));url=urllib.parse.urljoin(url,line);status,raw=get(url,2000000);manifest=raw.decode();assert manifest.lstrip().startswith('#EXTM3U')
   assert '#EXTINF:' in manifest
   line=next(x.strip() for x in manifest.splitlines() if x.strip() and not x.startswith('#'));status,data=get(urllib.parse.urljoin(url,line));row['segment_http']=status;row['segment_bytes']=len(data)
   if status==206 and len(data)<=1024:
    row['first_segment_note']='Unexpected cached partial response (1024 bytes); sample uses second segment'
    segments=[x.strip() for x in manifest.splitlines() if x.strip() and not x.startswith('#')]
    status,data=get(urllib.parse.urljoin(url,segments[1]));row['segment_http']=status;row['segment_bytes']=len(data);row['sample_segment']=2
   target=a.samples/(host['name']+'.ts');target.write_bytes(data)
   keyline=next((x for x in manifest.splitlines() if x.startswith('#EXT-X-KEY:') and 'METHOD=NONE' not in x),None)
   if keyline:
    assert 'METHOD=AES-128' in keyline and 'KEYFORMAT=' not in keyline, 'DRM unsupported'
    keyurl=re.search(r'URI="([^"]+)"',keyline).group(1);_,key=get(urllib.parse.urljoin(url,keyurl));assert len(key)==16
    ivmatch=re.search(r'IV=0x([0-9a-fA-F]+)',keyline);seq=re.search(r'#EXT-X-MEDIA-SEQUENCE:(\d+)',manifest);iv=ivmatch.group(1) if ivmatch else format(int(seq.group(1)) if seq else 0,'032x')
    decoded=subprocess.run(['openssl','enc','-d','-aes-128-cbc','-K',key.hex(),'-iv',iv],input=data,capture_output=True,timeout=15);assert decoded.returncode==0;data=decoded.stdout;target.write_bytes(data);row['encryption']='Standard HLS AES-128, key accessible with normal request'
   mapline=next((x for x in manifest.splitlines() if x.startswith('#EXT-X-MAP:')),None)
   if mapline:
    initurl=re.search(r'URI="([^"]+)"',mapline).group(1);_,init=get(urllib.parse.urljoin(url,initurl));target.write_bytes(init+data)
   fmt=['-f','mpegts'] if any(all(data[i+j*188]==71 for j in range(5)) for i in range(max(0,min(188,len(data)-752)))) else []
   r=subprocess.run(['ffprobe','-v','error']+fmt+['-show_entries','stream=codec_name,codec_type,width,height:format=duration','-of','json',str(target)],capture_output=True,text=True,timeout=25);assert r.returncode==0
   row['probe']=json.loads(r.stdout);assert any(x.get('codec_type')=='video' and x.get('codec_name') not in ('png','mjpeg','gif','webp') for x in row['probe'].get('streams',[]));row['hls_validation']='PASS';row['sample_download']='PASS';row['sha256']=hashlib.sha256(data).hexdigest()
   r=subprocess.run(['ffmpeg','-v','error']+fmt+['-i',str(target),'-t','2','-f','null','-'],capture_output=True,timeout=35);row['sample_decode']='PASS' if r.returncode==0 else 'FAIL';break
  except Exception as e:row['hls_validation']='FAIL';row['error']=type(e).__name__
 results.append(row)
a.output.write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n');print(json.dumps([{k:v for k,v in r.items() if k not in ('probe','sha256')} for r in results],ensure_ascii=False,indent=2))
