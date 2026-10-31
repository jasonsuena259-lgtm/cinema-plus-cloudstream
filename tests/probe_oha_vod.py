#!/usr/bin/env python3
"""Non-blocking public OHA API shape probe. Never print video URLs or credentials."""
import json
import urllib.request
import urllib.error

URLS = [
    ("OHA movies", "https://oha.to/tmdb/catalog/video/movie.json?sort=trending&language=en"),
    ("OHA series", "https://oha.to/tmdb/catalog/series/series.json?sort=trending&language=en"),
    ("MHub movies", "https://mhub.mx/tmdb/catalog/video/movie.json?sort=trending&language=en"),
    ("MHub series", "https://mhub.mx/tmdb/catalog/series/series.json?sort=trending&language=en"),
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
            print(label, "status", status, "keys", sorted(obj)[:15],
                  "list_count", len(result) if isinstance(result, list) else "none",
                  "list_fields", sorted(result[0].keys())[:18] if isinstance(result, list) and result and isinstance(result[0], dict) else [])
        else:
            print(label, "status", status, "kind", type(obj).__name__,
                  "count", len(obj) if isinstance(obj, list) else 0)
    except urllib.error.HTTPError as e:
        print(label, "HTTP", e.code)
    except Exception as e:
        print(label, "network_failure", type(e).__name__)
