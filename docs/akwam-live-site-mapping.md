# Akwam.ss provider — live structure and release gates

## Proven live pages (29 October 2026)

- `/one` home, `/recent`, `/movies`, `/series`, `/shows`
- `/movies?section=29` Arabic movies; `/series?section=30` foreign TV
- Search: `/search?q=Love%20Language` returned an exact title; `s=` and `query=` did not
- Cards use `.entry-box` with poster link `a.box` and title `.entry-title a.text-white`
- Movie details: `/movie/11572/love-language` has two real `/watch/{sourceId}/{contentId}/...` and `/download/{sourceId}/{contentId}/...` links
- TV details: `/series/5908/...` lists 8 real episode pages (`/episode/{id}/...`)
- Actual watch HTML contains a `<video>` with two `<source type="video/mp4">` links on `s203d1.downet.net`, no iframe on tested film
- Download HTML contains `downet.net` links
- The CI HTTP Range request to the external file host encountered TLS `SSLError`, so **playability remains unverified**; never disable TLS certificate verification to fabricate success

## Runtime architecture

`AkwamParser.kt`: parse real HTML links/cards, ordered episodes, and quality-specific watch/download URLs.

`AkwamProvider.kt`: CloudStream/KodaStream main sections, search, details and episodes.

`AkwamResolver.kt`: for each observed quality, inspect both genuine watch and download pages, find direct video MP4s, and publish only if a bounded byte-range request proves an MP4 container. Collect actual subtitle files; rely on CloudStream's registered extractors for foreign iframe hosts.

`.github/workflows/akwam-ss-preview.yml`: compile and test, verify .cs3 manifest, publish separate `akwam-preview` branch with `repo.json` and `plugins.json`. Never alter CineLite's `cinelite-preview` branch.

## Explicit release acceptance criteria

1. Kotlin tests and .cs3 compilation pass.
2. KodaStream installs the extension as internalName `Akwam` without affecting `CineLite`.
3. Cards have correct poster/title/URL.
4. TV episode links and seasons are real and correctly ordered.
5. On a movie and TV episode, at least one selected source passes first-byte and playback validation with both audio and video.
6. Additional watch and download sources are accurately labeled and not claimed usable merely because they appear in HTML.
7. Subtitles are reported only if actual subtitle URLs exist and are readable.
8. Release remains experimental until tests 2–7 are observed on Android.

## CI diagnosis history

- The initial Kotlin compile failed from misplaced `>=` token in generic method signatures; corrected.
- A subsequent compile found missing JSpecify Jsoup annotations; added explicit `org.jspecify:jspecify:1.0.0`.
- On later runs, the upstream CloudStream Gradle plugin `com.github.recloudstream:gradle:-SNAPSHOT` and `master-SNAPSHOT` intermittently returned JitPack resolution failures. Use cache-first build workflow; do not report a package as published until the actual `akwam-preview` branch exists.
