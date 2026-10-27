#!/usr/bin/env python3
"""Opt-in network check; downloads only HLS initialization and selected short segments."""
import json, re, subprocess, tempfile, urllib.request, urllib.parse, urllib.error
from pathlib import Path

SAMPLES = {
    'film_discover_1428857': 'yKiVRUq56B28fGUsEzA1SN6005ojqmgN2ZVGBMEB59P8',
    'film_slider_969681': 'kyinHlfWm200OCgMN65X2tkPJ7AUWEeZNtDyQB7Q4bFo',
    'series_106379_s01e01': '46lLvi5wmmVRBn3ad6j02XruD02e00O9pBd45F01Cr600niM',
    'series_106379_s02e01': 'fDbhWeBwcFa8uyh7iKL601Fz01bbflm6La02IcROlobtGQ',
    'json_avatar': 'GskIrti8484',
}
HEADERS = {'Referer':'https://ffrrx-2000.github.io/cinema-plas-bot/', 'User-Agent':'CinemaPlus-source-check/1.0'}
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers=HEADERS),timeout=25) as response:
        return response.status, response.read()
def inspect(name, id):
    result = {'sample':name, 'extraction':'PASS', 'application_playback':'NOT_TESTED', 'application_download':'NOT_TESTED'}
    try:
        status, raw = get('https://stream.mux.com/'+id+'.m3u8'); master=raw.decode()
        result['http'] = status
        if not master.lstrip().startswith('#EXTM3U'): raise ValueError('invalid HLS')
        result['resolutions'] = re.findall(r'RESOLUTION=(\d+x\d+)',master)
        result['audio_tracks'] = len(re.findall(r'#EXT-X-MEDIA:TYPE=AUDIO',master))
        result['subtitles'] = len(re.findall(r'#EXT-X-MEDIA:TYPE=SUBTITLES',master))
        uris=[x.strip() for x in master.splitlines() if x.strip() and not x.startswith('#')]
        variant = urllib.parse.urljoin('https://stream.mux.com/'+id+'.m3u8',uris[0]) if '#EXT-X-STREAM-INF:' in master else None
        playlist = get(variant)[1].decode() if variant else master
        if not playlist.lstrip().startswith('#EXTM3U') or '#EXTINF:' not in playlist: raise ValueError('invalid media playlist')
        lines=[x.strip() for x in playlist.splitlines() if x.strip() and not x.startswith('#')]
        init=re.search(r'#EXT-X-MAP:.*?URI="([^"]+)"',playlist)
        root=variant or 'https://stream.mux.com/'+id+'.m3u8'
        first=get(urllib.parse.urljoin(root,lines[0]))[1]
        later=get(urllib.parse.urljoin(root,lines[min(len(lines)-1,10)]))[1]
        header=get(urllib.parse.urljoin(root,init.group(1)))[1] if init else b''
        result['manifest']='PASS'; result['segments']='PASS'; result['first_segment_bytes']=len(first); result['later_segment_bytes']=len(later)
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)/'sample.bin'; out.write_bytes(header+first)
            proc=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_name,width,height','-of','json',str(out)],capture_output=True,text=True,timeout=20)
            result['probe']=json.loads(proc.stdout).get('streams',[]) if proc.returncode==0 else []
            result['video_metadata']='PASS' if result['probe'] else 'FAIL'
            result['partial_download']='PASS' if len(first)>0 and result['probe'] else 'FAIL'
    except Exception as e:
        result['network_error']=str(e); result.setdefault('manifest','FAIL'); result.setdefault('segments','NOT_TESTED'); result.setdefault('video_metadata','NOT_TESTED'); result.setdefault('partial_download','NOT_TESTED')
    return result
if __name__ == '__main__':
    for name, id in SAMPLES.items(): print(json.dumps(inspect(name,id),ensure_ascii=False),flush=True)
