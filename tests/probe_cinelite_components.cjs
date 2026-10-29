/* Read-only diagnostic of public React card metadata and documented embed player. */
const { chromium } = require('playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const page=await browser.newPage();
 function script() { return () => {
   const el=[...document.querySelectorAll('h3')].find(x=>x.textContent?.includes("Grey's Anatomy")) || document.querySelector('h3');
   const key=el && Object.keys(el).find(k=>k.startsWith('__reactFiber'));
   let node=key?el[key]:null;const levels=[];
   for(let i=0;node && i<16;i++,node=node.return) {
     const p=node.memoizedProps||{};
     const found=[];
     for(const [k,v] of Object.entries(p)){
       if(!v || typeof v!=='object'||Array.isArray(v)) continue;
       if ('id' in v && ('title' in v||'name' in v||'original_name' in v)) {
         found.push({prop:k,id:v.id,title:v.title,name:v.name,media_type:v.media_type,
           first_air_date:v.first_air_date});
       }
     }
     levels.push({i,name:String(node.elementType?.name||node.type?.name||node.elementType||'').slice(0,70),
       keys:Object.keys(p).slice(0,20),found});
   }
   const elementHtml=el?.parentElement?.parentElement?.outerHTML.slice(0,2600);
   return {key,levels,elementHtml};
 }; }
 try {
   await page.goto('https://cinelite.xyz/',{waitUntil:'domcontentloaded',timeout:35000});
   await page.waitForTimeout(5500);
   console.log('CARD_REACT_FIBER '+JSON.stringify(await page.evaluate(script()),null,2));
   try {
     await page.getByText("Grey's Anatomy",{exact:true}).first().click({timeout:5500});
     await page.waitForTimeout(2200);
     const overlay=await page.evaluate(()=>{
       const elm=[...document.querySelectorAll('button')].find(x=>x.textContent?.includes('Season 1'));
       const episode=[...document.querySelectorAll('div,button')].find(x=>x.textContent?.trim()==='Episode 1');
       return {button:elm?.parentElement?.outerHTML.slice(0,1800),
         episode:episode?.parentElement?.outerHTML.slice(0,1700),
         iframe:[...document.querySelectorAll('iframe')].map(f=>new URL(f.src).origin).slice(0,5),
         text:document.body.innerText.slice(0,1300)};
     });
     console.log('DETAIL_OVERLAY '+JSON.stringify(overlay,null,2));
   }catch(e){console.log('DETAIL_ERROR '+e.message);}
   try {
     await page.getByText('Search',{exact:true}).first().click({timeout:4000});
     await page.waitForTimeout(700);
     console.log('SEARCH_UI '+JSON.stringify(await page.evaluate(()=>({
       inputs:[...document.querySelectorAll('input')].map(x=>({type:x.type,placeholder:x.placeholder,outer:x.outerHTML.slice(0,350)})),
       text:document.body.innerText.slice(0,700)
     })),null,2));
   }catch(e){console.log('SEARCH_ERROR '+e.message);}
   const player=await browser.newPage();
   const observed=new Set();
   player.on('request',r=>{try{let u=new URL(r.url());if(u.hostname.endsWith('cinelite.xyz'))observed.add(u.hostname+u.pathname.slice(0,65).replace(/[0-9]{3,}/g,'{id}'));}catch(_){}});
   try {
     await player.goto('https://embed.cinelite.xyz/550',{waitUntil:'domcontentloaded',timeout:22000});
     await player.waitForTimeout(4400);
     const observedDom=await player.evaluate(()=>({
       title:document.title,
       text:document.body.innerText.slice(0,2800),
       headings:[...document.querySelectorAll('h1,h2,h3')].map(e=>e.textContent.trim()).slice(0,15),
       buttons:[...document.querySelectorAll('button,[role=button]')].map(e=>e.textContent.trim().slice(0,75)).filter(Boolean).slice(0,55),
       frames:[...document.querySelectorAll('iframe')].map(e=>({hostname:new URL(e.src).hostname,title:e.title})).slice(0,12),
       videoCount:document.querySelectorAll('video').length
     }));
     console.log('EMBED_MOVIE '+JSON.stringify({dom:observedDom,requests:[...observed].slice(0,45)},null,2));
   }catch(e){console.log('EMBED_ERROR '+e.message);}
   await player.close();
 }catch(e){console.log('MAIN_ERROR '+e.message);}
 finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1});
