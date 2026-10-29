# CineLite — CloudStream provider status (2026-10-29)

Pull request: https://github.com/jasonsuena259-lgtm/cinema-plus-cloudstream/pull/2
Branch: `feature/cinelite-provider`
Status: **Experimental / draft; not confirmed playable, not ready to merge.**

## Verified with independent live Playwright tests in GitHub Actions

- `https://cinelite.xyz/` loaded successfully and rendered 371 image elements with real movie/TV cards.
- Cards are React-rendered clickable `div` elements, **not** `a href` anchors.
- An actual React fiber item on Grey's Anatomy contained `id=1416`, `media_type=tv`.
- Selecting Grey's Anatomy opened a detail overlay without a URL change, with named episodes for season 1 and an interactive season menu; switching to season 2 displayed its own real episodes. The season dropdown listed seasons 1–23 during the probe.
- Search is at `/search`; its text box returned film and TV search results for Fight Club.
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
- CloudStream Gradle build CI initially failed to resolve `com.github.recloudstream:gradle:-SNAPSHOT` from JitPack (an external build dependency). A trial with `master-SNAPSHOT` is under CI evaluation, not a validated fix.
- Search/card/season UI may change. Synthetic unit fixtures alone cannot prove Android behavior.

## Merge criteria

1. Android Gradle build and unit tests must pass, with a valid `.cs3` artifact.
2. Launch the addon and confirm home cards, search cards, series selection and more than one season.
3. Confirm real media bytes from a movie and an episode, for each source actually offered by the site, using access legitimately available from the app.
4. Verify subtitles, playback headers, downloads and source switching individually. If the server API continues returning 403, clearly report playback unavailable; do not claim success.

Public site API was inspected only to learn the observable supported format; no challenges were bypassed.
