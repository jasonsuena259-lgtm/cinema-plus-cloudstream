/* Non-destructive check of ONE PIECE real CineLite anime UI.
   Never log network media URLs, authentication tokens, cookies or subtitles. */
const {chromium}=require('playwright');
(async()=>{
 const b=await chromium.launch({headless:true,args:['--no-sandbox']});
 try{
  for(const route of ['/anime','/']){
   const p=await b.newPage();
   try{
    await p.goto('https://cinelite.xyz'+route,{waitUntil:'domcontentloaded',timeout:40000});
    await p.waitForTimeout(3000);
    const initial=await p.evaluate(()=>{
      const h=[...document.querySelectorAll('h3,h4')].filter(x=>/^(ONE PIECE|One Piece)$/i.test(x.textContent.trim()));
      return {count:h.length,card:h.slice(0,3).map(x=>({tag:x.tagName,parents:[x.parentElement?.className,x.parentElement?.parentElement?.className,x.closest('.cursor-pointer')?.className].map(y=>String(y||'').slice(0,120))})),headings:[...document.querySelectorAll('h2')].slice(0,18).map(x=>x.textContent.trim().slice(0,45))};
    });
    console.log('ONE_PIECE_INITIAL '+JSON.stringify({route,initial}));
    const title=p.locator('h3,h4').filter({hasText:/^ONE PIECE$/i}).first();
    if(await title.count()){
      await title.evaluate(el=>{(el.closest('.cursor-pointer')||el.closest('[class*=cursor-pointer]')||el).click()});
      await p.waitForTimeout(2200);
      let details=await p.evaluate(()=>{
        const body=document.body.innerText;
        const label=[...document.querySelectorAll('button,[role=button]')].map(x=>x.textContent.trim().replace(/\s+/g,' ').slice(0,80)).filter(Boolean).slice(0,80);
        const eps=[...document.querySelectorAll('span')].filter(x=>/^EPISODE\s*\d+$/i.test(x.textContent.trim())).map(x=>x.textContent.trim()).slice(0,18);
        const clickable=[...document.querySelectorAll('button,[role=button]')].filter(x=>/episodes|season|play ep|sub|dub/i.test(x.textContent.trim())).map(x=>({text:x.textContent.trim().slice(0,80),class:String(x.className).slice(0,120)})).slice(0,25);
        return {path:location.pathname,buttons:label,episodeMarkers:eps,clickable,tail:body.slice(0,1600)};
      });
      console.log('ONE_PIECE_MODAL '+JSON.stringify({route,details}));
      const epBtn=p.getByRole('button',{name:/Play Ep\s*1/i}).first();
      if(await epBtn.count()){
        await epBtn.evaluate(e=>e.click());
        await p.waitForTimeout(1800);
        console.log('ONE_PIECE_WATCH '+JSON.stringify(await p.evaluate(()=>({
          path:location.pathname,
          title:document.title.slice(0,60),
          videoCount:document.querySelectorAll('video').length,
          buttons:[...document.querySelectorAll('button')].map(e=>e.textContent.trim()).filter(Boolean).slice(0,16)
        }))));
      }
    }
   }catch(e){console.log('ONE_PIECE_PROBE_ERROR '+JSON.stringify({route,error:e.message.slice(0,180)}))}
   finally{await p.close()}
  }
 }finally{await b.close()}
})().catch(e=>{console.error('ONE_PIECE_FATAL',e.message);process.exitCode=1});
