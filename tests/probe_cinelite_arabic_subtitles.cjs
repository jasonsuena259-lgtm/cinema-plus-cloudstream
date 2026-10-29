/* Diagnostic of real Arabic subtitle choices. Never log media URLs or tokens. */
const {chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 try{
   const page=await browser.newPage();
   const req=new Map();
   page.on('response',res=>{
     try{
       const u=new URL(res.url()),p=u.pathname.toLowerCase(),ctype=res.headers()['content-type']||'';
       if(!/subtitle|caption|\/subs\/|\.vtt|\.srt|\.ass|\.ssa|\.m3u8/.test(p+ctype))return;
       const safePath=p.replace(/\d+/g,'{id}').slice(0,100);
       const key=u.hostname+safePath+' '+res.status();
       if(req.size<50)req.set(key,{domain:u.hostname,path:safePath,status:res.status(),type:res.request().resourceType(),mime:ctype.split(';')[0]});
     }catch(_){}
   });
   await page.goto('https://embed.cinelite.xyz/anime/195539/1',{waitUntil:'domcontentloaded',timeout:35000});
   await page.waitForTimeout(2300);
   await page.evaluate(()=>{
     const b=[...document.querySelectorAll('button,[role=button]')].find(e=>/audio\s*&\s*subs/i.test(e.textContent||''));
     b?.click();
   });
   await page.waitForTimeout(350);
   const options=await page.evaluate(()=>{
     const results=[];
     for(const e of document.querySelectorAll('*')){
       if(e.children.length>1||e.getClientRects().length===0)continue;
       if(!/^(?:🇸🇦)?Arabic$/i.test(e.textContent.trim()))continue;
       const row=e.closest('button,[role=option],[role=menuitem],[class*=cursor-pointer]')||e.parentElement;
       const keys=Object.keys(e).filter(k=>k.startsWith('__reactFiber'));
       results.push({
         ownTag:e.tagName,outerClass:String(e.className).slice(0,100),
         rowTag:row?.tagName,rowClass:String(row?.className).slice(0,160),
         rowLabel:row?.textContent.trim().replace(/\s+/g,' ').slice(0,100),
         dataKeys:row?[...row.attributes].map(a=>a.name).slice(0,20):[],
         hasFiber:keys.length>0
       });
       if(results.length>8)break;
     }
     return results;
   });
   console.log('ARABIC_SUBTITLE_ROWS '+JSON.stringify(options));
   const selected=await page.evaluate(()=>{
     const targets=[...document.querySelectorAll('*')].filter(e=>e.children.length===0&&
       /^(?:🇸🇦)?Arabic$/i.test(e.textContent.trim())&&e.getClientRects().length>0);
     const row=targets[0]?.closest('button,[role=option],[role=menuitem],[class*=cursor-pointer]')||targets[0]?.parentElement;
     if(row){row.click();return true}return false;
   });
   await page.waitForTimeout(1650);
   const after=await page.evaluate(()=>({
     text:document.body.innerText.slice(-480).replace(/https?:\/\/[^\s]+/g,'[URL]'),
     tracks:[...document.querySelectorAll('video')].map(v=>({tracks:v.textTracks?.length||0,mode:[...v.textTracks||[]].map(t=>t.mode)}))
   }));
   console.log('ARABIC_SUBTITLE_AFTER_SELECT '+JSON.stringify({selected,after,resources:[...req.values()]}));
 }finally{await browser.close();}
})().catch(e=>{console.error('ARABIC_SUBTITLE_AUDIT_ERROR '+e.message.slice(0,180));process.exitCode=1});
