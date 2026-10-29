const {chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--autoplay-policy=no-user-gesture-required']});
 for(const embed of ['https://embed.cinelite.xyz/550','https://embed.cinelite.xyz/1399/1/1']){
  const page=await browser.newPage();
  const requests=[];
  page.on('response',r=>{
   try{
    const u=new URL(r.url());
    if(!['script','stylesheet','image','font'].includes(r.request().resourceType())){
     const record={host:u.hostname,path:u.pathname.slice(0,90).replace(/\/[0-9]{4,}(?:\/|$)/g,'/{id}/'),
       type:r.request().resourceType(),status:r.status(),mime:r.headers()['content-type']?.split(';')[0]||''};
     if(requests.length<95 && !requests.find(v=>JSON.stringify(v)===JSON.stringify(record)))requests.push(record);
    }
   }catch(_){}
  });
  try{
    await page.goto(embed,{waitUntil:'domcontentloaded',timeout:30000});await page.waitForTimeout(3200);
    console.log('PLAYER_PRE '+JSON.stringify({embed,buttons:await page.locator('button').evaluateAll(
      arr=>arr.map(x=>x.innerText?.trim()).filter(Boolean).slice(0,45)), videos:await page.evaluate(()=>
      [...document.querySelectorAll('video')].map(v=>({readyState:v.readyState,networkState:v.networkState,
      paused:v.paused,hasSrc:!!v.src,hasCurrentSrc:!!v.currentSrc})))},null,2));
    try {await page.getByRole('button',{name:'Clove'}).last().click({timeout:2200});await page.waitForTimeout(450);}catch(_){}
    console.log('PLAYER_SERVERS '+JSON.stringify(await page.evaluate(()=>({
      buttons:[...document.querySelectorAll('button')].map(b=>b.textContent.trim()).filter(Boolean).slice(0,60),
      text:document.body.innerText.slice(0,1900)})),null,2));
    try{await page.evaluate(()=>{for(const v of document.querySelectorAll('video')){v.muted=true;v.play().catch(()=>{});}})}catch(_){}
    await page.waitForTimeout(5000);
    console.log('PLAYER_MEDIA '+JSON.stringify({embed,requests,
      videos:await page.evaluate(()=>[...document.querySelectorAll('video')].map(v=>({readyState:v.readyState,
      paused:v.paused,networkState:v.networkState,currentTime:v.currentTime,error:v.error?.code||null,
      sourceHost:(()=>{try{return new URL(v.currentSrc).hostname}catch(_){return ''}})()})))},null,2));
  }catch(e){console.log('PLAYER_PROBE_ERROR '+embed+': '+e.message.slice(0,400));}
  await page.close();
 }
 await browser.close();
})().catch(e=>{console.log('ERROR '+e.message);process.exitCode=1});
