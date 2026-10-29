/* Read-only live DOM / navigation diagnostic. Never print credentials or media URLs. */
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  const observed = new Map();
  page.on('response', response => {
    try {
      const u = new URL(response.url());
      if (!['cinelite.xyz', 'api.cinelite.xyz', 'embed.cinelite.xyz', 'api.themoviedb.org'].includes(u.hostname)) return;
      const key = u.hostname + u.pathname.replace(/\/[0-9]+(?:\/|$)/g, '/{id}/');
      if (!observed.has(key)) observed.set(key, response.status());
    } catch (_) {}
  });
  try {
    await page.goto('https://cinelite.xyz/', { waitUntil: 'domcontentloaded', timeout: 35000 });
    await page.waitForTimeout(5500);
    const home = await page.evaluate(() => {
      const headings = [...document.querySelectorAll('h3')].map(e => e.textContent?.trim()).filter(Boolean).slice(0,18);
      const sampleHeading = [...document.querySelectorAll('h3')].find(e => e.textContent?.includes("Grey's Anatomy"))
        || document.querySelector('h3');
      const ancestors = [];
      let node = sampleHeading;
      for (let i = 0; node && i < 5; ++i, node=node.parentElement) {
        ancestors.push({
          tag: node.tagName,
          className: typeof node.className === 'string' ? node.className.slice(0,200) : null,
          attributes: [...node.attributes].map(a=>[a.name,a.value.slice(0,120)]),
          outer: node.outerHTML.slice(0, i === 3 ? 3000 : 900)
        });
      }
      const links = [...document.querySelectorAll('a[href]')].map(a => [a.getAttribute('href')?.split('?')[0], a.innerText?.trim().slice(0,65)]).filter(([u,t])=>u&&t).slice(0,25);
      const clickable = [...document.querySelectorAll('[role=button],button,[data-id],[data-tmdb-id]')]
        .slice(0,18).map(el=>({tag:el.tagName,cls:String(el.className).slice(0,120),dataId:el.getAttribute('data-id'),
          tmdb:el.getAttribute('data-tmdb-id'),role:el.getAttribute('role'),title:el.innerText?.trim().slice(0,70)}));
      const inputs = [...document.querySelectorAll('input')].map(e=>({
        type:e.type,placeholder:e.placeholder,cls:String(e.className).slice(0,100)
      })).slice(0,12);
      return { title: document.title, cardsImg: document.querySelectorAll('img').length,
        headings, sampleAncestors: ancestors, links, clickable, inputs };
    });
    console.log('HOMEPAGE ' + JSON.stringify(home, null, 2));
    try {
      await page.getByText("Grey's Anatomy", { exact: true }).first().click({ timeout: 7000 });
      await page.waitForTimeout(3500);
      const details = await page.evaluate(() => ({
        url: location.origin + location.pathname + location.hash,
        title: document.title,
        headings: [...document.querySelectorAll('h1,h2,h3')].map(e=>e.textContent?.trim().slice(0,80)).filter(Boolean).slice(0,18),
        playerFrames: document.querySelectorAll('iframe').length,
        buttons: [...document.querySelectorAll('button,[role=button]')].map(e=>e.textContent?.trim().slice(0,70)).filter(Boolean).slice(0,30),
        selectors: [...document.querySelectorAll('select')].map(e=>({options:e.options.length, className:String(e.className).slice(0,100)})).slice(0,6)
      }));
      console.log('NAVIGATED_TV ' + JSON.stringify(details, null, 2));
    } catch (e) { console.log('TV_CLICK_ERROR: ' + e.message); }
    console.log('NETWORK ' + JSON.stringify([...observed].slice(0,80), null, 2));
    const embed = await browser.newPage();
    try {
      await embed.goto('https://embed.cinelite.xyz/', {waitUntil:'domcontentloaded', timeout:18000});
      await embed.waitForTimeout(1000);
      const doc = await embed.evaluate(()=>({
        title: document.title,
        headings: [...document.querySelectorAll('h1,h2,h3')].map(x=>x.textContent?.trim()).filter(Boolean).slice(0,20),
        examples: [...document.querySelectorAll('code,pre')].map(x=>x.textContent?.trim().slice(0,350)).filter(Boolean).slice(0,16),
        links: [...document.querySelectorAll('a[href]')].map(e=>e.getAttribute('href')).filter(Boolean).slice(0,22)
      }));
      console.log('PUBLIC_EMBED_DOCUMENTATION ' + JSON.stringify(doc,null,2));
    } catch(e) { console.log('EMBED_DOC_ERROR: '+e.message); }
    await embed.close();
  } catch (e) { console.log('Probe could not inspect site: ' + e.message); }
  finally { await browser.close(); }
})().catch(e=>{console.error(e.message); process.exitCode=1});
