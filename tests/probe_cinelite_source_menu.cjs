/* CineLite source-menu diagnostic: no playback URL, tokens, cookies or API bodies logged. */
const { chromium }=require('playwright');
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  try {
    for(const route of ['/550','/1399/1/1','/anime/195539/1']){
      const page=await browser.newPage();
      try {
        await page.goto('https://embed.cinelite.xyz'+route,{waitUntil:'domcontentloaded',timeout:40000});
        await page.waitForTimeout(2300);
        const before=await page.evaluate(()=>[...document.querySelectorAll('button')].map(e=>({t:e.textContent.trim().slice(0,50),class:e.className.slice(0,90),visible:e.getClientRects().length>0})).filter(x=>x.t).slice(0,35));
        console.log('SOURCE_MENU_BEFORE',JSON.stringify({route,buttons:before}));
        await page.evaluate(()=>{
          const buttons=[...document.querySelectorAll('button')];
          const targets=buttons.filter(e=>/^(Clove|Reyna Sub|Chamber Sub|Neon Sub)$/i.test(e.textContent.trim()));
          if(targets.length)targets[targets.length-1].click();
        });
        await page.waitForTimeout(650);
        const modal=await page.evaluate(()=>{
          const data=[...document.querySelectorAll('button,[role=option],[role=menuitem],[class*=cursor-pointer]')]
            .filter(e=>e.getClientRects().length>0)
            .map(e=>({tag:e.tagName,role:e.getAttribute('role'),t:(e.textContent||'').trim().replace(/\s+/g,' ').slice(0,70),class:String(e.className).slice(0,120)}))
            .filter(e=>e.t.length>0&&e.t.length<80).slice(-65);
          const text=document.body.innerText.slice(-1800);
          return {options:data,lastText:text};
        });
        console.log('SOURCE_MENU_OPEN',JSON.stringify({route,menu:modal}));
        if(route.startsWith('/anime/')){
          const switched=await page.evaluate(()=>{
            const row=[...document.querySelectorAll('div.group.cursor-pointer')]
              .find(e=>e.textContent.includes('Chamber Sub'));
            if(!row)return {selected:false};
            row.click();
            return {selected:true};
          });
          await page.waitForTimeout(800);
          const state=await page.evaluate(()=>{
            const current=[...document.querySelectorAll('button')].filter(b=>
              /^(Reyna Sub|Reyna Dub|Chamber Sub|Chamber Dub|Neon Sub|Neon Dub)$/.test(b.textContent.trim())
            ).map(b=>b.textContent.trim());
            return {buttons:current.slice(0,5),menuStillOpen:document.body.innerText.includes('Stream Sources')};
          });
          console.log('SOURCE_MENU_SWITCH',JSON.stringify({route,clicked:switched.selected,state}));
        }
      }catch(e){console.log('SOURCE_MENU_ERROR',JSON.stringify({route,error:e.message.slice(0,150)}));}
      finally{await page.close();}
    }
  }finally{await browser.close();}
})().catch(e=>{console.error('PROBE_FAILED',e.message);process.exitCode=1});
