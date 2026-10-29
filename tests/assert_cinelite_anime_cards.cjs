/* Repeat Android snapshot's React annotations on the live Anime route.
   Reads public media metadata only, no media URLs or tokens. */
const {chromium}=require('playwright');const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 try{
  const page=await browser.newPage();
  await page.goto('https://cinelite.xyz/anime',{waitUntil:'domcontentloaded',timeout:40000});
  await page.waitForFunction(()=>document.querySelectorAll('.slider-track h3').length>5,{timeout:20000});
  await page.evaluate(()=>{
    const valid=x=>x&&Number.isInteger(x.id)&&x.id>0&&['movie','tv','anime'].includes(x.media_type);
    const norm=x=>String(x||'').trim().toLowerCase().replace(/\s+/g,' ');
    for(const slider of document.querySelectorAll('.slider-track')){
      const key=Object.keys(slider).find(x=>x.startsWith('__reactFiber'));
      let f=key?slider[key]:null,items=null;
      for(let depth=0;f&&depth<14;depth++,f=f.return)
        if(Array.isArray(f.memoizedProps?.items)){items=f.memoizedProps.items;break}
      if(!items?.length)continue;
      for(const card of slider.children){
        if(!card.querySelector('h3,h4'))continue;
        const label=norm(card.querySelector('h3,h4')?.textContent);
        const matches=items.filter(x=>valid(x)&&[
          x.title,x.name,x.displayTitle,x.titleEnglish,x.titleRomaji,x.titleUserPreferred
        ].some(name=>norm(name)===label));
        if(matches.length===1){
          card.setAttribute('data-cinelite-id',String(matches[0].id));
          card.setAttribute('data-cinelite-type',matches[0].media_type);
        }
      }
    }
  });
  const cards=await page.evaluate(()=>[...document.querySelectorAll('[data-cinelite-id][data-cinelite-type]')].map(x=>({
    id:Number(x.getAttribute('data-cinelite-id')),
    type:x.getAttribute('data-cinelite-type'),
    title:x.querySelector('h3,h4')?.textContent.trim()
  })).filter(x=>x.id&&x.title));
  const unique=[...new Map(cards.map(x=>[x.type+':'+x.id,x])).values()];
  console.log('VERIFIED_ANIME_CARDS',JSON.stringify({cards:cards.length,unique:unique.length,sample:unique.slice(0,8)}));
  assert.ok(unique.length>=8,'Live Anime page should render at least 8 real AniList card IDs');
  assert.ok(unique.some(x=>x.id===195539&&x.type==='anime'),'Expected AniList ID 195539 from real Anime page');
 }finally{await browser.close();}
})().catch(e=>{console.error('ANIME_CARD_VALIDATION_FAILED',e.message);process.exitCode=1});
