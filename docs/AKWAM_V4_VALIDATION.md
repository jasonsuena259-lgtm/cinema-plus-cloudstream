# Akwam.ss v4 — validation and handoff

Scope: independent **CloudStream/KodaStream extension**, maintained on `feature/akwam-provider`. **Do not change CineLite or its publication branch.**

## Public installation channel
- Repository JSON: `https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/akwam-preview/repo.json`
- Package: `https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/akwam-preview/Akwam-v4.cs3`
- Published by `.github/workflows/akwam-ss-preview.yml`; the V4 package must be confirmed to exist after CI success. The V3 package was previously published successfully.

## Verified website structure (October 29, 2026)
- Listings: `/movies`, `/series`, `/shows`, `/recent`, `/one`.
- Search uses `/search?q=...`; cards use `.entry-box` and `.entry-title a.text-white`.
- Movie example: `/movie/11572/love-language` with separate 720p/360p watch/download routes.
- Series example: `/series/5908/the-diplomat-...` has eight observed real episode links.
- The `/shows/{id}` category contains independently playable TV specials/documentaries, not necessarily episodic series.
- A tested watch page exposed direct MP4 sources and a download page exposed external MP4 URLs.

## Code changes in v4
1. Replace obsolete `akwam.day` optional live tests with opt-in `akwam.ss` catalogue, qualities and season tests.
2. Expose actual MP4/MKV file and HLS playlist candidates only after format-aware validation. No fake servers.
3. Validate HLS syntax before labeling any URL as a playlist; identify MKV EBML and MP4 container signatures using a bounded Range request.
4. Recognize subtitle URLs with signed query parameters, retain stated language labels, and use `Unknown` if no actual language is identified. No invented Arabic subtitle.
5. Publish v4 package and repository metadata with independent version number so existing installations can update.

## Validation gates
- [ ] `./gradlew Akwam:testDebugUnitTest Akwam:make` succeeds on latest commit.
- [ ] `akwam-preview/Akwam-v4.cs3` exists and `plugins.json` advertises version 4.
- [ ] Install/update Akwam in KodaStream on Android and verify catalogue/search/cards/posters/episodes.
- [ ] Validate movie **and** TV episode playback with **picture and audio**, including seeking.
- [ ] Confirm each watch/download link with a real container/playlist and actual playback; count only working sources.
- [ ] Validate separately offered subtitles using an actual subtitle URL and spoken language.
- [ ] Confirm behavior with failed CDN TLS certificate checks without bypassing certificate verification.

`AKWAM_LIVE_TEST=1 ./gradlew Akwam:testDebugUnitTest --rerun-tasks` enables optional live integration tests. They may fail on access or network restrictions and do **not** validate Android playback.

**Release label stays experimental until Android playback and subtitles are observed.** GitHub Kotlin/build success alone is not a release gate for streaming availability.
