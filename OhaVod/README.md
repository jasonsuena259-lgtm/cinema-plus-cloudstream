# OHA Movies & Series | أوها — CloudStream

Independent provider for the movie and TV catalog shown at https://oha.to. It does NOT replace the separate OHA Live extension.

## Scope
- Trending Series, Popular Series, Trending Movies, Popular Movies
- Search across movies and shows
- Official OHA / MHub metadata and real season/episode listings (including specials when returned)
- Retrieve all source entries through the OHA VOD API and preserve each host, quality, and language
- Native CloudStream host extractors for embedded servers; direct HLS/MP4/DASH only for genuine video URLs

## Limitations
- Successful compilation does not establish compatibility with every live OHA server.
- Some hosts (Dood, Filemoon, VOE, Veev, Mixdrop) need a dedicated extractor or interactive clientFetch handshake.
- An HTML embed URL must not be reported as an actual playable video link.
- No guaranteed subtitles; extractors may supply them if present.
- OHA and MHub APIs may change; Android playback must be checked in CloudStream.

## Build
`./gradlew OhaVod:make`

## CloudStream repository, after CI release
https://raw.githubusercontent.com/jasonsuena259-lgtm/cinema-plus-cloudstream/builds/repo-ohavod-v1.json
