# Dexter v3: Arabic subtitles, posters, Cloud3 stability

Progress as of October 29 2026. Branch: feature/dexter-provider.

## Subtitle evidence (real network checks)

- `https://sub.vdrk.site/v1/movie/429617`: 200, 99 subtitle entries.
- `https://sub.vdrk.site/v1/tv/259909/1/1`: 200, 72 subtitle entries.
- Both returned Arabic, Arabic2, Arabic3, Arabic4.
- Arabic files hosted on `cache.vdrk.site` end in `.vtt`.
- Tested Arabic VTT resource: HTTP 200, `text/vtt`, begins with `WEBVTT`.
- See GitHub Actions workflow `dexter-subtitles-probe.yml`, run 40971507523.

## Implementation

- `DexterSubtitles.kt`: only real URLs from successful subtitle responses.
- `DexterResolver.kt`: adds tracks before returning successful API streams.
- `DexterApi.kt`: root + per-source captions/tracks/subtitles, HLS EXT-X-MEDIA.
- Deduplicates URLs, prioritizes Arabic captions, excludes thumbnail cue sheets.
- Files are not automatically translated; alignment can vary by release.

## Cards/posters

- Dexter HTML routinely returns Cloudflare 403 on GitHub hosted runners.
- `v3-cinemeta.strem.io` search and catalogue JSON returned 200 with poster
  and TMDB/IMDb identifiers (workflow `dexter-metadata-probe.yml`).
- `DexterCatalog.kt` maps those IDs to Dexter movie/TV paths and fallback
  metadata; playback requires Dexter's API and is not guaranteed for all titles.

## Cloud3

- Upstream fork `mo7AmMeD64/cloudSTREAM3` v0.8.0 app package `com.kodastream.app`
  loads `.cs3` using `PathClassLoader` via `PluginManager`.
- `dexter-cloud3-smoke.yml` installs the public Cloud3 APK on emulator,
  installs the Dexter repository through its own UI, checks for FATAL EXCEPTION,
  and restarts the app. Read its run outcome, not this document, for results.

## Release gate

- Build/tests must pass and repo manifest must expose Dexter-v3.cs3.
- Native Cloud3 emulator may be inconclusive due launcher or JitPack errors.
- Phone playback, subtitle synchronization, all servers are NOT established
  by successful Gradle tests alone.
- Never suggest that v3 is published until the release branch exists and
  advertises `version: 3`.

Existing Akwam/CineLite branches are not changed.
