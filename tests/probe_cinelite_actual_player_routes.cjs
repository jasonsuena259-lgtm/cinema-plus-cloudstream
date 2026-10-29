/* Read-only real episode/player route check. No signed URLs, cookies, tokens, or request bodies. */
const {chromium}=require('playwright');
async function snapshot(page,tag){
 const data=await page.evaluate(()=>{
  let host=p=>{try{return new URL(p,location.href).hostname}catch{return ''}};
  let path=p=>{try{return new URL(p,location.href).pathname}catch{return ''}};
  return {
   path:location.pathname,
   title:document.title,
   iframe:[...document.querySelectorAll('iframe[src]')].slice(0,6).map(x=>({host:host(x.src),path:path(x.src)})),
   episodes:[...document.querySelectorAll('span')].filter(x=>/^EPISODE\s+\d+$/i.test(x.textContent.trim())).length,
   controls:[...document.querySelectorAll('button')].map(x=>x.textContent.trim()).filter(x=>/source|server|clove|reyna|neon|chamber|season/i.test(x)).slice(0,20),
   video:[...document.querySelectorAll('video')].map(v=>({hasSrc:!!v.currentSrc,readyState:v.readyState}))
  }
 });
 console.log(tag+' '+JSON.stringify(data));
}
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--autoplay-policy=no-user-gesture-required']});
 try {
  const cases=[
    {type:'tv',name:'VisionQuest',id:213375},
    {type:'tv',name:"Grey's Anatomy",id:1416},
    {type:'movie',name:'Fight Club',id:550},
    {type:'anime',name:'Cyberpunk: Edgerunners 2',id:195539}
  ];
  for(const c of cases){
   const page=await browser.newPage();
   const network=new Map();
   page.on('response',r=>{
    try{
     let u=new URL(r.url());
     if(!['cinelite.xyz','embed.cinelite.xyz','api.mycinebackendfuckoff.site'].includes(u.hostname))return;
     let k=u.hostname+u.pathname.replace(/\d{3,}/g,'{id}')+':'+r.status();
     if(network.size<25)network.set(k,{host:u.hostname,path:u.pathname.replace(/\d{3,}/g,'{id}'),status:r.status()});
    }catch{}
   });
   try {
    await page.goto('https://cinelite.xyz/search',{waitUntil:'domcontentloaded',timeout:35000});
    await page.locator('input[type=text]').first().fill(c.name);
    await page.waitForTimeout(1700);
    const card=page.locator('h3').filter({hasText:new RegExp('^'+c.name.replace(/[^a-z0-9]/gi,'.')+'$','i')}).first();
    if(!await card.count()){console.log('CASE_UNAVAILABLE '+c.name);continue}
    const id=await card.evaluate(h=>{let f=h[Object.keys(h).find(k=>k.startsWith('__reactFiber'))];for(let i=0;f&&i<14;i++,f=f.return)if(f.memoizedProps?.item?.id)return f.memoizedProps.item.id;return 0;});
    if(id!==c.id){console.log('CASE_MISMATCH '+JSON.stringify({name:c.name,expected:c.id,actual:id}));continue;}
    await card.click();
    await page.waitForTimeout(1750);
    await snapshot(page,'CASE_DETAILS_'+c.type);
    if(c.type!=='movie'){
      const epi=page.locator('span').filter({hasText:/^EPISODE 1$/i}).first();
      if(await epi.count()) {
        const group=epi.locator('xpath=ancestor::div[contains(@class,"cursor-pointer")][1]');
        await group.click({timeout:4500});
        await page.waitForTimeout(2400);
        await snapshot(page,'CASE_EPISODE_'+c.type);
      }else console.log('CASE_NO_EPISODE '+c.name);
    } else {
      const play=page.getByRole('button',{name:/^play$/i}).first();
      if(await play.count()){await play.click({timeout:5000});await page.waitForTimeout(1700);await snapshot(page,'CASE_MOVIE_PLAY');}
    }
    console.log('CASE_ENDPOINTS_'+c.type+' '+JSON.stringify([...network.values()]));
   } catch(e){console.log('CASE_ERROR_'+c.type+' '+e.message.slice(0,150))}
   finally{await page.close()}
  }
 } finally {await browser.close()}
})().catch(e=>{console.error('FATAL '+e.message);process.exitCode=1});
