# MySeed Analysis

## Website Information
- URL: `https://myseed.baby`
- Language: Arabic
- Media types: Movies, TV Series, Anime

## Content Structure
1. **Homepage Sections:**
   - Categories map to specific paths like `topvideos.php`, `newvideos.php` or `category.php?cat=...`
   - Content is typically listed within `ul#pm-grid li.col-xs-6` items.

2. **Search:**
   - Endpoint: `/search.php?keywords=query`
   - Search results have similar layout with posters, links, and titles.

3. **Details Page (`/watch.php?vid=...`):**
   - Contains a video player section.
   - For series, contains episodes list matching `ul.pm-ul-browse-videos li.col-xs-6`.
   - Title, description, poster are easily extracted.

4. **Playback (`/play.php?vid=...`):**
   - Extracts server options within `ul.list_servers li` elements.
   - Servers are embedded through iframes provided in `data-embed` attributes.
   - Found servers: Vidspeed, 1vid, Vkvideo, Ok, Vidmoly, Bysesukior, Hgcloud, Voe, Dsvplay.
   - Uses typical CloudStream extractors through iframe resolution.

## Android Status
- MySeed uses simple direct embed server iframe passing. Most common hosters will fallback correctly or extract standard m3u8/mp4.
