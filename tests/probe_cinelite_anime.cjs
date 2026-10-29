/* Read-only diagnostics of publicly rendered CineLite anime routes and server UI.
   Never print playback URLs, session tokens, cookie values or API bodies. */
const {chromium}=require('playwright');
const allow=['cinelite.xyz','embed.cinelite.xyz','api.mycinebackendfuckoff.site','api.themoviedb.org'];
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox','--autoplay-policy=no-user-gesture-required']});
  try {
    for(const path of ['/anime','/watch/anime/195539/1']){
      const page=await browser.newPage();
      const requests=new Map();
      page.on('response',res=>{
        try {const u=new URL(res.url());
          if(!allow.some(host=>u.hostname===host))return;
          const endpoint=u.pathname.replace(/\d+/g,'{id}').slice(0,100);
          const k=u.hostname+endpoint+' '+res.status();
          if(requests.size<50&&!requests.has(k)) requests.set(k,{
            host:u.hostname,endpoint,status:res.status(),
            type:res.request().resourceType(),mime:(res.headers()['content-type']||'').split(';')[0]
          });
        }catch(_){}
      });
      try{
        await page.goto('https://cinelite.xyz'+path,{waitUntil:'domcontentloaded',timeout:45000});
        await page.waitForTimeout(6000);
        const data=await page.evaluate(()=>{
          const buttons=[...document.querySelectorAll('button')].map(e=>e.innerText.trim().replace(/\s+/g,' ').slice(0,85)).filter(Boolean);
          const titles=[...document.querySelectorAll('h1,h2,h3')].map(e=>e.innerText.trim().slice(0,100)).filter(Boolean);
          const links=[...document.querySelectorAll('a[href]')].filter(e=>/anime|episode|season/i.test(e.innerText||'')).slice(0,12).map(e=>({
            label:e.innerText.trim().slice(0,45),path:(()=>{try{return new URL(e.href).pathname}catch{return ''}})()
          }));
          const videos=[...document.querySelectorAll('video')].map(v=>({readyState:v.readyState,hasSrc:!!v.currentSrc,tracks:v.textTracks?.length||0}));
          const tracks=[...document.querySelectorAll('track')].map(e=>({kind:e.kind,label:e.label,lang:e.srclang,hasSrc:!!e.src}));
          const iframe=[...document.querySelectorAll('iframe')].map(e=>{try{return new URL(e.src).hostname}catch{return ''}}).slice(0,10);
          const cursor=[...document.querySelectorAll('[class*=cursor-pointer]')].filter(e=>e.querySelector('h3')).length;
          return {title:document.title,titles:titles.slice(0,22),buttons:buttons.slice(0,60),links,videos,tracks,iframe,cursor,
            body:document.body.innerText.slice(0,1250)};
        });
        console.log('CINELITE_ROUTE '+JSON.stringify({path, ...data,requests:[...requests.values()]},null,2));
        if(path.includes('/watch/')){
          const menu=page.getByText('Reyna Sub',{exact:true}).first();
          try{await menu.click({timeout:3000});await page.waitForTimeout(1300);}catch(_){}
          const after=await page.evaluate(()=>({
            buttons:[...document.querySelectorAll('button')].map(x=>x.innerText.trim()).filter(Boolean).slice(0,45),
            snippet:document.body.innerText.slice(-1150),
            media:[...document.querySelectorAll('video')].map(v=>({readyState:v.readyState,hasSrc:!!v.currentSrc,trackCount:v.textTracks?.length||0})),
            tracks:[...document.querySelectorAll('track')].map(t=>({label:t.label,kind:t.kind,lang:t.srclang}))
          }));
          console.log('ANIME_SERVER_AFTER_CLICK '+JSON.stringify({after,requests:[...requests.values()]},null,2));
        }
      }catch(e){console.log('CINELITE_ROUTE_ERROR '+path+' '+e.message.slice(0,220));}
      await page.close();
    }
  } finally {await browser.close()}
})().catch(e=>{console.error(e.message);process.exitCode=1});
