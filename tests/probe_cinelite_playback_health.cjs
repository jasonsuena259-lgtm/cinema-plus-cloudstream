/* Diagnostics only: check the site's actual player routes, server-menu state,
 * and media HTTP outcomes. Never log signed URLs, tokens, headers or cookies.
 * A menu entry alone does NOT count as a working source. */
const { chromium } = require('playwright');
const routes = [
  { kind: 'anime-first', path: '/anime/21/1' },
  { kind: 'anime-next', path: '/anime/21/2' },
  { kind: 'movie', path: '/550' },
  { kind: 'tv-episode', path: '/1399/1/1' },
  // The user's screenshot shows VisionQuest S1E6 with 14 network agents.
  // Test the specific title, not only a different unrelated TV series.
  { kind: 'visionquest-s1e6', path: '/213375/1/6' },
  { kind: 'visionquest-watch-s1e6', siteUrl: 'https://cinelite.xyz/watch/213375/1/6' },
];
(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  try {
    for (const route of routes) {
      const page = await browser.newPage();
      const statusCodes = [], resourceTypes = new Set();
      let navError = null;
      page.on('response', response => {
        try {
          const url = new URL(response.url());
          const path = url.pathname.toLowerCase();
          const ext = (url.searchParams.get('ext') || '').toLowerCase();
          const mime = (response.headers()['content-type'] || '').toLowerCase();
          const isMedia = /\.(m3u8|mp4|m4s|ts)$/.test(path) ||
            /^(\.?(m3u8|mp4))$/.test(ext) ||
            /^(video\/|audio\/|application\/(vnd.apple.mpegurl|x-mpegurl))/.test(mime);
          if (isMedia) {
            statusCodes.push(response.status());
            resourceTypes.add(response.request().resourceType());
          }
        } catch (_) {}
      });
      try {
        await page.goto(route.siteUrl || ('https://embed.cinelite.xyz' + route.path),
          { waitUntil: 'domcontentloaded', timeout: 25000 });
        await page.waitForTimeout(1700);
        const serverState = await page.evaluate(() => {
          const text = document.body?.innerText || '';
          const agent = text.match(/(\d+)\s+Agents/i);
          return {
            agentCount: agent ? Number(agent[1]) : null,
            hasPlayer: !!document.querySelector('video'),
            hasSourceMenu: /Stream Sources|Servers/i.test(text)
          };
        });
        await page.evaluate(() => {
          document.querySelectorAll('video').forEach(v => {
            v.muted = true;
            v.play().catch(() => {});
          });
          const btn = [...document.querySelectorAll('button')].find(el =>
            /^(Reyna Sub|Clove|Reyna|Chamber|Neon|Viper)$/i.test(
              (el.textContent || '').trim()));
          btn?.click();
        });
        await page.waitForTimeout(450);
        const switched = await page.evaluate(() => {
          const row = [...document.querySelectorAll('[class*=cursor-pointer],[role=option]')]
            .find(el => /^(Chamber Sub|Reyna Sub|Neon Sub|Clove)$/.test(
              (el.textContent || '').replace(/\s+/g, ' ').trim()));
          if (!row) return false;
          row.click();
          document.querySelectorAll('video').forEach(v => {
            v.muted = true;
            v.play().catch(() => {});
          });
          return true;
        });
        await page.waitForTimeout(2800);
        const good = statusCodes.filter(s => s === 200 || s === 206).length;
        console.log('CINELITE_LIVE_HEALTH ' + JSON.stringify({
          titleKind: route.kind, agentCount: serverState.agentCount,
          hasSourceMenu: serverState.hasSourceMenu, hasPlayer: serverState.hasPlayer,
          switchedOneRealRow: switched, totalMediaResponses: statusCodes.length,
          successfulMediaResponses: good,
          failedMediaResponses: statusCodes.length - good,
          // No endpoint, URL host, token, cookie or even media request path.
          mediaResourceTypes: [...resourceTypes]
        }));
      } catch (e) {
        navError = e.message.split('\n')[0].slice(0,120);
        console.log('CINELITE_LIVE_HEALTH_ERROR ' + JSON.stringify({
          titleKind: route.kind, reason: navError
        }));
      } finally {
        await page.close();
      }
    }
  } finally {
    await browser.close();
  }
})().catch(e => {
  console.error('CINELITE_LIVE_HEALTH_RUNNER_FAILED ' + e.message.split('\n')[0]);
  process.exitCode = 1;
});
