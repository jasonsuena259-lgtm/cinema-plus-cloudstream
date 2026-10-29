# Dexter.pw CloudStream provider — v1 experimental

## Implementation scope

Independent CloudStream / KodaStream module: Dexter. The source branch is
feature/dexter-provider; its release destination will be dexter-preview,
separate from akwam-preview and cinelite-preview.

### Verified route evidence
- Home: https://dexter.pw/
- Movies: https://dexter.pw/movies
- TV catalogue: https://dexter.pw/tv
- Film page (user example): https://dexter.pw/watch/429617-spider-man-far-from-home
- TV title: https://dexter.pw/show/1405-dexter
- Individual TV episode: https://dexter.pw/watch/show/1405-dexter/1/1
- Other verified TV details routes: /show/4604-smallville and /show/1402-the-walking-dead.

Live website probing: GitHub Actions run 40967337510 (2026-10-29).
The site's main page, movies, TV, movie details, show, episode and search all
returned HTTP 403 Cloudflare challenge from a public CI runner. The user-supplied
Neonvase HLS URL also returned HTTP 403 in CI. We did **not** bypass challenges.

Public site indexing supports the route shapes, but NOT the HTML selectors,
current episode anchors, search form field, or streaming-server count.
The provider therefore attempts only visible actual links and rejects pages
that cannot be parsed instead of fabricating data.

### Watch/download observation

The user supplied a real browser screenshot showing a playing movie on a
Dexter.pw watch page, and separately an HLS media playlist under
v.neonvase.top. User-visible quality variants were 1600p, 1080p, 720p and
480p in the player. A download helper reported rendition dimensions
3840x2160, 1920x1080, 1280x720 and 852x480. Those renderings are
**renditions, not independent streaming servers**.

The download helper also detected thumbs.vtt. This is a thumbnail cue sheet,
not evidence of dialogue subtitles, and is explicitly excluded by the parser.
No Arabic subtitle stream was independently verified.

### Source resolution rules

1. Use genuine movie watch URLs and episode links present in site HTML.
2. Parse HTML video/source/iframe elements and literal media URLs in inline JS.
3. Verify M3U8 playlist signatures and MP4/MKV container bytes.
4. Leave the HLS master playlist to adaptive players; do not invent unobserved
   rendition URLs from resolution labels.
5. Distinct quality URLs from the same hostname are NOT distinct server hosts.
6. Preserve Referer and subtitles from actual HTML; deduplicate URLs.
7. Registered CloudStream extractors may handle embedded hosts if applicable.
8. No Cloudflare/CAPTCHA, login, DRM, or TLS validation bypass.

### Release and Android checklist

- [ ] CI Kotlin unit tests and build pass, and correct .cs3 manifest is emitted.
- [ ] Independent dexter-preview/repo.json is published with plugin version 1.
- [ ] KodaStream installs and lists Dexter independently of Akwam and CineLite.
- [ ] On Android, cards, poster, search, episodes and season labels display.
- [ ] Confirm at least one film + one actual TV episode has picture *and* sound.
- [ ] Confirm audio, seek, 1080p and other actual variants are playable.
- [ ] Count distinct *working hostnames*, not the initial reported 21 servers.
- [ ] Verify subtitle URLs (including Arabic only if actually present).
- [ ] Validate download and playback without claiming untested 4K.

An automated build success does not satisfy the Android playback checks.
Keep Dexter marked experimental until these acceptance gates have evidence.
