/* Live read-only audit of diverse CineLite TV cards and real episode carousels.
   No media URLs, credentials, proxy tokens, or downloaded episodes are recorded. */
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const shows=['VisionQuest',"Grey's Anatomy",'WandaVision','Loki'];
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  try{
    for(const title of shows){
      const page=await browser.newPage();
      try{
        await page.goto('https://cinelite.xyz/search',{waitUntil:'domcontentloaded',timeout:35000});
        await page.locator('input[type=text]').first().fill(title);
        await page.waitForTimeout(2500);
        const cards=page.locator('h3').filter({hasText:new RegExp('^'+title.replace(/[^a-z0-9]/gi,'.')+'$','i')});
        const info=await cards.first().evaluate(el=>{
          let f=el[Object.keys(el).find(k=>k.startsWith('__reactFiber'))],id=null;
          for(let i=0;f&&i<14;i++,f=f.return)if(f.memoizedProps?.item?.media_type==='tv'){
            id=f.memoizedProps.item.id;break;
          }
          return {id,heading:el.textContent.trim()};
        }).catch(()=>({id:null}));
        if(info.id){
          await cards.first().click({timeout:5000});
          await page.waitForTimeout(1700);
          const data=await page.evaluate(()=>{
            const labels=[...document.querySelectorAll('span')].filter(e=>/^EPISODE\s+\d+$/i.test(e.textContent.trim()));
            const discovered=labels.map(span=>{
              const card=span.closest('div.group.cursor-pointer')||span.closest('div[class*="cursor-pointer"]');
              return {number:Number(span.textContent.match(/\d+/)?.[0]||0),
                title:card?.querySelector('h4,h3')?.textContent?.trim()||''};
            }).filter(e=>e.number>0&&e.title);
            const unique=[...new Map(discovered.map(e=>[e.number,e])).values()];
            const seasonLabels=[...document.querySelectorAll('button')].map(x=>x.textContent.trim())
              .filter(t=>/^Season\s+\d+$/i.test(t));
            return {episodeCount:unique.length,seasonLabels:seasonLabels.slice(0,10),
              samples:unique.slice(0,3)};
          });
          console.log('CINELITE_TV_AUDIT '+JSON.stringify({show:title,id:info.id,...data}));
          if(title==='VisionQuest')assert.ok(data.episodeCount>=1,
            'VisionQuest is shown in search but its live episode carousel was empty');
        } else console.log('CINELITE_TV_AUDIT '+JSON.stringify({show:title,cardUnavailable:true}));
      }catch(e){console.log('CINELITE_TV_AUDIT_ERROR '+title+': '+e.message.slice(0,180));if(title==='VisionQuest')throw e}
      finally{await page.close();}
    }
  }finally{await browser.close();}
})().catch(e=>{console.error('TV_AUDIT_FAILED',e.message);process.exitCode=1});
