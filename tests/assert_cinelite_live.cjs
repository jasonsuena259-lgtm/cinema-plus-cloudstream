/* CineLite end-to-end card smoke check. The values below come from live React metadata,
 * not a fake/static TMDB catalogue. Never collect cookies or media tokens. */
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

function items() {
  return [...document.querySelectorAll('h3')].flatMap(h => {
    const k = Object.keys(h).find(k => k.startsWith('__reactFiber'));
    let f = k ? h[k] : null;
    for (let i = 0; f && i < 12; i++, f = f.return) {
      const item = f.memoizedProps?.item;
      if (Number.isInteger(item?.id) && item.id > 0 &&
          ['movie', 'tv'].includes(item.media_type)) {
        const card = h.closest('div.cursor-pointer');
        const poster = card?.querySelector('img[src]')?.getAttribute('src') ?? '';
        return [{ id: item.id, type: item.media_type,
          title: h.textContent.trim(), poster: poster.startsWith('https://') }];
      }
    }
    return [];
  });
}

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage();
    await page.goto('https://cinelite.xyz/', { waitUntil: 'domcontentloaded', timeout: 40000 });
    await page.waitForFunction(() => document.querySelectorAll('div.slider-track h3').length >= 8,
      { timeout: 30000 });
    const home = await page.evaluate(items);
    const unique = new Map(home.map(item => [item.type + ':' + item.id, item]));
    console.log('HOME_REAL_CARDS', JSON.stringify({ total: home.length, unique: unique.size,
      validPosters: [...unique.values()].filter(i => i.poster).length,
      movie: [...unique.values()].filter(i => i.type === 'movie').length,
      tv: [...unique.values()].filter(i => i.type === 'tv').length }));
    assert.ok(unique.size >= 8, 'Expected at least eight real CineLite cards with React identities');
    assert.ok([...unique.values()].every(i => i.title), 'Card titles must be present');
    assert.ok([...unique.values()].some(i => i.poster), 'At least one real poster must exist');

    await page.goto('https://cinelite.xyz/search', { waitUntil: 'domcontentloaded', timeout: 40000 });
    const input = page.locator('input[type=text]').first();
    await input.waitFor({ state: 'visible', timeout: 20000 });
    await input.fill('Fight Club');
    await page.waitForFunction(() => [...document.querySelectorAll('h3')]
      .some(e => /fight club/i.test(e.textContent ?? '')), { timeout: 30000 });
    const search = await page.evaluate(items);
    console.log('SEARCH_REAL_CARDS', JSON.stringify({ total: search.length,
      matches: search.filter(i => /fight club/i.test(i.title)).length,
      sampleIds: search.slice(0, 4).map(i => i.type + ':' + i.id) }));
    assert.ok(search.some(i => /fight club/i.test(i.title)), 'Search must return real titled results');
    assert.ok(search.some(i => i.id > 0), 'Search must expose a valid TMDB identity');
    await page.close();
  } finally { await browser.close(); }
})().catch(err => { console.error('LIVE_CARDS_FAILED', err.message); process.exitCode = 1; });
