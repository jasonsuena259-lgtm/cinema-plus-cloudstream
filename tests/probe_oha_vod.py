#!/usr/bin/env python3
"""Non-blocking public OHA API shape probe. Never print video URLs or credentials."""
import json
import urllib.request
import urllib.error
import urllib.parse

URLS = [
    ("OHA movies", "https://oha.to/tmdb/catalog/video/movie.json?sort=trending&language=en"),
    ("OHA series", "https://oha.to/tmdb/catalog/series/series.json?sort=trending&language=en"),
    ("MHub movies", "https://mhub.mx/tmdb/catalog/video/movie.json?sort=trending&language=en"),
    ("MHub series", "https://mhub.mx/tmdb/catalog/series/series.json?sort=trending&language=en"),
    ("MHub movie details", "https://mhub.mx/tmdb/item/video/tmdb:155.json?language=en"),
    ("MHub series details", "https://mhub.mx/tmdb/item/series/tmdb:1396.json?language=en"),
    ("MHub season catalog", "https://mhub.mx/tmdb/catalog/video/season_1396_1.json?language=en"),
    ("OHA film sources movie", "https://oha.to/vod/source/movie/tmdb:155.json"),
    ("OHA film sources", "https://oha.to/vod/source/video/tmdb:155.json"),
    ("OHA episode sources", "https://oha.to/vod/source/series/tmdb:1396:1:1.json"),
    ("OHA old catalog", "https://www.oha.to/web-vod/api/list?id=movie.popular"),
]
for label, url in URLS:
    try:
        req = urllib.request.Request(url, headers={
            "Accept": "application/json",
            "User-Agent": "Mozilla/5.0 (Linux; Android 13) Chrome/125.0",
            "Referer": "https://oha.to/"
        })
        with urllib.request.urlopen(req, timeout=7) as r:
            data = r.read(500000)
            typ = str(r.headers.get("Content-Type", ""))[:45]
            status = r.status
        try:
            obj = json.loads(data)
        except (ValueError, UnicodeError):
            print(label, "status", status, "content_type", typ, "non-json")
            continue
        if isinstance(obj, dict):
            result = obj.get("items") or obj.get("data") or obj.get("results") or obj.get("sources")
            catalogs = obj.get("catalogs") or []
            catalog_ids = [str(x.get("id", "")) for x in catalogs[:3] if isinstance(x, dict)] if isinstance(catalogs, list) else []
            print(label, "catalog_count", len(catalogs) if isinstance(catalogs, list) else 0,
                  "catalog_ids", catalog_ids)
            print(label, "status", status, "keys", sorted(obj)[:15],
                  "list_count", len(result) if isinstance(result, list) else "none",
                  "list_fields", sorted(result[0].keys())[:18] if isinstance(result, list) and result and isinstance(result[0], dict) else [])
            if label == "OHA film sources movie" and isinstance(result, list):
                seen_hosts = set()
                for source in result:
                    embed = source.get("url") if isinstance(source, dict) else None
                    if not isinstance(embed, str) or not embed.startswith("https://"):
                        continue
                    host = urllib.parse.urlparse(embed).hostname or ""
                    if not host or host in seen_hosts:
                        continue
                    seen_hosts.add(host)
                    try:
                        resolve_url = "https://oha.to/vod/resolve?url=" + urllib.parse.quote(embed, safe="")
                        with urllib.request.urlopen(urllib.request.Request(resolve_url, headers={
                            "Accept": "application/json", "User-Agent": "Mozilla/5.0",
                            "Referer": "https://oha.to/"
                        }), timeout=6) as rsp:
                            info = json.loads(rsp.read(100000))
                        if isinstance(info, dict):
                            print("OHA resolver host", host, "keys", sorted(info)[:15],
                                  "direct", isinstance(info.get("url"), str),
                                  "clientFetch", isinstance(info.get("clientFetch"), dict))
                    except Exception as ex:
                        print("OHA resolver host", host, "error", type(ex).__name__)
                    if len(seen_hosts) >= 4:
                        break
        else:
            print(label, "status", status, "kind", type(obj).__name__,
                  "count", len(obj) if isinstance(obj, list) else 0)
    except urllib.error.HTTPError as e:
        print(label, "HTTP", e.code)
    except Exception as e:
        print(label, "network_failure", type(e).__name__)
