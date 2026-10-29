/* Read-only regression probe. Avoid storing media URLs, credentials or cookies. */
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({headless:true,args:['--no-sandbox']});
  const page = await browser.newPage();
  try {
    await page.goto('https://cinelite.xyz/search',{waitUntil:'domcontentloaded',timeout:40000});
    const input=page.locator('input[type="text"]').first();
    await input.fill('VisionQuest');
    await page.waitForTimeout(3300);
    const search=await page.evaluate(()=>{
      const titles=[...document.querySelectorAll('h3')].map(e=>e.textContent.trim()).filter(Boolean);
      const h=[...document.querySelectorAll('h3')].find(e=>/^vision\s*quest$/i.test(e.textContent.trim()));
      const k=h&&Object.keys(h).find(x=>x.startsWith('__reactFiber'));
      let f=k?h[k]:null,item=null;
      for(let i=0;i<14&&f;i++,f=f.return){
        if(f.memoizedProps?.item?.media_type==='tv'){item=f.memoizedProps.item;break}
      }
      return {matches:titles.filter(t=>/vision\s*quest/i.test(t)).slice(0,12),
        found:!!h,identity:item&&{id:item.id,type:item.media_type,name:item.name||item.title},
        cards:titles.length};
    });
    console.log('VISIONQUEST_SEARCH',JSON.stringify(search));
    const card=page.locator('h3').filter({hasText:/^Vision\s*Quest$/i}).first();
    if (await card.count()) {
      await card.click({timeout:10000});
      await page.waitForTimeout(2200);
      const detail=await page.evaluate(()=>{
        const labels=[...document.querySelectorAll('span')].filter(x=>/^EPISODE\s*\d+$/i.test(x.textContent.trim()));
        const examples=labels.slice(0,5).map(x=>({
          number:x.textContent.trim(),parentClass:x.parentElement?.parentElement?.className?.slice(0,140),
          h4:x.closest('div.group')?.querySelector('h4')?.textContent?.trim()||'',
          hasAdTrigger:!!x.closest('div.ad-trigger-play')
        }));
        const seasons=[...document.querySelectorAll('button')].map(x=>x.textContent.trim())
          .filter(t=>/^Season\s*\d+$/i.test(t));
        const text=document.body.innerText;
        return {episodeLabels:labels.length,examples,seasons:seasons.slice(0,35),
          visibleNoEpisodes:/no episodes|coming soon|no seasons/i.test(text),
          detailText:text.slice(-1300)};
      });
      console.log('VISIONQUEST_DETAIL',JSON.stringify(detail));
    }
  } catch(e){console.error('VISIONQUEST_PROBE_ERROR',e.message.slice(0,180));}
  finally {await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1});
