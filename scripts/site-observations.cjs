// Passive, signed-out desktop-browser observations, NOT Android test results.
// No credentials, account creation, CAPTCHA solving, identity spoofing or mutations.
// Query strings/fragments/cookies/headers other than CSP are never written to the artifact.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const output = process.env.OBSERVATIONS_DIR || 'site-observations';
const safe = value => { try { const u = new URL(value); return u.origin + u.pathname; } catch { return ''; } };
const pages = [
  ['login', 'https://github.com/login'],
  ['signup', 'https://github.com/signup'],
  ['repository', 'https://github.com/markcollege2026-droid/Arena.ai-apk'],
  ['file', 'https://github.com/markcollege2026-droid/Arena.ai-apk/blob/main/README.md'],
  ['issues', 'https://github.com/markcollege2026-droid/Arena.ai-apk/issues'],
  ['pull-request', 'https://github.com/markcollege2026-droid/Arena.ai-apk/pull/2'],
  ['actions', 'https://github.com/markcollege2026-droid/Arena.ai-apk/actions'],
  ['job', 'https://github.com/markcollege2026-droid/Arena.ai-apk/actions/runs/38487448876/job/115604618730'],
];
(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
  const context = await browser.newContext({ viewport: { width: 412, height: 880 } });
  for (const [name, url] of pages) {
    const page = await context.newPage();
    const requests = new Map();
    const failures = [];
    const websockets = new Set();
    page.on('request', r => requests.set(r.resourceType() + ' ' + safe(r.url()), { type: r.resourceType(), url: safe(r.url()) }));
    page.on('response', r => {
      const item = requests.get(r.request().resourceType() + ' ' + safe(r.url()));
      if (item) item.status = r.status();
    });
    page.on('requestfailed', r => failures.push({ url: safe(r.url()), type: r.resourceType(), error: r.failure()?.errorText }));
    page.on('websocket', s => websockets.add(safe(s.url())));
    const record = { name, requested: url, observedAt: new Date().toISOString(), browser: browser.version(), signedOut: true };
    try {
      const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
      record.status = response?.status();
      record.csp = response?.headers()['content-security-policy'];
      await page.waitForTimeout(6000);
      record.finalUrl = safe(page.url());
      record.dom = await page.evaluate(() => ({
        title: document.title,
        inputTypes: [...document.querySelectorAll('input')].filter(n => n.type !== 'hidden')
          .map(n => ({ type: n.type, id: n.id, name: n.name, placeholder: n.placeholder })),
        formActions: [...document.forms].map(f => { const u = new URL(f.action); return u.origin + u.pathname; }),
        hasGoogleButton: [...document.querySelectorAll('button,a')].some(n => /^(?:Continue|Sign in|Sign up) with Google$/i.test(n.textContent.trim())),
        hasCaptcha: /captcha|arkose|verify your account|verify you are human/i.test(document.body?.innerText || ''),
        scripts: [...document.scripts].filter(s => s.src).map(s => { const u = new URL(s.src); return u.origin + u.pathname; }),
      }));
      await page.screenshot({ path: path.join(output, name + '.png') });
      if (name === 'login' && record.dom.hasGoogleButton) {
        const button = page.getByRole('button', { name: /(?:Continue|Sign in|Sign up) with Google/i }).first();
        const link = page.getByRole('link', { name: /(?:Continue|Sign in|Sign up) with Google/i }).first();
        const candidate = await button.count() ? button : link;
        if (await candidate.count()) {
          await candidate.click();
          await page.waitForTimeout(8000);
          record.googleHandoffUrl = safe(page.url());
          record.googleHandoffTitle = await page.title();
          await page.screenshot({ path: path.join(output, 'google-handoff.png') });
        }
      }
    } catch (e) { record.observationError = e.name; }
    record.requests = [...requests.values()].sort((a,b) => a.url.localeCompare(b.url));
    record.failedRequests = failures;
    record.websockets = [...websockets];
    record.frames = page.frames().map(f => safe(f.url()));
    fs.writeFileSync(path.join(output, name + '.json'), JSON.stringify(record, null, 2));
    const hosts = [...new Set(record.requests.map(r => { try { return new URL(r.url).hostname; } catch { return ''; } }))].filter(Boolean).sort();
    const summary = JSON.stringify({ name, status: record.status, url: record.finalUrl, dom: record.dom && { title: record.dom.title, google: record.dom.hasGoogleButton, inputTypes: record.dom.inputTypes }, hosts, challengeResources: record.requests.filter(r => /captcha|arkose/.test(r.url)), frames: record.frames, websockets: record.websockets, googleHandoff: record.googleHandoffUrl, error: record.observationError });
    console.log(summary);
    console.log('::notice title=Signed-out browser observation ' + name + '::' + summary.replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A'));
    await page.close();
  }
  await browser.close();
})().catch(e => { console.error(e.name + ': observation job failed'); process.exitCode = 1; });
