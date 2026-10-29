/* Live /anime React render probe: no tokens, cookies, media URLs or personal data. */
const {chromium}=require("playwright");
(async()=>{
 const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
 try{
  for(const path of ["/anime","/discover","/"]){
   const page=await browser.newPage();
   try{
    await page.goto("https://cinelite.xyz"+path,{waitUntil:"domcontentloaded",timeout:40000});
    for(const pause of [2200,4000]){
     await page.waitForTimeout(pause);
     const data=await page.evaluate(()=>{
      const walk=(el)=>{
       const key=Object.keys(el).find(k=>k.startsWith("__reactFiber"));
       let f=key?el[key]:null;
       for(let i=0;f&&i<14;i++,f=f.return){
        const v=f.memoizedProps?.item;
        if(Number.isInteger(v?.id)&&v.id>0&&["movie","tv"].includes(v.media_type))
         return {id:v.id,type:v.media_type};
       }
       return null;
      };
      const tally={};
      const cards=[...document.querySelectorAll("h2,h3,h4,a[href],div.cursor-pointer,div[class*=group]")].map(e=>{
       const v=walk(e);if(!v)return null;
       const label=e.textContent?.trim().replace(/\s+/g," ").slice(0,60);
       if(!label||label.length>110)return null;
       return {tag:e.tagName,label,id:v.id,type:v.type,class:String(e.className).slice(0,45)};
      }).filter(Boolean);
      const unique=[...new Map(cards.map(x=>[x.type+":"+x.id,x])).values()];
      return {title:document.title,headings:[...document.querySelectorAll("h1,h2,h3,h4")].slice(0,25).map(x=>({tag:x.tagName,title:x.textContent.trim().slice(0,50)})),counts:{h3:document.querySelectorAll("h3").length,h4:document.querySelectorAll("h4").length,slider:document.querySelectorAll(".slider-track h3").length,cursor:document.querySelectorAll(".cursor-pointer").length},unique:unique.slice(0,18),uniqueCount:unique.length,body:document.body.innerText.slice(0,320)};
     });
     console.log("ANIME_RENDER_AUDIT "+JSON.stringify({path,elapsed:pause,data}));
     if(path==="/anime"&&pause===2200){
       const internals=await page.evaluate(()=>{
         const title=[...document.querySelectorAll('h3')].find(h=>/Cyberpunk|ONE PIECE/i.test(h.textContent||''));
         const card=title?.closest('div.cursor-pointer');
         const inspected=[];
         for(const [elementName,e] of [['h3',title],['card',card],['parent',card?.parentElement]]){
           if(!e)continue;
           const k=Object.keys(e).find(key=>key.startsWith('__reactFiber'));
           let f=k?e[k]:null;
           const traces=[];
           for(let depth=0;f&&depth<16;depth++,f=f.return){
             const p=f.memoizedProps;
             const ks=p&&typeof p==='object'?Object.keys(p).slice(0,16):[];
             const nested=[];
             for(const key of ks){
               const v=p[key];
               if(v&&typeof v==='object'&&!Array.isArray(v)){
                 const fields=Object.keys(v).slice(0,18);
                 const id=Number.isInteger(v.id)?v.id:undefined;
                 nested.push({key,fields,id,media_type:typeof v.media_type==='string'?v.media_type:undefined});
               }
             }
             traces.push({depth,props:ks,nested:nested.slice(0,5)});
           }
           inspected.push({elementName,hasFiber:!!k,html:e.outerHTML.slice(0,900),traces});
         }
         return inspected;
       });
       console.log('ANIME_FIBER_STRUCTURE '+JSON.stringify(internals));
       const sliderItems=await page.evaluate(()=>{
         const slider=document.querySelector('.slider-track');
         if(!slider)return null;
         const k=Object.keys(slider).find(k=>k.startsWith('__reactFiber'));
         let f=k?slider[k]:null,items=null;
         for(let i=0;i<12&&f;i++,f=f.return)
           if(Array.isArray(f.memoizedProps?.items)){items=f.memoizedProps.items;break}
         return {length:items?.length||0,first:items?.slice(0,7).map(v=>({
           id:v.id,fields:Object.keys(v).slice(0,25),type:v.type,media_type:v.media_type,
           title:typeof v.title==='string'?v.title:v.title?.english||v.title?.romaji||v.name,
           banner:v.bannerImage||v.banner?.slice?.(0,100),format:v.format
         }))};
       });
       console.log('ANIME_ITEMS_SHAPE '+JSON.stringify(sliderItems));
     }
    }
   }catch(e){console.log("ANIME_RENDER_ERROR "+JSON.stringify({path,error:e.message.slice(0,130)}))}
   finally{await page.close();}
  }
 }finally{await browser.close()}
})().catch(e=>{console.error("AUDIT_FAILED",e.message);process.exitCode=1});
