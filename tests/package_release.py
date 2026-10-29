#!/usr/bin/env python3
"""Publish independently installable artifacts and validate their entry points."""
import hashlib, json, os, shutil, zipfile, re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
repo = os.environ.get('GITHUB_REPOSITORY', 'jasonsuena259-lgtm/cinema-plus-cloudstream')
branch = os.environ.get('PUBLISH_BRANCH', 'main')
out = root / ('dist' if branch == 'builds' else '.')
out.mkdir(exist_ok=True)
base = f'https://raw.githubusercontent.com/{repo}/{branch}'

def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')

entries = []
for module, entrypoint in [('CinemaPlus', 'com.cinemaplus.CinemaPlusPlugin'), ('Akwam', 'com.akwam.AkwamPlugin'), ('TukTukCinema', 'com.tuktuk.TukTukPlugin'), ('CineWave', 'com.cinewave.CineWavePlugin'), ('MySeed', 'com.myseed.MySeedPlugin'), ('CimaNowVIP', 'com.cimanowvip.CimaNowVIPPlugin')]:
    version = int(re.search(r'^version = (\d+)', (root / module / 'build.gradle.kts').read_text(), re.M).group(1))
    source = root / module / 'build' / f'{module}.cs3'
    with zipfile.ZipFile(source) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        assert manifest['version'] == version and manifest['pluginClassName'] == entrypoint, manifest
        dex = archive.read('classes.dex')
        providers = {'CinemaPlus': b'Lcom/cinemaplus/CinemaPlusProvider;', 'Akwam': b'Lcom/akwam/AkwamProvider;', 'TukTukCinema': b'Lcom/tuktuk/TukTukProvider;', 'CineWave': b'Lcom/cinewave/CineWaveProvider;', 'MySeed': b'Lcom/myseed/MySeedProvider;', 'CimaNowVIP': b'Lcom/cimanowvip/CimaNowVIPProvider;'}
        assert all(value not in dex for key, value in providers.items() if key != module), 'Cross-provider classes in standalone plugin'
    filename = f'{module}-v{version}.cs3'
    target = root / 'dist' / filename
    target.parent.mkdir(exist_ok=True)
    shutil.copyfile(source, target)
    raw = target.read_bytes()
    entry = dict(fileHash='sha256-' + hashlib.sha256(raw).hexdigest(), apiVersion=1,
        repositoryUrl=f'https://github.com/{repo}', fileSize=len(raw), status=3,
        language=('en' if module == 'CineWave' else 'ar'), authors=['Cinema Plus community'], tvTypes=['Movie','TvSeries'] + (['Anime'] if module in ('TukTukCinema','MySeed') else []),
        version=version, internalName=module, description=('CineWave — تجريبي؛ تشغيل Android غير مُثبت' if module == 'CineWave' else ('CimaNow VIP — نسخة اختبار؛ الدخول والتصفح على Android غير مثبتين' if module == 'CimaNowVIP' else f'{module} — إضافة مستقلة')),
        url=f'{base}/' + ('' if branch == 'builds' else 'dist/') + filename,
        name={'CinemaPlus':'Cinema Plus', 'Akwam':'Akwam | أكوام', 'TukTukCinema':'TukTuk Cinema | توك توك سينما', 'CineWave':'CineWave', 'MySeed':'MySeed | ماي سيد', 'CimaNowVIP':'CimaNow VIP | سيما ناو'}[module])
    entries.append(entry)
    slug = module.lower()
    plugins = f'plugins-{slug}-v{version}.json'
    write(out / plugins, [entry])
    if branch == "main":
        for previous in root.glob(f"plugins-{slug}-v*.json"):
            write(previous, [entry])
    data = dict(name=entry['name'], description=entry['description'], manifestVersion=1, pluginLists=[f'{base}/{plugins}'])
    write(out / f'repo-{slug}-v{version}.json', data)
    write(out / f'repo-{slug}.json', data)
write(out / 'plugins.json', entries)
if branch == 'main':
    # Existing installations receive the standalone CinemaPlus update.
    for path in root.glob('plugins-v*.json'):
        write(path, entries)
    write(out / 'plugins-v6.json', entries)
print(json.dumps([(x['internalName'], x['version'], x['fileSize']) for x in entries]))
