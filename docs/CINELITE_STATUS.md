# CineLite CloudStream module — experimental

Site: https://cinelite.xyz
GitHub branch: feature/cinelite-provider

## Grounded facts (2026-10-29)
- CineLite publicly advertises films, TV and anime.
- External site inspection reported the app uses api.cinelite.xyz and embed.cinelite.xyz.
- We could not fetch actual film HTML, inspect the live JSON API schema, or validate one video link from the assistant environment.
- No sample content cards, media URLs, server names or video availability are claimed as confirmed.

## What has been implemented
- A self-contained provider and registered plugin for CloudStream, without altering existing providers.
- Android WebView-rendered DOM inspection for JavaScript-heavy home/details pages.
- Card discovery ONLY from actual links and titles on the rendered site. Empty catalogue produces a diagnostic error (never 20 fake examples).
- Attempted query-based search. The /search?q= route is a candidate, **not confirmed**.
- Details and TV episodes only when links are exposed in DOM. No fabricated episodes.
- Per-iframe/server extraction using CloudStream extractors, with WebView capture of HLS/MP4 if necessary.
- Byte-prefix validation of HLS/MP4 candidates prior to publishing playback links.
- Synthetic parser tests and a GitHub Actions live DOM **diagnostic**.

## Known blockers (not a finished end-to-end integration)
- SPA internal navigation and URL patterns may not be exposed as anchor elements.
- Home cards may rely on JS router buttons; search, TV season switching and other UI interactions need real DOM traces.
- There is no verified player API endpoint or site-authored list of all selectable servers.
- No Android run, real-title search, episode list, verified stream/segment or subtitles have been observed in this environment.
- Browser media capture can miss MediaSource/blob streams, protected media or server tabs requiring user interaction.
- A compiler/CI result is required before calling this build usable.

This PR is not ready to merge as a fully working source. After obtaining live DOM / safe request traces, replace heuristic selectors and routes with site-specific confirmed integration and test actual movie and series playback.
