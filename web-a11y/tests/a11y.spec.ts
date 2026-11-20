import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { spawn, execFileSync, type ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

const WCAG_TAGS = [
  'wcag2a',
  'wcag2aa',
  'wcag21a',
  'wcag21aa',
  'wcag22aa',
  'best-practice',
];

const MAX_LOG_BYTES = 16 * 1024;

function resolveServerBinary(): string {
  if (process.env.ROCHE_SERVER_BIN) {
    return process.env.ROCHE_SERVER_BIN;
  }
  const repoRoot = path.resolve(__dirname, '..', '..');
  const candidates = [
    path.join(repoRoot, 'target', 'debug', 'roche-server'),
    path.join(repoRoot, 'target', 'release', 'roche-server'),
  ];
  const existing = candidates
    .filter((c) => fs.existsSync(c))
    .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  if (existing.length > 0) {
    return existing[0];
  }
  throw new Error(
    `Could not find roche-server binary. Build it first with \`cargo build -p roche-server\` or set ROCHE_SERVER_BIN. Checked: ${candidates.join(', ')}`
  );
}

async function getFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (addr && typeof addr === 'object') {
        const { port } = addr;
        server.close(() => resolve(port));
      } else {
        server.close(() => reject(new Error('Failed to allocate ephemeral port')));
      }
    });
  });
}

function appendBounded(current: string, chunk: Buffer): string {
  const combined = current + chunk.toString('utf8');
  return combined.length > MAX_LOG_BYTES
    ? combined.slice(combined.length - MAX_LOG_BYTES)
    : combined;
}

async function waitForServer(
  baseUrl: string,
  child: ChildProcess,
  getLogs: () => string,
  timeoutMs = 20_000
): Promise<void> {
  let exitReason: string | null = null;
  const onExit = (code: number | null, signal: NodeJS.Signals | null) => {
    exitReason = `exited prematurely (code=${code}, signal=${signal})`;
  };
  const onError = (err: Error) => {
    exitReason = `failed to spawn: ${err.message}`;
  };
  child.once('exit', onExit);
  child.once('error', onError);

  try {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (exitReason) {
        throw new Error(`roche-server ${exitReason}\n--- server output ---\n${getLogs()}`);
      }
      try {
        const res = await fetch(`${baseUrl}/login`);
        if (res.ok) return;
      } catch {
        // Server not listening yet
      }
      await new Promise((r) => setTimeout(r, 150));
    }
    throw new Error(
      `Timed out waiting for Roche server at ${baseUrl}\n--- server output ---\n${getLogs()}`
    );
  } finally {
    child.off('exit', onExit);
    child.off('error', onError);
  }
}

async function stopServerGracefully(child: ChildProcess | undefined): Promise<void> {
  if (!child || child.exitCode !== null || child.signalCode !== null) {
    return;
  }
  await new Promise<void>((resolve) => {
    const killTimer = setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill('SIGKILL');
      }
    }, 5_000);
    killTimer.unref();
    child.once('exit', () => {
      clearTimeout(killTimer);
      resolve();
    });
    child.kill('SIGTERM');
  });
}

function seedFixtures(serverBin: string, dataDir: string): void {
  const env = { ...process.env, ROCHE_TAILSCALE_ENABLED: 'false' };

  const batchPayload = {
    entries: [
      {
        ref_key: 'e1',
        kind: 'book',
        title: 'The Design of Everyday Things',
        creators: ['Don Norman'],
        release_year: 1988,
      },
      {
        ref_key: 'e2',
        kind: 'book',
        title: 'Inclusive Components',
        creators: ['Heydon Pickering'],
        release_year: 2019,
      },
      {
        ref_key: 'e3',
        kind: 'book',
        title: 'The Elements of Typographic Style',
        creators: ['Robert Bringhurst'],
        release_year: 1992,
      },
      {
        ref_key: 'e4',
        kind: 'book',
        title: 'Grid Systems in Graphic Design',
        creators: ['Josef Müller-Brockmann'],
        release_year: 1981,
      },
      {
        ref_key: 'e5',
        kind: 'album',
        title: 'Kind of Blue',
        creators: ['Miles Davis'],
        release_year: 1959,
      },
      {
        ref_key: 'e6',
        kind: 'movie',
        title: 'Stalker',
        creators: ['Andrei Tarkovsky'],
        release_year: 1979,
      },
    ],
    collections: [
      {
        ref_key: 'classics',
        title: 'Design Classics',
        slug: 'design-classics',
        role: 'list',
        summary: 'Foundational texts on usability, typography, and systems.',
        members: [
          { entry_ref: 'e1' },
          { entry_ref: 'e2' },
          { entry_ref: 'e3' },
          { entry_ref: 'e4' },
        ],
      },
      {
        ref_key: 'want-books',
        title: 'Want to Read (Books)',
        slug: 'want:book',
        role: 'want',
        members: [{ entry_ref: 'e3' }, { entry_ref: 'e4' }],
      },
    ],
    logs: [
      {
        entry_ref: 'e1',
        status: 'consumed',
        notes: 'Essential mental models for affordances and signifiers.',
        ts: '2026-09-14T18:00:00Z',
      },
      {
        entry_ref: 'e2',
        status: 'consumed',
        notes: 'Practical ARIA patterns and semantic HTML.',
        ts: '2026-10-02T19:30:00Z',
      },
      {
        entry_ref: 'e5',
        status: 'started',
        notes: 'Evening listening session.',
        ts: '2026-10-18T21:00:00Z',
      },
      {
        entry_ref: 'e6',
        status: 'abandoned',
        notes: 'Saving for a quieter weekend.',
        ts: '2026-08-22T20:00:00Z',
      },
    ],
    comparisons: [
      {
        scope_collection_id: 'classics',
        ordering: [['e1'], ['e2'], ['e3'], ['e4']],
        ts: '2026-10-03T12:00:00Z',
      },
    ],
    generators: [
      {
        collection_ref: 'classics',
        enabled: true,
        current_entry_ref: 'e3',
        drawn_at: '2026-10-20T09:00:00Z',
      },
    ],
  };

  execFileSync(serverBin, ['--data-dir', dataDir, 'catalog', 'apply', '-'], {
    input: JSON.stringify(batchPayload),
    encoding: 'utf8',
    env,
  });

  const opmlPath = path.join(dataDir, 'seed.opml');
  fs.writeFileSync(
    opmlPath,
    `<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
  <head><title>Roche Seed Feeds</title></head>
  <body>
    <outline text="Editorial Essays" title="Editorial Essays" type="rss" xmlUrl="https://editorial.example/rss.xml" htmlUrl="https://editorial.example"/>
    <outline text="Design Talks" title="Design Talks" type="rss" xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UC1234567890" htmlUrl="https://www.youtube.com/channel/UC1234567890"/>
  </body>
</opml>`,
    'utf8'
  );

  execFileSync(serverBin, ['--data-dir', dataDir, 'import', opmlPath], {
    encoding: 'utf8',
    env,
  });
}

function formatViolations(
  route: string,
  violations: Awaited<ReturnType<AxeBuilder['analyze']>>['violations']
): string {
  return violations
    .map((v) => {
      const nodes = v.nodes
        .map(
          (n) =>
            `    - target: ${n.target.join(' ')}\n      html: ${n.html}\n      summary: ${n.failureSummary ?? ''}`
        )
        .join('\n');
      return `[${v.impact ?? 'unknown'}] ${v.id} on ${route}: ${v.help} (${v.helpUrl})\n${nodes}`;
    })
    .join('\n\n');
}

async function assertAccessible(page: Page, routeLabel: string): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
  expect(
    results.violations,
    `Accessibility violations found on ${routeLabel}:\n\n${formatViolations(routeLabel, results.violations)}`
  ).toEqual([]);
}

async function gotoAndAssertOk(
  page: Page,
  baseUrl: string,
  route: string,
  expectedPath?: string
): Promise<void> {
  const response = await page.goto(`${baseUrl}${route}`, { waitUntil: 'load' });
  expect(response, `No HTTP response for ${route}`).not.toBeNull();
  expect(response!.status(), `Expected HTTP 200 on ${route}`).toBe(200);
  const actualPath = new URL(page.url()).pathname;
  expect(actualPath, `Unexpected redirect to /login on ${route}`).not.toBe('/login');
  if (expectedPath) {
    expect(actualPath, `Unexpected pathname for ${route}`).toBe(expectedPath);
  }
}

test.describe('Roche Web Accessibility (WCAG 2.1/2.2 AA + Best Practices + Contrast)', () => {
  let serverProcess: ChildProcess | undefined;
  let dataDir: string | undefined;
  let baseUrl = '';
  let serverBin = '';
  let serverLogs = '';
  let sessionCookies: Awaited<ReturnType<Page['context']['cookies']>> = [];

  test.beforeAll(async ({ browser }) => {
    serverBin = resolveServerBinary();
    dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'roche-web-a11y-'));
    seedFixtures(serverBin, dataDir);

    const port = await getFreePort();
    baseUrl = `http://127.0.0.1:${port}`;
    serverLogs = '';

    serverProcess = spawn(
      serverBin,
      ['--data-dir', dataDir, '--host', '127.0.0.1', '--port', String(port), 'serve'],
      {
        stdio: ['ignore', 'pipe', 'pipe'],
        env: {
          ...process.env,
          ROCHE_TAILSCALE_ENABLED: 'false',
          RUST_LOG: 'warn',
        },
      }
    );
    serverProcess.stdout?.on('data', (chunk: Buffer) => {
      serverLogs = appendBounded(serverLogs, chunk);
    });
    serverProcess.stderr?.on('data', (chunk: Buffer) => {
      serverLogs = appendBounded(serverLogs, chunk);
    });

    await waitForServer(baseUrl, serverProcess, () => serverLogs);

    // Pair once and cache session cookies for authenticated route tests.
    const pairingCode = execFileSync(serverBin, ['--data-dir', dataDir, 'pair'], {
      encoding: 'utf8',
      env: { ...process.env, ROCHE_TAILSCALE_ENABLED: 'false' },
    }).trim();
    expect(pairingCode.length).toBeGreaterThan(0);

    const authContext = await browser.newContext();
    const authPage = await authContext.newPage();
    await authPage.goto(`${baseUrl}/login`, { waitUntil: 'load' });
    await authPage.fill('#pairing_code', pairingCode);
    await Promise.all([
      authPage.waitForURL((url) => url.pathname === '/'),
      authPage.click('button[type="submit"]'),
    ]);
    sessionCookies = await authContext.cookies();
    await authContext.close();
  });

  test.afterAll(async () => {
    await stopServerGracefully(serverProcess);
    if (dataDir && fs.existsSync(dataDir)) {
      fs.rmSync(dataDir, { recursive: true, force: true });
    }
  });

  test('login page (clean and invalid-code error state) passes axe WCAG AA checks', async ({
    page,
  }) => {
    const resp = await page.goto(`${baseUrl}/login`, { waitUntil: 'load' });
    expect(resp?.status()).toBe(200);
    await assertAccessible(page, '/login');

    await page.fill('#pairing_code', 'definitely-invalid-code');
    const [errResp] = await Promise.all([
      page.waitForResponse((r) => new URL(r.url()).pathname === '/login' && r.request().method() === 'POST'),
      page.click('button[type="submit"]'),
    ]);
    expect(errResp.status()).toBe(401);
    await expect(page.locator('#login-error')).toBeVisible();
    await expect(page.locator('#pairing_code')).toHaveAttribute('aria-invalid', 'true');
    await assertAccessible(page, '/login (401 error)');
  });

  const populatedRoutes: Array<{ route: string; expectedPath?: string; checkSelector: string }> = [
    { route: '/', expectedPath: '/', checkSelector: '.album-sleeve' },
    { route: '/inbox', expectedPath: '/inbox', checkSelector: 'main#roche-main h2' },
    {
      route: '/inbox?kind=article&q=Accessibility',
      expectedPath: '/inbox',
      checkSelector: '.active-filters',
    },
    { route: '/starred', expectedPath: '/lists/starred', checkSelector: 'main#roche-main h2' },
    { route: '/feeds', expectedPath: '/feeds', checkSelector: 'text=Editorial Essays' },
    { route: '/lists', expectedPath: '/lists', checkSelector: 'text=Design Classics' },
    {
      route: '/lists?q=Design',
      expectedPath: '/lists',
      checkSelector: 'text=The Design of Everyday Things',
    },
    {
      route: '/lists/design-classics',
      expectedPath: '/lists/design-classics',
      checkSelector: 'table.ranked',
    },
    { route: '/diary', expectedPath: '/diary', checkSelector: '.diary-entry-row' },
    { route: '/queue', expectedPath: '/queue', checkSelector: 'main#roche-main h2' },
    { route: '/rank', expectedPath: '/rank', checkSelector: 'text=Design Classics' },
  ];

  for (const { route, expectedPath, checkSelector } of populatedRoutes) {
    test(`populated route ${route} passes axe WCAG AA + best-practice checks`, async ({ page }) => {
      await page.context().addCookies(sessionCookies);
      await gotoAndAssertOk(page, baseUrl, route, expectedPath);
      await expect(page.locator(checkSelector).first()).toBeVisible();
      await assertAccessible(page, route);
    });
  }

  test('entry detail, list creation/add form, and interactive rank board pass axe + keyboard focus checks', async ({
    page,
  }) => {
    await page.context().addCookies(sessionCookies);

    // 1. Entry detail page from populated list
    await gotoAndAssertOk(page, baseUrl, '/lists/design-classics', '/lists/design-classics');
    const firstEntryLink = page.locator('table.ranked a[href^="/entries/"]').first();
    await expect(firstEntryLink).toBeVisible();
    const entryHref = await firstEntryLink.getAttribute('href');
    expect(entryHref).toBeTruthy();
    await gotoAndAssertOk(page, baseUrl, entryHref!, entryHref!);
    await expect(page.locator('main#roche-main h2')).toBeVisible();
    await assertAccessible(page, entryHref!);

    // 2. Real form flow: create a manual list and add a work via the actual template controls
    await gotoAndAssertOk(page, baseUrl, '/lists', '/lists');
    const createToggle = page.locator('button.js-new-list-toggle', {
      hasText: 'New List',
    });
    await expect(createToggle).toBeVisible();
    await createToggle.click();
    const listTitleInput = page.locator('#list-title');
    await expect(listTitleInput).toBeVisible();
    await listTitleInput.fill('Accessibility Audit List');
    await Promise.all([
      page.waitForURL((url) => url.pathname.startsWith('/lists/') && url.pathname !== '/lists/'),
      page.locator('form[action="/web/lists/create"] button[type="submit"]').click(),
    ]);
    const createdListPath = new URL(page.url()).pathname;
    await expect(page.locator('main#roche-main h2')).toContainText('Accessibility Audit List');
    await assertAccessible(page, `${createdListPath} (empty manual list)`);

    const addSummary = page.locator('details.editorial-panel > summary', {
      hasText: 'Add Work to List',
    });
    await expect(addSummary).toBeVisible();
    await addSummary.click();
    const addTitleInput = page.locator('#add-title');
    await expect(addTitleInput).toBeVisible();
    await addTitleInput.fill('A Pattern Language');
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/add')),
      page.locator('form[action^="/web/lists/"][action$="/add"] button[type="submit"]').click(),
    ]);
    await expect(page.locator('table.ranked')).toContainText('A Pattern Language');
    await assertAccessible(page, `${createdListPath} (populated manual list)`);

    // 3. Interactive rank board: axe audit + keyboard focus preservation + tie toggle Label-in-Name
    await gotoAndAssertOk(page, baseUrl, '/rank/design-classics', '/rank/design-classics');
    await expect(page.locator('.js-rank-card').first()).toBeVisible();
    await assertAccessible(page, '/rank/design-classics (initial)');

    // Focus card #2's Move Up button, activate it, and verify focus stays on the button (not lost to body!)
    const secondCardUpBtn = page.locator('.js-rank-card').nth(1).locator('.js-card-up');
    await secondCardUpBtn.focus();
    await secondCardUpBtn.click();
    const activeIsStillButton = await page.evaluate(() =>
      document.activeElement?.classList.contains('js-card-up') ?? false
    );
    expect(activeIsStillButton, 'Focus was lost after reordering rank card').toBe(true);

    // Toggle Tie on card #2 and re-run axe to verify Untie state passes Label-in-Name & ARIA checks
    const secondCardTieBtn = page.locator('.js-rank-card').nth(1).locator('.js-card-tie');
    await secondCardTieBtn.click();
    await expect(secondCardTieBtn).toHaveAttribute('aria-pressed', 'true');
    await expect(secondCardTieBtn).toHaveText('Untie');
    await assertAccessible(page, '/rank/design-classics (after move & tie)');
  });
});
