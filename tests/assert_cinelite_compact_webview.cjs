/* Execute the exact Kotlin-embedded WebView snapshot JavaScript in live Chromium.
 * The result must be compact and contain real card IDs, never the full React DOM.
 * No media links, auth tokens or personal data are logged. */
const {chromium}=require('playwright');
const fs=require('node:fs');
const assert=require('node:assert/strict');
const source=fs.readFileSync('CineLite/src/main/kotlin/com/cinelite/CineLiteBrowser.kt','utf8');
const start=source.indexOf('"""(function(){',source.indexOf('fun readDom('));
const end=source.indexOf('})()"""',start);
assert.ok(start>=0&&end>start,'Missing snapshot code');
const code=source.slice(start+3,end+4);
(async()=>{
 const b=await chromium.launch({headless:true,args:['--no-sandbox']});
 try{
  for(const route of ['/anime','/']){
   const page=await b.newPage();
   try{
    await page.goto('https://cinelite.xyz'+route,{waitUntil:'domcontentloaded',timeout:35000});
    // CI runners sometimes receive the React shell before the real carousels.
    // Wait for cards instead of assuming a fixed hydration time.
    await page.waitForFunction(() =>
      document.querySelectorAll('.slider-track h3,.slider-track h4,section h3').length>=5,
      null,{timeout:18000}).catch(()=>{});
    let result={html:'',count:0};
    for(let attempt=0;attempt<4;attempt++){
      result=JSON.parse(await page.evaluate(code));
      if(result.count>=5)break;
      await page.waitForTimeout(1400);
    }
    assert.equal(typeof result.html,'string');
    assert.ok(result.html.length<250000,
      'Android WebView IPC payload is unexpectedly large');
    assert.ok(result.count>=5,'No live cards from WebView DOM extraction');
    assert.ok(result.html.includes('data-cinelite-id='));
    assert.ok(!result.html.includes('<script'));
    if(route==='/anime')assert.ok(result.html.includes('data-cinelite-type="anime"'));
    const labels=[...result.html.matchAll(/<h3>([^<]{1,65})<\/h3>/g)]
      .slice(0,6).map(x=>x[1]);
    console.log('COMPACT_ANDROID_SNAPSHOT',JSON.stringify({
      route,count:result.count,payloadBytes:result.html.length,samples:labels
    }));
   } finally{await page.close();}
  }
 }finally{await b.close()}
})().catch(e=>{console.error('COMPACT_SNAPSHOT_FAILED',e.message);process.exitCode=1});
