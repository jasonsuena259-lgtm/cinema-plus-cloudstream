# Dexter v4 — site-driven episode index and show poster repair

Scope: independent `feature/dexter-provider` / `dexter-preview`.

## Root causes found from user Android screenshots

1. Show cards on the primary homepage used blank or blocked Dexter artwork,
   while externally sourced catalog cards already displayed real posters.
2. Dexter's `Watch Miniseries (8 episodes)` link points to S01E01.
   It is a SEASON shortcut, not an episode title or entire episode index.
3. The v3 provider used the few links present on the show page, and only
   used Cinemeta episode metadata when the list was completely empty.
   This caused VisionQuest to show only `1. Watch Miniseries (8 episodes)`.

## Evidence from the Dexter website index

- `https://dexter.pw/show/213375-visionquest`: one season, eight episodes.
- Its `Watch Miniseries (8 episodes)` link is
  `/watch/show/213375-visionquest/1/1`.
- The site separately exposes S1E2 Theseus, S1E3 Reboot, S1E4 Malware.
- `https://dexter.pw/show/4607-lost`: six season links containing
  24, 24, 23, 13, 17 and 17 episodes (118 total).

## v4 behavior

- Parse the season count, season number, and the actual Dexter first-episode
  link shown in the show's page.
- Assemble sorted episode choices in `/watch/show/{tmdb}-{slug}/{season}/{ep}`
  routes following Dexter's observed route pattern.
- Never count a season shortcut as an individual episode.
- Preserve explicit on-site episode hyperlinks, season ordering and titles.
- Prefer Cinemeta's descriptive titles when the metadata matches.
- If the page is blocked, use Cinemeta's structured TV index as fallback.
- Use external posters only if their TMDB ID matches Dexter's real card ID.
- Recognize srcset/data-src/data-poster/CSS images rather than placeholders.
- Keep the same v3 playback resolver and Arabic subtitle sources unchanged.

## Important limitations

Website advertised season counts may include future or unavailable streams.
Constructed episode display URLs follow a verified *path pattern* but are
not independently verified playable: the individual Dexter API is still
queried at playback. Never claim all of these play without device checks.
GitHub Actions host may receive HTTP 403 for Dexter HTML and video APIs.
Catalog artwork may be unavailable for obscure titles; no fabricated posters.

## Acceptance checks

- [ ] Kotlin build and new v4 unit tests pass.
- [ ] Published dexter-preview `plugins.json` version = 4.
- [ ] On Android VisionQuest displays 8 ordered episode entries, not one
      season shortcut.
- [ ] On Android Lost exposes 6 distinct seasons, 118 entries.
- [ ] Original home and TV catalog show real posters for Grey's Anatomy,
      Dexter and other titles where matching metadata is available.
- [ ] v3-working video servers and Arabic subtitle options remain functional.
- [ ] Do not modify Akwam or CineLite.

Site evidence references: Dexter.pw show/213375-visionquest, show/4607-lost.
