# CineLite — CloudStream provider status (2026-10-29)

Pull request: https://github.com/jasonsuena259-lgtm/cinema-plus-cloudstream/pull/2
Branch: `feature/cinelite-provider`
Status: **Build and real-card smoke tests pass; playback still blocked/unverified. Draft, not ready to merge as a verified player.**

## 2026-10-29 — Android downloader evidence and v4 changes

- A user-provided Android download-manager screenshot shows that a CineLite Batman watch page exposes an approximately 3.6 GB segmented media item and several external `.srt` subtitle URLs. This demonstrates discoverability in a browser/download manager, **not successful CloudStream playback**.
- The observed signed media endpoints use two legitimate formats: a `.m3u8` suffix and a `/proxy/hls?…&ext=.m3u8` route. Tokens are temporary and must **never** be hardcoded, logged, included in tests or committed.
- Corrected media classification so an HLS proxy with `ext=.m3u8` is passed to CloudStream with `ExtractorLinkType.M3U8`, rather than incorrectly treated as MP4.
- WebView collects actual SRT/VTT/ASS subtitle anchors/requests, if present in the loaded player's DOM/network. It forwards them only with validated media. External subtitle retrieval is not guaranteed.
- First-party movie/anime watch pages can fall back to their **observed embed path** when the asynchronous iframe has not yet appeared in the snapshot.
- Added fabricated-token *unit fixtures* covering `ext=.m3u8`, ordinary `.m3u8`, `.mp4`, and both observed watch-route forms. The real user's URLs/tokens are not present in source code.
- v4 Gradle build, preview publication, and **actual playback on the user's Android device** must be evaluated separately. Never report media/translation availability until those checks succeed.

## Verified with independent live Playwright tests in GitHub Actions

- `https://cinelite.xyz/` loaded successfully. A new strict Chromium smoke test extracted **182 unique React-identified cards** on the homepage: **133 movies, 49 TV shows, all 182 with poster URLs** (2026-10-29, run `40887332069`). The old exploratory probe also found 371 image elements, which should not be confused with 371 unique cards.
- Cards are React-rendered clickable `div` elements, **not** `a href` anchors.
- An actual React fiber item on Grey's Anatomy contained `id=1416`, `media_type=tv`.
- Selecting Grey's Anatomy opened a detail overlay without a URL change, with named episodes for season 1 and an interactive season menu; switching to season 2 displayed its own real episodes. The season dropdown listed seasons 1–23 during the probe.
- Search is at `/search`; its text box returned film and TV search results for Fight Club. The strict smoke test confirmed **22 React-identified search cards**, including 21 whose titles match Fight Club.
- The public embed documentation at `https://embed.cinelite.xyz/` specifies movie route `/550` and TV route `/1399/1/1`. Both routes rendered their correct movie/episode metadata in an actual browser.
- Player UI exposed `Clove`, `Cast`, `Audio & Subs` controls and a video element.

## Current code

- CineLite module includes a registered CloudStream provider and Android WebView React DOM parser.
- Only `memoizedProps.item` containing real IDs from the website's rendered cards can create home/search cards, with an anchor parser as compatibility fallback.
- Movie links use CineLite's documented public embed route, with the site's actual item ID.
- Series episodes are gathered by searching the actual title, selecting the actual React card, opening the actual season selector, and reading actual episode titles/numbers. Only the documented embed route is used for episode data.
- Stream handling tries CloudStream's extractors and, where possible, captures real HLS/MP4 network requests; a playable stream is only returned if its downloaded prefix validates as HLS/MP4.
- This approach does not bypass access restrictions, and **must not display fake playable links when server extraction fails**.

## Confirmed blockers

- The live public embed player generated requests to `api.mycinebackendfuckoff.site/servers`, `/provider/vault`, and `/subtitles` that returned **HTTP 403** in the GitHub Actions browser. A Cloudflare verification was also encountered.
- On the tested film and episode player, the HTML and metadata appeared but the video element remained uninitialized; **no working video media request or HLS/MP4 URL was verified**.
- Therefore **working playback, download links, subtitles and complete server coverage are NOT demonstrated**.
- Live Android/CloudStream provider verification has not been run.
- The initial CineLite-specific compile error on Jsoup's JSpecify `@Nullable` annotations is **fixed** by explicitly depending on `org.jspecify:jspecify:1.0.0`. A full GitHub Actions Gradle build, unit-test and packaging workflow subsequently **passed** (run `40887047961`). More recent runs should be checked separately for regressions.
- Search/card/season UI may change. Synthetic unit fixtures alone cannot prove Android behavior.

## Merge criteria

1. ~~Android Gradle build and unit tests must pass, with a valid `.cs3` artifact.~~ **Passed in CI** (run `40887047961`); still validate installation on device.
2. Launch the addon and confirm home cards, search cards, series selection and more than one season.
3. Confirm real media bytes from a movie and an episode, for each source actually offered by the site, using access legitimately available from the app.
4. Verify subtitles, playback headers, downloads and source switching individually. If the server API continues returning 403, clearly report playback unavailable; do not claim success.

Public site API was inspected only to learn the observable supported format; no challenges were bypassed.
