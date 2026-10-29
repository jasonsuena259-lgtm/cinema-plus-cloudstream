/* Run the actual Kotlin WebView server-switcher JS on the live first-party
 * Anime player, without collecting/printing URLs, headers, cookies or tokens.
 * Only real server labels selected by the player are examined. */
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const resolver = fs.readFileSync('CineLite/src/main/kotlin/com/cinelite/CineLiteResolver.kt','utf8');
const browserCode = fs.readFileSync('CineLite/src/main/kotlin/com/cinelite/CineLiteMultiSourceBrowser.kt','utf8');
assert.ok(resolver.includes('CineLiteMultiSourceBrowser(context).capture('),
  'The multi-source WebView must be wired into the CloudStream loadLinks resolver');
assert.ok(resolver.includes('pending.trySend(request)'),
  'Signed links must be captured during the active WebView session');
assert.ok(resolver.includes('request.sourceName'),
  'Real source names must reach CloudStream links');
assert.ok(resolver.includes('verifyObserved(request)'),
  'An intercepted HLS request must pass media-content verification');
const prefix = 'val js = """(function(){';
const start = browserCode.indexOf(prefix);
const end = browserCode.indexOf('})()"""',start);
assert.ok(start >= 0 && end > start,'Missing real WebView source-picker JS');
const actualWebViewJS = browserCode.slice(start + 'val js = """'.length, end + '})()'.length);
assert.doesNotThrow(()=>new Function(actualWebViewJS), 'Invalid embedded source-picker JavaScript');
(async()=>{
 const browser = await chromium.launch({headless:true,args:['--no-sandbox']});
 try{
  const page = await browser.newPage();
  const mediaResponses = [];
  // Passive response observations only. Signed manifest URLs and auth tokens
  // must never enter artifacts or logs.
  page.on('response', response => {
    try {
      const u = new URL(response.url());
      const ext = (u.searchParams.get('ext') || '').toLowerCase();
      if (/\.(m3u8|mp4)$/i.test(u.pathname) || ext === '.m3u8' || ext === 'm3u8') {
        mediaResponses.push(response.status());
      }
    } catch (_) {}
  });
  try{
   await page.addInitScript(()=>{
    window.__CineLiteTestSourceNames=[];
    window.CineLiteSourceName = {
      select(name) {
        if(typeof name==='string' && name.length<60)
          window.__CineLiteTestSourceNames.push(name);
      }
    };
    window.CineLiteSubtitles={ collect(){} };
   });
   await page.goto('https://embed.cinelite.xyz/anime/195539/1',
     {waitUntil:'domcontentloaded',timeout:40000});
   await page.waitForTimeout(2400);
   await page.evaluate(actualWebViewJS);
   await page.waitForTimeout(7000);
   const observation = await page.evaluate(()=>{
     const selected = [...new Set(window.__CineLiteTestSourceNames||[])];
     const text = document.body.innerText;
     return {selected, advertisedSix: /6 Agents/.test(text), advertisesSources:/Stream Sources/.test(text)};
   });
   // Never fabricate servers or report success based on headings alone.
   // Site-side playback may still depend on user interaction/region;
   // report actual HTTP media observations separately from menu navigation.
   const ok = mediaResponses.filter(x=>x===200 || x===206).length;
   console.log('CINELITE_REAL_SOURCE_MENU_JS',JSON.stringify({
     advertisedSix:observation.advertisedSix,
     advertisesSources:observation.advertisesSources,
     selected:observation.selected,
     mediaResponses:mediaResponses.length,
     successfulMediaResponses:ok
   }));
   if(observation.advertisedSix)assert.ok(observation.selected.length>=2,
     'The native multi-source picker failed to select two real advertised Anime servers');
  }finally{await page.close()}
 }finally{await browser.close()}
})().catch(e=>{console.error('CINELITE_REAL_SOURCE_MENU_JS_FAILED',e.message);process.exitCode=1});
