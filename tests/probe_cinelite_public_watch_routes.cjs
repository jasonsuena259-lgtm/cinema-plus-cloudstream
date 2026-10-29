/* Validate first-party watch URL paths through the site's real React UI.
 * Never log signed HLS URLs, query parameters, cookies, or credentials. */
const {chromium}=require('playwright');
const fs=require('node:fs');
// Run CineLite's exact Android WebView search-card JS on the live TV title,
// rather than a separate test-only approximation.
const native=fs.readFileSync(
  'CineLite/src/main/kotlin/com/cinelite/CineLiteBrowser.kt','utf8');
const tvBegin=native.indexOf('suspend fun seriesEpisodes(');
const jsMarker='val selectScript = """(function(){';
const jsBegin=native.indexOf(jsMarker,tvBegin);
const jsEnd=native.indexOf('})()"""',jsBegin);
if(tvBegin<0||jsBegin<0||jsEnd<jsBegin)
 throw Error('TV episode native selection script missing');
const nativeSelect=native.slice(jsBegin+jsMarker.indexOf('"""')+3,
  jsEnd+'})()'.length).replaceAll('$safeQuery','"VisionQuest"').replaceAll('$id','213375');
const targets=[
 {title:'VisionQuest',kind:'tv',episode:6},
 {title:'Fight Club',kind:'movie'}
];
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 try{
  for(const target of targets){
   const page=await browser.newPage();
   try{
    await page.goto('https://cinelite.xyz/search',
      {waitUntil:'domcontentloaded',timeout:32000});
    const input=page.locator('input[type=text]').first();
    await input.waitFor({timeout:13000});
    await input.fill(target.title);
    await page.waitForTimeout(2100);
    const matched=target.kind==='tv'
      ? await page.evaluate(nativeSelect)
      : await page.evaluate(title=>{
          const headers=[...document.querySelectorAll('h3')]
            .filter(x=>x.textContent.trim().toLowerCase()===title.toLowerCase());
          const card=headers.map(x=>x.closest('div.cursor-pointer,div.group'))
            .find(Boolean);
          if(!card)return false;
          card.click();return true;
        },target.title);
    if(!matched){
      console.log('CINELITE_WATCH_ROUTE '+JSON.stringify({kind:target.kind,found:false}));
      continue;
    }
    await page.waitForTimeout(1800);
    if(target.kind==='tv'){
      const ep=await page.evaluate(n=>{
        const candidates=[...document.querySelectorAll('span,[data-episode]')]
          .filter(e=>new RegExp('^(?:EPISODE|EP\\.?|الحلقة)\\s*#?\\s*'+n+'$','i')
            .test((e.textContent||'').trim()));
        const card=candidates.map(x=>x.closest('div.group.cursor-pointer,div.cursor-pointer'))
          .find(Boolean);
        if(!card)return false;
        card.click();return true;
      },target.episode);
      if(!ep){
        console.log('CINELITE_WATCH_ROUTE '+JSON.stringify({
          kind:target.kind,found:true,episodeFound:false
        }));
        continue;
      }
    }else{
      // Movie overlay's play control, never a guessed player URL.
      await page.evaluate(()=>{
        const b=[...document.querySelectorAll('button,[role=button]')]
          .find(e=>/^(?:play|watch now|watch|مشاهدة)$/i.test(e.textContent.trim()));
        b?.click();
      });
    }
    await page.waitForTimeout(2500);
    const state=await page.evaluate(()=>{
      const urls=[location.href,...[...document.querySelectorAll('iframe[src]')]
        .map(f=>f.src)];
      const safe=urls.map(raw=>{
        try {
          const u=new URL(raw);
          if(!['cinelite.xyz','embed.cinelite.xyz'].includes(u.hostname))return null;
          return u.hostname+u.pathname;
        }catch{return null}
      }).filter(Boolean);
      const text=document.body.innerText||'';
      const count=Number(text.match(/(\d+)\s+Agents/i)?.[1]||0);
      return {paths:safe.slice(0,4),agentCount:count,
        sourceMenu: /Stream Sources/.test(text)};
    });
    console.log('CINELITE_WATCH_ROUTE '+JSON.stringify({
      kind:target.kind,found:true,...state
    }));
   }catch(e){
    console.log('CINELITE_WATCH_ROUTE_ERROR '+JSON.stringify({
      kind:target.kind,message:e.message.split('\n')[0].slice(0,140)
    }));
   }finally{await page.close()}
  }
 }finally{await browser.close()}
})().catch(e=>{console.error('WATCH_ROUTE_DIAGNOSTIC_ERROR',e.message);process.exitCode=1});
