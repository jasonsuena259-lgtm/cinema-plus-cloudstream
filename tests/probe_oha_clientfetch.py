#!/usr/bin/env python3
"""Bounded, non-blocking OHA VOD clientFetch integration probe.
Print only hostnames, hop counts and response shapes; never stream URLs/tokens.
"""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request
from http.cookies import SimpleCookie

BASE = "https://oha.to"
AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/125.0"
MAX = 1_000_000

def fetch(url, *, headers=None, data=None):
    req = urllib.request.Request(url, data=data, headers=headers or {
        "Accept": "application/json", "User-Agent": AGENT
    })
    try:
        with urllib.request.urlopen(req, timeout=7) as resp:
            return resp.status, resp.geturl(), resp.headers, resp.read(MAX)
    except urllib.error.HTTPError as err:
        return err.code, err.geturl(), err.headers, err.read(MAX)

def get_json(url, data=None):
    code, target, headers, raw = fetch(url, data=data, headers={
        "Accept": "application/json",
        "Content-Type": "application/json",
        "User-Agent": AGENT,
        "Referer": BASE + "/"
    })
    if code < 200 or code > 299:
        raise ValueError("HTTP " + str(code))
    return json.loads(raw)

def safe_url(link):
    parsed = urllib.parse.urlsplit(link)
    return parsed.scheme in ("http", "https") and bool(parsed.hostname) and not (
        parsed.hostname.startswith("127.") or parsed.hostname == "localhost" or
        parsed.hostname.startswith("10.") or parsed.hostname.startswith("192.168.") or
        parsed.hostname.startswith("169.254.")
    )

def probe(embed):
    name = urllib.parse.urlsplit(embed).hostname or "invalid"
    jar = {}
    state = get_json(BASE + "/vod/resolve?url=" + urllib.parse.quote(embed, safe=""))
    if not isinstance(state, dict):
        print("host", name, "unexpected first reply")
        return
    for hop in range(1, 5):
        if isinstance(state.get("url"), str):
            print("host", name, "resolved", True, "hops", hop - 1,
                  "format", state.get("format", "unknown"))
            return
        task = state.get("clientFetch")
        if not isinstance(task, dict) or not task.get("id") or not task.get("url"):
            print("host", name, "stopped", hop, "keys", sorted(state)[:8])
            return
        target = task["url"]
        if not safe_url(target):
            print("host", name, "refused_target")
            return
        domain = urllib.parse.urlsplit(target).hostname
        method = task.get("method", "GET").upper()
        if method not in ("GET", "POST", "HEAD"):
            print("host", name, "unsupported_method", method)
            return
        sent = {k: str(v) for k, v in (task.get("headers") or {}).items()
                if k.lower() in ("referer", "origin", "user-agent", "accept", "accept-language", "cookie", "authorization")}
        sent.setdefault("User-Agent", AGENT)
        if jar.get(domain):
            sent["Cookie"] = "; ".join(f"{k}={v}" for k, v in jar[domain].items())
        status, final, hdrs, body = fetch(target, headers=sent,
                                         data=b"" if method == "POST" else None)
        if not safe_url(final):
            print("host", name, "refused_redirect")
            return
        cookie_data = jar.setdefault(urllib.parse.urlsplit(final).hostname, {})
        for s in hdrs.get_all("Set-Cookie", []):
            try:
                cookies = SimpleCookie()
                cookies.load(s)
                for key in cookies: cookie_data[key] = cookies[key].value
            except Exception: pass
        retained = {}
        for key in ("Content-Type", "Content-Length", "Last-Modified", "ETag", "Location", "Cache-Control"):
            if hdrs.get(key): retained[key.lower()] = hdrs[key]
        if hdrs.get_all("Set-Cookie"):
            retained["set-cookie"] = ", ".join(hdrs.get_all("Set-Cookie"))
        payload = json.dumps({
            "id": task["id"], "status": status, "url": final, "headers": retained,
            "body": base64.b64encode(body).decode("ascii"), "encoding": "base64"
        }).encode("utf-8")
        state = get_json(BASE + "/vod/client-fetch", payload)
        if not isinstance(state, dict):
            print("host", name, "unexpected_relay")
            return
    print("host", name, "max_hops", 4, "next", sorted(state)[:8])

try:
    sources = get_json(BASE + "/vod/source/movie/tmdb:155.json").get("sources", [])
    targets = []
    seen = set()
    for source in sources:
        url = source.get("url", "")
        if not safe_url(url): continue
        host = urllib.parse.urlsplit(url).hostname
        if host and host not in seen:
            seen.add(host)
            targets.append(url)
        if len(targets) == 2: break
    print("OHA clientFetch probe source hosts", len(targets))
    for target in targets:
        try: probe(target)
        except Exception as e:
            print("host", urllib.parse.urlsplit(target).hostname,
                  "probe_error", type(e).__name__)
except Exception as exc:
    print("OHA clientFetch unavailable:", type(exc).__name__)

# Confirm episodes use the same resolution flow, not just movie sources.
try:
    episodes = get_json(BASE + "/vod/source/series/tmdb:1396:1:1.json").get("sources", [])
    voe = next((item["url"] for item in episodes if
                "voe." in urllib.parse.urlsplit(item.get("url", "")).netloc), None)
    print("OHA clientFetch series probe", bool(voe))
    if voe:
        try: probe(voe)
        except Exception as exc:
            print("series VOE probe_error", type(exc).__name__)
except Exception as exc:
    print("OHA clientFetch series unavailable:", type(exc).__name__)
