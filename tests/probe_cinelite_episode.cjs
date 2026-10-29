const {chromium}=require('playwright');
(async()=>{
 const b=await chromium.launch({headless:true,args:['--no-sandbox']});
 const p=await b.newPage();
 const sleep=ms=>p.waitForTimeout(ms);
 try {
 await p.goto('https://cinelite.xyz/',{waitUntil:'domcontentloaded',timeout:35000});await sleep(5000);
 await p.getByText("Grey's Anatomy",{exact:true}).first().click({timeout:8000});await sleep(2000);
 console.log('EPISODE_DOM '+JSON.stringify(await p.evaluate(()=>{
  const leaf=[...document.querySelectorAll('*')].find(x=>x.children.length===0&&/^EPISODE 1$/i.test(x.textContent.trim()));
  const around=leaf?.parentElement?.parentElement?.parentElement;
  const cards=[...document.querySelectorAll('img[alt]')].filter(x=>x.alt.includes('A Hard Day'));
  return {leaf:leaf?.outerHTML,parent:around?.outerHTML.slice(0,3500),cards:cards.map(c=>c.parentElement?.outerHTML.slice(0,1000))};
 }),null,2));
 await p.getByText('Season 1',{exact:true}).first().click({timeout:5000});await sleep(450);
 console.log('SEASON_MENU '+JSON.stringify(await p.evaluate(()=>{
   return [...document.querySelectorAll('button')].filter(x=>/^Season \d+$/i.test(x.textContent.trim())).slice(0,35).map(x=>({text:x.textContent.trim(),outer:x.outerHTML.slice(0,400)}));
 }),null,2));
 try {await p.getByText('Season 2',{exact:true}).last().click({timeout:3500});await sleep(1100);
 console.log('SEASON_TWO '+JSON.stringify(await p.evaluate(()=>({
 text:document.body.innerText.slice(0,1400),
 episodes:[...document.querySelectorAll('*')].filter(x=>x.children.length===0&&/^EPISODE \d+$/i.test(x.textContent.trim())).slice(0,14).map(x=>x.textContent.trim())
 })),null,2));
 } catch(e){console.log('SEASON2_ERR '+e.message.slice(0,400));}
 await p.goto('https://cinelite.xyz/',{waitUntil:'domcontentloaded',timeout:25000});await sleep(3200);
 await p.getByRole('button',{name:'Search'}).first().click({timeout:5000});await sleep(600);
 console.log('SEARCH_SCREEN '+JSON.stringify(await p.evaluate(()=>({
 inputs:[...document.querySelectorAll('input')].map(x=>({type:x.type,placeholder:x.placeholder,outer:x.outerHTML.slice(0,450)})),
 text:document.body.innerText.slice(0,800),url:location.origin+location.pathname
 })),null,2));
 const inp=p.locator('input').first();
 await inp.fill('Fight Club');await sleep(2100);
 console.log('SEARCH_RESULTS '+JSON.stringify(await p.evaluate(()=>({
 titles:[...document.querySelectorAll('h3')].map(x=>x.textContent.trim()).slice(0,24),
 text:document.body.innerText.slice(0,850),
 sample:[...document.querySelectorAll('h3')].slice(0,2).map(h=>h.parentElement?.parentElement?.outerHTML.slice(0,800))
 })),null,2));
 }catch(e){console.log('PROBE_ERROR '+e.message);}
 finally{await b.close();}
})().catch(e=>{console.log('FATAL '+e.message);process.exitCode=1});
