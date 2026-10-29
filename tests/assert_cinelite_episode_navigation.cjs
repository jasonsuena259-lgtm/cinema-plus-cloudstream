/* Validate the EXACT WebView JavaScript used by CineLiteBrowser.animeEpisodes
 * against CineLite's public Anime page. Reads only catalogue/episode metadata;
 * never records player URLs, cookies, headers, media tokens or streams. */
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const source = fs.readFileSync('CineLite/src/main/kotlin/com/cinelite/CineLiteBrowser.kt', 'utf8');
function webViewScript(stage) {
  const block = source.indexOf(stage);
  assert.ok(block >= 0, 'Missing Android episode stage: ' + stage);
  const start = source.indexOf('val script = """(function(){', block);
  assert.ok(start >= 0, 'Missing embedded JS for ' + stage);
  const end = source.indexOf('})()"""', start);
  assert.ok(end > start, 'Unterminated embedded JS for ' + stage);
  return source.slice(start + 'val script = """'.length, end + '})()'.length);
}
const openJS = webViewScript('openCard = open@{')
  .replaceAll('$id', '21')
  .replaceAll('$safeTitle', '"ONE PIECE"');
const episodesJS = webViewScript('readEpisodes = read@{');
assert.ok(!openJS.includes('$id') && !openJS.includes('$safeTitle'),
  'Unresolved Kotlin placeholders in WebView JS');

(async () => {
  const browser = await chromium.launch({ headless:true, args:['--no-sandbox'] });
  try {
    const page = await browser.newPage();
    try {
      await page.goto('https://cinelite.xyz/anime',
        { waitUntil:'domcontentloaded', timeout:40000 });
      await page.waitForFunction(() => document.querySelectorAll('.slider-track h3,.slider-track h4').length > 5,
        {timeout:20000});
      let clicked=false, episodes=[];
      for (let attempt=0;attempt<5;attempt++) {
        clicked = await page.evaluate(openJS);
        if (clicked) break;
        await page.waitForTimeout(900);
      }
      assert.ok(clicked, 'Android WebView JS did not open the REAL AniList ONE PIECE card (ID 21)');
      for (let attempt=0;attempt<7;attempt++) {
        await page.waitForTimeout(700);
        const raw = await page.evaluate(episodesJS);
        episodes = JSON.parse(raw);
        if (episodes.length) break;
      }
      assert.ok(episodes.some(x=>x.number===1),
        'Android WebView JS failed to identify the real ONE PIECE Episode 1 button');
      assert.ok(episodes.every(x=>Number.isInteger(x.number)&&x.number>0),
        'Invalid episode number in native WebView parser');
      console.log('CINELITE_NATIVE_EPISODE_JS_PASS '+JSON.stringify({
        anime:'ONE PIECE',anilistId:21,realEpisodeButtons:episodes.length,
        episodeOneFound:true
      }));
    } finally { await page.close(); }
  } finally { await browser.close(); }
})().catch(e=>{ console.error('CINELITE_NATIVE_EPISODE_JS_FAIL',e.message);process.exitCode=1; });
