# Dexter v2 - Fix for empty server picker

v1 looked for media links in the server-rendered watch page HTML.
An independent open-source Dexter client documents its public content API:
- Movie: /api/movie/{tmdbId}?site=dexter
- Episode: /api/tv/{tmdbId}/episode/{season}/{episode}?site=dexter
Reference: https://github.com/justemir84/nuvio-dexter-plugin/blob/main/providers/dexterpw.js

v2 derives TMDB IDs directly from genuine movie and TV episode paths,
fetches API JSON first, and emits actual media source URLs. It parses
the URL, kind, label, quality and subtitle metadata. It ignores duplicates
and refuses to invent server endpoints. HLS master-variant probes are
limited in number and duration so the picker is not delayed by 21 CDN calls.
Fallback HTML parsing is retained for source markup.

Limits: October 29 GitHub Actions probe returned HTTP 403 from both public
API endpoints, so actual Android playback, subtitle and server counts remain
unverified. No challenge/CAPTCHA/TLS/DRM bypass has been performed.
The user must test v2 on device; a test fixture or successful build does
not demonstrate that live sources are playable. Do not count quality
renditions as distinct servers.
