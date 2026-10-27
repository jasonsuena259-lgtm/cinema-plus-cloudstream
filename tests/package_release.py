#!/usr/bin/env python3
"""Publish two independently installable artifacts and validate their entry points."""
import hashlib, json, os, shutil, zipfile
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
for module, version, entrypoint in [('CinemaPlus', 6, 'com.cinemaplus.CinemaPlusPlugin'), ('Akwam', 1, 'com.akwam.AkwamPlugin')]:
    source = root / module / 'build' / f'{module}.cs3'
    with zipfile.ZipFile(source) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        assert manifest['version'] == version and manifest['pluginClassName'] == entrypoint, manifest
        dex = archive.read('classes.dex')
        other = b'Lcom/akwam/AkwamProvider;' if module == 'CinemaPlus' else b'Lcom/cinemaplus/CinemaPlusProvider;'
        assert other not in dex, 'Cross-provider classes in standalone plugin'
    filename = f'{module}-v{version}.cs3'
    target = root / 'dist' / filename
    target.parent.mkdir(exist_ok=True)
    shutil.copyfile(source, target)
    raw = target.read_bytes()
    entry = dict(fileHash='sha256-' + hashlib.sha256(raw).hexdigest(), apiVersion=1,
        repositoryUrl=f'https://github.com/{repo}', fileSize=len(raw), status=3,
        language='ar', authors=['Cinema Plus community'], tvTypes=['Movie','TvSeries'],
        version=version, internalName=module, description=f'{module} — إضافة مستقلة',
        url=f'{base}/' + ('' if branch == 'builds' else 'dist/') + filename,
        name='Cinema Plus' if module == 'CinemaPlus' else 'Akwam | أكوام')
    entries.append(entry)
    slug = module.lower()
    plugins = f'plugins-{slug}-v{version}.json'
    write(out / plugins, [entry])
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
