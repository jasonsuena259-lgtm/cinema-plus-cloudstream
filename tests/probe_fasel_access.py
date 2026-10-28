#!/usr/bin/env python3
"""Read-only FaselHD access probe. A successful HTTP response is not playback proof."""
import argparse
import concurrent.futures
import datetime
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def probe(url):
    result = {"url": url, "utc": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            response = urllib.request.urlopen(request, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read(2_000_000).decode("utf-8", errors="replace")
            title = re.search(r"<title[^>]*>(.*?)</title>", body, re.I | re.S)
            blocked = any(marker in body.lower() for marker in (
                "sorry, you have been blocked", "cf-chl-", "just a moment..."
            ))
            result.update(http=response.status, final_url=response.url,
                          title=title.group(1).strip() if title else None,
                          protection_page=blocked,
                          access="BLOCKED" if blocked or response.status in (401, 403, 429)
                          else "HTTP_OK_UNVERIFIED" if 200 <= response.status < 300 else "FAIL")
    except Exception as error:
        result.update(access="FAIL", error=type(error).__name__ + ": " + str(error))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("urls", nargs="*", default=["https://faselhd.info/"])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    for url in args.urls:
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.hostname not in ("faselhd.info", "www.faselhd.info"):
            parser.error("Only HTTPS URLs on the requested FaselHD domain are accepted")
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        results = list(executor.map(probe, args.urls))
    text = json.dumps(results, ensure_ascii=False, indent=2) + "\n"
    print(text, end="")
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    raise SystemExit(0 if all(row["access"] == "HTTP_OK_UNVERIFIED" for row in results) else 1)
