/* Ephemeral read-only diagnostic; no media URL, credential or query-string logging. */
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
    await page.waitForTimeout(7000);
    const result = await page.evaluate(() => {
      const links = [...document.querySelectorAll('a[href]')].map(a => ({
        href: a.getAttribute('href')?.split('?')[0]?.slice(0,140),
        title: (a.getAttribute('title') || a.innerText || a.querySelector('img')?.alt || '').trim().slice(0,70)
      })).filter(l => l.href && l.title).slice(0,25);
      const images = [...document.querySelectorAll('img')].length;
      const headings = [...document.querySelectorAll('h1,h2,h3')].map(n=>n.innerText?.trim().slice(0,60)).filter(Boolean).slice(0,15);
      const possibleCards = [...document.querySelectorAll('[class*=card],[class*=Card]')].length;
      const scriptSources = [...document.querySelectorAll('script[src]')].map(e=>new URL(e.src).pathname).slice(0,12);
      return { title: document.title, links, images, headings, possibleCards, scriptSources };
    });
    console.log(JSON.stringify({ status: 'observed', page: result, endpoints: [...observed].slice(0,45) }, null, 2));
    if (result.links.length === 0) console.log('WARNING: no anchor cards, DOM selector contract unverified.');
  } catch (e) {
    console.log('Probe cannot verify live content: ' + e.message);
    process.exitCode = 0; // diagnostic, not a false passing playback test
  } finally {
    await browser.close();
  }
})().catch(e => { console.error(e.message); process.exitCode = 1 });
