/* Live player subtitle controls audit. NEVER print stream/subtitle URLs, tokens, or cookies. */
const {chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 try{
  for(const route of ['/550','/1399/1/1','/anime/195539/1']){
   const page=await browser.newPage();
   try{
     await page.goto('https://embed.cinelite.xyz'+route,{waitUntil:'domcontentloaded',timeout:35000});
     await page.waitForTimeout(2300);
     const opened=await page.evaluate(()=>{
       const controls=[...document.querySelectorAll('button,[role=button]')];
       const control=controls.find(b=>/audio\s*&\s*subs|subtitles|captions/i.test((b.textContent||'').trim()));
       if(control){control.click();return true}
       return false;
     });
     await page.waitForTimeout(500);
     const data=await page.evaluate(()=>{
       const relevant=[...document.querySelectorAll('button,span,p,[role=option],track')]
         .map(e=>(e.getAttribute?.('label')||e.getAttribute?.('srclang')||
           e.textContent||'').trim().replace(/\s+/g,' ').slice(0,70))
         .filter(x=>x&&/(Arabic|عرب|subtitle|caption|English|reyna|dub|sub|audio|no subtitles|language|none)/i.test(x))
         .slice(0,28);
       const tracks=[...document.querySelectorAll('track[src]')].map(t=>({
         lang:t.getAttribute('srclang')||'',kind:t.kind,label:t.label
       }));
       const directSubtitles=[...document.querySelectorAll('a[href]')].filter(a=>{
         try{return /\.(srt|vtt|ass|ssa)$/i.test(new URL(a.href).pathname)}
         catch(e){return false}
       }).length;
       return {labels:relevant,tracks,directSubtitles};
     });
     console.log('CINELITE_SUBTITLES_AUDIT '+JSON.stringify({route,opened,...data}));
   }catch(e){console.log('CINELITE_SUBTITLES_ERROR '+route+' '+e.message.slice(0,120))}
   finally{await page.close()}
  }
 }finally{await browser.close()}
})().catch(e=>{console.error('SUBTITLE_AUDIT_FAILED',e.message);process.exitCode=1});
