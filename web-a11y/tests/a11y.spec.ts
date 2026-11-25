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

function rocheTestEnv(extra: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  return {
    ...process.env,
    ROCHE_TAILSCALE_ENABLED: 'false',
    ROCHE_BACKUP_ENABLED: 'false',
    ...extra,
  };
}

function formatServerDiagnostics(
  child: ChildProcess | undefined,
  exitInfo: string | null,
  logs: string
): string {
  const state = child
    ? `pid=${child.pid ?? 'none'}, exitCode=${child.exitCode}, signalCode=${child.signalCode}, event=${exitInfo ?? 'running'}`
    : 'not started';
  return `--- roche-server state (${state}) ---\n${logs.trim() || '(no output captured)'}`;
}

function isAddrInUseError(err: unknown, logs: string): boolean {
  const haystack = `${err instanceof Error ? err.message : String(err)}\n${logs}`;
  return /address already in use|addrinuse|eaddrinuse/i.test(haystack);
}

function seedFixtures(serverBin: string, dataDir: string): void {
  const env = rocheTestEnv();

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
      {
        ref_key: 'e7',
        kind: 'book',
        title: 'Thinking with Type',
        creators: ['Ellen Lupton'],
        release_year: 2004,
      },
      {
        ref_key: 'e8',
        kind: 'book',
        title: 'Envisioning Information',
        creators: ['Edward Tufte'],
        release_year: 1990,
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
          { entry_ref: 'e7' },
          { entry_ref: 'e8' },
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
        entry_ref: 'e7',
        status: 'consumed',
        notes: 'Clear visual guide to letterforms, grids, and hierarchy.',
        ts: '2026-10-05T14:00:00Z',
      },
      {
        entry_ref: 'e8',
        status: 'consumed',
        notes: 'Dense analytical design principles for information displays.',
        ts: '2026-10-09T16:15:00Z',
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
  let serverExitInfo: string | null = null;
  let sessionCookies: Awaited<ReturnType<Page['context']['cookies']>> = [];
  let pageErrors: string[] = [];

  test.beforeEach(async ({ page }) => {
    pageErrors = [];
    page.on('pageerror', (err) => {
      pageErrors.push(`pageerror: ${err.message}`);
    });
    await page.addInitScript(() => {
      (window as unknown as { __rocheCspViolations?: string[] }).__rocheCspViolations = [];
      document.addEventListener('securitypolicyviolation', (ev) => {
        (window as unknown as { __rocheCspViolations?: string[] }).__rocheCspViolations?.push(
          `${ev.violatedDirective}: ${ev.blockedURI || '(inline)'}`
        );
      });
    });
  });

  test.beforeAll(async ({ browser }) => {
    serverBin = resolveServerBinary();
    dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'roche-web-a11y-'));
    seedFixtures(serverBin, dataDir);

    // Mint the initial pairing code before spawning `serve` to avoid concurrent startup SQLite writes.
    const pairingCode = execFileSync(serverBin, ['--data-dir', dataDir, 'pair'], {
      encoding: 'utf8',
      env: rocheTestEnv(),
    }).trim();
    expect(pairingCode.length).toBeGreaterThan(0);

    // Retry ephemeral port allocation on rare Linux TOCTOU `EADDRINUSE` races.
    const maxStartAttempts = 3;
    for (let attempt = 1; attempt <= maxStartAttempts; attempt++) {
      const port = await getFreePort();
      baseUrl = `http://127.0.0.1:${port}`;
      serverLogs = '';
      serverExitInfo = null;

      const child = spawn(
        serverBin,
        ['--data-dir', dataDir, '--host', '127.0.0.1', '--port', String(port), 'serve'],
        {
          stdio: ['ignore', 'pipe', 'pipe'],
          env: rocheTestEnv({ RUST_LOG: 'warn' }),
        }
      );
      serverProcess = child;
      child.stdout?.on('data', (chunk: Buffer) => {
        serverLogs = appendBounded(serverLogs, chunk);
      });
      child.stderr?.on('data', (chunk: Buffer) => {
        serverLogs = appendBounded(serverLogs, chunk);
      });
      child.once('exit', (code, signal) => {
        serverExitInfo = `exit(code=${code}, signal=${signal})`;
      });
      child.once('error', (err) => {
        serverExitInfo = `error(${err.message})`;
      });

      try {
        await waitForServer(baseUrl, child, () => serverLogs);
        break;
      } catch (err) {
        await stopServerGracefully(child);
        if (attempt < maxStartAttempts && isAddrInUseError(err, serverLogs)) {
          continue;
        }
        throw err;
      }
    }

    const authContext = await browser.newContext();
    try {
      const authPage = await authContext.newPage();
      await authPage.goto(`${baseUrl}/login`, { waitUntil: 'load' });
      await authPage.fill('#pairing_code', pairingCode);
      await Promise.all([
        authPage.waitForURL((url) => url.pathname === '/'),
        authPage.click('button[type="submit"]'),
      ]);
      sessionCookies = await authContext.cookies();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      throw new Error(
        `beforeAll login failed against ${baseUrl}: ${msg}\n${formatServerDiagnostics(serverProcess, serverExitInfo, serverLogs)}`,
        { cause: err }
      );
    } finally {
      await authContext.close();
    }
  });

  test.afterEach(async ({ page }, testInfo) => {
    const cspViolations = await page
      .evaluate(
        () =>
          (window as unknown as { __rocheCspViolations?: string[] }).__rocheCspViolations ?? []
      )
      .catch(() => [] as string[]);

    if (testInfo.status !== testInfo.expectedStatus) {
      const diag = formatServerDiagnostics(serverProcess, serverExitInfo, serverLogs);
      await testInfo.attach('roche-server-diagnostics', {
        body: diag,
        contentType: 'text/plain',
      });
      if (serverExitInfo) {
        console.error(diag);
      }
    }

    expect(pageErrors, `Uncaught JS runtime errors in ${testInfo.title}`).toEqual([]);
    expect(cspViolations, `CSP violations detected in ${testInfo.title}`).toEqual([]);
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
    await expect(page.locator('#pairing_code')).toBeVisible();
    await assertAccessible(page, '/login');

    await page.fill('#pairing_code', 'definitely-invalid-code');
    const [errResp] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === '/api/v1/auth/login' && r.request().method() === 'POST'
      ),
      page.click('button[type="submit"]'),
    ]);
    expect(errResp.status()).toBe(401);
    await expect(page.locator('#login-error')).toBeVisible();
    await expect(page.locator('#pairing_code')).toHaveAttribute('aria-invalid', 'true');
    await assertAccessible(page, '/login (401 error)');
  });

  const populatedRoutes: Array<{ route: string; expectedPath?: string; checkSelector: string }> = [
    { route: '/', expectedPath: '/', checkSelector: '.album-sleeve' },
    { route: '/inbox', expectedPath: '/inbox', checkSelector: ':is(.inbox-row, p.empty)' },
    {
      route: '/inbox?kind=article&q=Accessibility',
      expectedPath: '/inbox',
      checkSelector: '.active-filters',
    },
    { route: '/starred', expectedPath: '/lists/starred', checkSelector: 'h2:has-text("Starred")' },
    { route: '/feeds', expectedPath: '/feeds', checkSelector: 'text=Editorial Essays' },
    { route: '/lists', expectedPath: '/lists', checkSelector: 'text=By Media Type' },
    {
      route: '/lists?q=Design',
      expectedPath: '/lists',
      checkSelector: 'text=The Design of Everyday Things',
    },
    {
      route: '/lists/design-classics',
      expectedPath: '/lists/design-classics',
      checkSelector: 'table.ranked tbody tr',
    },
    { route: '/diary', expectedPath: '/diary', checkSelector: '.diary-entry-row' },
    { route: '/queue', expectedPath: '/queue', checkSelector: '.queue-item-row' },
    { route: '/rank', expectedPath: '/lists', checkSelector: 'text=Design Classics' },
  ];

  for (const { route, expectedPath, checkSelector } of populatedRoutes) {
    test(`populated route ${route} passes axe WCAG AA + best-practice checks`, async ({ page }) => {
      await page.context().addCookies(sessionCookies);
      await gotoAndAssertOk(page, baseUrl, route, expectedPath);
      await expect(page.locator(checkSelector).first()).toBeVisible();
      if (route === '/lists') {
        await expect(page.locator('text=Design Classics').first()).toBeVisible();
        await expect(page.locator('a.list-row-title', { hasText: 'Books' })).toBeVisible();
      }
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
      page.locator('form[data-list-form="create"] button[type="submit"]').click(),
    ]);
    const createdListPath = new URL(page.url()).pathname;
    await expect(page.locator('main#roche-main h2')).toContainText('Accessibility Audit List');
    await assertAccessible(page, `${createdListPath} (empty manual list)`);

    const addTitleInput = page.locator('#add-title');
    await expect(addTitleInput).toBeVisible();
    await addTitleInput.fill('A Pattern Language');
    await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === 'POST' && r.url().endsWith('/entries/resolve')
      ),
      page.locator('form[data-list-form="add"] button[type="submit"]').click(),
    ]);
    await expect(page.locator('table.ranked')).toContainText('A Pattern Language');
    await assertAccessible(page, `${createdListPath} (populated manual list)`);

    // 3. Interactive rank board: axe audit + keyboard focus preservation + tie toggle Label-in-Name
    await gotoAndAssertOk(page, baseUrl, '/rank/design-classics', '/rank/design-classics');
    await expect(page.locator('.js-rank-card').first()).toBeVisible();
    await expect(page.locator('.js-rank-card').first().locator('.js-card-up')).toBeDisabled();
    await expect(page.locator('.js-rank-card').last().locator('.js-card-down')).toBeDisabled();
    await assertAccessible(page, '/rank/design-classics (initial)');

    // Focus card #2's Move Up button, activate it, and verify focus stays inside the card (transfers cleanly when Move Up disables at top)
    const secondCardUpBtn = page.locator('.js-rank-card').nth(1).locator('.js-card-up');
    await secondCardUpBtn.focus();
    await secondCardUpBtn.click();
    const activeIsStillMoveButton = await page.evaluate(() => {
      const el = document.activeElement;
      return Boolean(el && (el.classList.contains('js-card-up') || el.classList.contains('js-card-down')));
    });
    expect(activeIsStillMoveButton, 'Focus was lost after reordering rank card').toBe(true);

    // Toggle Tie on card #2 and re-run axe to verify Untie state passes Label-in-Name & ARIA checks
    const secondCardTieBtn = page.locator('.js-rank-card').nth(1).locator('.js-card-tie');
    await secondCardTieBtn.click();
    await expect(secondCardTieBtn).toHaveAttribute('aria-pressed', 'true');
    await expect(secondCardTieBtn).toHaveText('Untie');
    await assertAccessible(page, '/rank/design-classics (after move & tie)');

    // Dynamically reduce cluster size from 4 -> 3, verify Skip covered only tracks the 3 active items,
    // then manually exclude 1 more card down to floor 2, then restore all and verify size pill syncs back to 4
    const size3Pill = page.locator('.js-rank-size-opt[data-rank-k="3"]');
    await expect(size3Pill).toBeVisible();
    await size3Pill.click();
    await expect(page.locator('.js-rank-card')).toHaveCount(3);
    await expect(page.locator('.js-rank-excluded-tray')).toBeVisible();
    await expect(page.locator('.js-rank-excluded-label')).toContainText('Set aside at this size (1)');
    await expect(page.locator('.js-rank-restore-chip')).toHaveCount(1);
    const skipCoveredAt3 = await page
      .locator('#rank-skip-form input[name="covered"]')
      .inputValue();
    expect(skipCoveredAt3.split('.').length).toBe(3);
    await assertAccessible(page, '/rank/design-classics (resized to 3)');

    await page.locator('.js-rank-card').last().locator('.js-card-exclude').click();
    await expect(page.locator('.js-rank-card')).toHaveCount(2);
    await expect(page.locator('.js-rank-card').first().locator('.js-card-exclude')).toBeDisabled();
    await expect(page.locator('.js-rank-excluded-label')).toContainText('Left out of this comparison (2)');
    await expect(page.locator('.js-rank-restore-chip')).toHaveCount(2);
    await assertAccessible(page, '/rank/design-classics (excluded down to 2)');

    await page.locator('.js-rank-restore-all').click();
    await expect(page.locator('.js-rank-card')).toHaveCount(4);
    await expect(page.locator('.js-rank-excluded-tray')).toBeHidden();
    await expect(page.locator('.js-rank-size-opt[data-rank-k="4"]')).toHaveAttribute(
      'aria-current',
      'true'
    );
    await page.keyboard.press('+');
    await expect(page.locator('.js-rank-card')).toHaveCount(4);

    // Submit the comparison, verify Undo banner, then verify RankedTable renders numeric ScoreMark values cleanly
    const [submitResp] = await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === 'POST' && r.url().includes('/submit')
      ),
      page.locator('.rank-actions button.btn-primary').click(),
    ]);
    expect(submitResp.status()).toBe(201);
    await expect(page.locator('.undo-banner')).toBeVisible();

    await gotoAndAssertOk(page, baseUrl, '/lists/design-classics', '/lists/design-classics');
    await expect(page.locator('table.ranked tbody tr').first()).toBeVisible();
    const scoreCells = page.locator('table.ranked .score-num');
    await expect(scoreCells).toHaveCount(4);
    const firstScoreText = (await scoreCells.first().textContent())?.trim() ?? '';
    expect(firstScoreText).toMatch(/^\d+\.\d$/);
    const vpWidth = page.viewportSize()?.width ?? 1280;
    if (vpWidth > 600) {
      await expect(scoreCells.first()).toBeVisible();
    }
    await assertAccessible(page, '/lists/design-classics (ranked with ScoreMark)');

    // 4. Global search (/search), masthead + Log modal, unified Diary status labels
    await gotoAndAssertOk(page, baseUrl, '/search?q=Design', '/search');
    await expect(page.locator('#roche-global-search')).toHaveValue('Design');
    await expect(page.locator('#search-catalog')).toBeVisible();
    await assertAccessible(page, '/search?q=Design');

    await page.locator('#roche-quick-log-btn').click();
    const qlDialog = page.locator('#roche-quick-log-dialog');
    await expect(qlDialog).toBeVisible();
    await assertAccessible(page, 'Quick Log modal (Media tab)');
    await page.locator('#roche-ql-tab-url').click();
    await expect(page.locator('#qlm-url')).toBeVisible();
    await assertAccessible(page, 'Quick Log modal (Save URL tab)');
    await qlDialog.locator('.js-modal-close').first().click();
    await expect(qlDialog).not.toBeVisible();

    await gotoAndAssertOk(page, baseUrl, '/diary', '/diary');
    await expect(page.locator('.diary-toolbar-pills')).toContainText('Finished');
    await expect(page.locator('.diary-toolbar-pills')).toContainText('Skipped');
    await assertAccessible(page, '/diary (unified status labels)');
  });

  test('boards empty onboarding, populated ranked stream, backfill/undo, reader, and dark mode pass axe WCAG AA checks', async ({
    page,
  }) => {
    await page.context().addCookies(sessionCookies);

    // 1. Empty Boards onboarding state passes axe WCAG 2.1/2.2 AA
    await gotoAndAssertOk(page, baseUrl, '/boards', '/boards');
    await expect(page.locator('.board-empty-onboarding')).toBeVisible();
    await expect(page.locator('.board-quick-add-btn')).toHaveCount(4);
    await assertAccessible(page, '/boards (empty onboarding)');

    // 2. Seed a deterministic populated Hacker News board via authenticated peer sync API
    //    (zero external network calls; includes Ask HN self-post, PDF fallback item, and 32 stories for backfill)
    const apiPairCode = execFileSync(serverBin, ['--data-dir', dataDir!, 'pair'], {
      encoding: 'utf8',
      env: rocheTestEnv(),
    }).trim();
    const pairRes = await fetch(`${baseUrl}/api/v1/auth/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ device_name: 'a11y-seeder', pairing_code: apiPairCode }),
    });
    expect(pairRes.status).toBe(200);
    const { token } = (await pairRes.json()) as { token: string };

    const snapIso = new Date(Date.now() - 5 * 60_000).toISOString();
    const hlcMs = String(Date.now()).padStart(15, '0');
    const snapshotItems = [
      {
        external_id: '40001',
        rank: 1,
        title: 'Ask HN: Accessible editorial web typography patterns?',
        url: 'https://news.ycombinator.com/item?id=40001',
        discussion_url: 'https://news.ycombinator.com/item?id=40001',
        author: 'alice',
        domain: null,
        published_at: snapIso,
        score: 245,
        comment_count: 38,
        summary: 'Collecting favorite print-inspired type scales, contrast rules, and reading layouts.',
        self_text_html:
          '<h3>Background</h3><p>Collecting favorite print-inspired type scales and contrast rules.</p><h3>Questions</h3><p>What serif stacks age best across macOS, Linux, and mobile?</p>',
        metadata_json: '{}',
        fetched_at: snapIso,
      },
      {
        external_id: '40002',
        rank: 2,
        title: 'A Note on Distributed Systems Typography (PDF)',
        url: 'https://example.org/papers/distributed-typography.pdf',
        discussion_url: 'https://news.ycombinator.com/item?id=40002',
        author: 'bob',
        domain: 'example.org',
        published_at: snapIso,
        score: 192,
        comment_count: 64,
        summary: null,
        self_text_html: null,
        metadata_json: '{}',
        fetched_at: snapIso,
      },
      ...Array.from({ length: 30 }, (_, idx) => {
        const rank = idx + 3;
        const extId = String(40000 + rank);
        return {
          external_id: extId,
          rank,
          title: `Ranked Engineering Story #${rank}`,
          url: `https://stories.example.org/post-${rank}`,
          discussion_url: `https://news.ycombinator.com/item?id=${extId}`,
          author: 'builder',
          domain: 'stories.example.org',
          published_at: snapIso,
          score: 180 - idx * 3,
          comment_count: 10 + idx,
          summary: null,
          self_text_html: null,
          metadata_json: '{}',
          fetched_at: snapIso,
        };
      }),
    ];

    const syncRes = await fetch(`${baseUrl}/api/v1/sync/peer`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        peer_id: 'a11y-seeder',
        since_seq: 0,
        boards: [
          {
            id: '',
            adapter_type: 'hacker_news',
            source_key: 'top',
            source_url: 'https://news.ycombinator.com/',
            source_config_json: '{"section":"top"}',
            title: 'Hacker News',
            order_mode: 'ranked',
            poll_interval: 30,
            is_active: true,
            last_polled_at: snapIso,
            last_polled_by: 'a11y-seeder',
            snapshot_fetched_at: snapIso,
            snapshot_hlc: `${hlcMs}-0001-a11y-seeder`,
            created_at: snapIso,
            updated_at: snapIso,
            hlc: `${hlcMs}-0000-a11y-seeder`,
            snapshot_items: snapshotItems,
          },
        ],
      }),
    });
    expect(syncRes.status).toBe(200);
    const syncData = (await syncRes.json()) as { boards: Array<{ id: string }> };
    const boardId = syncData.boards[0]?.id;
    expect(boardId).toBeTruthy();

    const assertNoHorizontalOverflow = async (label: string) => {
      const { scrollWidth, clientWidth } = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }));
      expect(
        scrollWidth,
        `${label}: horizontal overflow (${scrollWidth} > ${clientWidth})`
      ).toBeLessThanOrEqual(clientWidth);
    };

    // 3. Populated /boards view passes axe WCAG AA (light & dark themes) + zero overflow + Settings popover
    await gotoAndAssertOk(page, baseUrl, `/boards/${boardId}`, `/boards/${boardId}`);
    await expect(page.locator('#roche-board-list .board-row')).toHaveCount(30);
    await expect(page.locator('.board-comments-link').first()).toContainText('38 comments');
    await assertNoHorizontalOverflow('/boards (populated)');
    await assertAccessible(page, '/boards (populated light)');

    await page.locator('.board-settings-details > summary').click();
    await expect(page.locator('.board-settings-panel')).toBeVisible();
    await assertNoHorizontalOverflow('/boards (settings popover open)');
    const settingsBox = await page.locator('.board-settings-panel').boundingBox();
    const viewportWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(settingsBox).toBeTruthy();
    expect(settingsBox!.x).toBeGreaterThanOrEqual(0);
    expect(settingsBox!.x + settingsBox!.width).toBeLessThanOrEqual(viewportWidth + 1);
    await assertAccessible(page, '/boards (settings popover open)');
    await page.keyboard.press('Escape');
    await expect(page.locator('.board-settings-panel')).toBeHidden();

    await page.locator('#roche-theme-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.locator('nav.primary-nav a.is-active')).toHaveCSS(
      'color',
      'rgb(229, 112, 104)'
    );
    await expect(page.locator('.board-comments-link').first()).toHaveCSS(
      'color',
      'rgb(230, 157, 103)'
    );
    await expect(page.locator('.board-comments-link').first()).toHaveCSS(
      'background-color',
      'rgb(33, 29, 24)'
    );
    await page.evaluate(() =>
      Promise.all(
        document
          .getAnimations()
          .filter((a) => a instanceof CSSTransition)
          .map((a) => a.finished.catch(() => undefined))
      )
    );
    await assertAccessible(page, '/boards (populated dark)');
    await page.locator('#roche-theme-toggle').click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await expect(page.locator('nav.primary-nav a.is-active')).toHaveCSS(
      'color',
      'rgb(143, 29, 24)'
    );
    await expect(page.locator('.board-comments-link').first()).toHaveCSS(
      'color',
      'rgb(124, 53, 18)'
    );
    await page.evaluate(() =>
      Promise.all(
        document
          .getAnimations()
          .filter((a) => a instanceof CSSTransition)
          .map((a) => a.finished.catch(() => undefined))
      )
    );

    // 4. Instant dismiss + backfill of #31 + Undo, then concurrent double-dismiss backfilling #31 & #32
    const thirdRow = page.locator('#roche-board-list .board-row[data-external-id="40003"]');
    const fourthRow = page.locator('#roche-board-list .board-row[data-external-id="40004"]');
    await thirdRow.locator('button[data-board-action="board-dismiss"]').click();
    await expect(thirdRow).toBeHidden();
    await expect(
      page.locator('#roche-board-list .board-row[data-external-id="40031"]')
    ).toBeVisible();
    const undoBtn = page.locator('#roche-toast-region .roche-toast-undo');
    await expect(undoBtn).toBeVisible();
    await undoBtn.click();
    await expect(thirdRow).toBeVisible();
    await expect(
      page.locator('#roche-board-list .board-row[data-external-id="40031"]')
    ).toHaveCount(0);

    // Trigger two simultaneous dismisses in the same tick to exercise pendingBackfills tracking
    await page.evaluate(() => {
      const b3 = document.querySelector(
        '#roche-board-list .board-row[data-external-id="40003"] button[data-board-action="board-dismiss"]'
      ) as HTMLButtonElement | null;
      const b4 = document.querySelector(
        '#roche-board-list .board-row[data-external-id="40004"] button[data-board-action="board-dismiss"]'
      ) as HTMLButtonElement | null;
      b3?.click();
      b4?.click();
    });
    await expect(thirdRow).toBeHidden();
    await expect(fourthRow).toBeHidden();
    await expect(
      page.locator('#roche-board-list .board-row[data-external-id="40031"]')
    ).toBeVisible();
    await expect(
      page.locator('#roche-board-list .board-row[data-external-id="40032"]')
    ).toBeVisible();
    await expect(page.locator('#roche-board-list .board-row:not([hidden])')).toHaveCount(30);
    await expect(undoBtn).toBeVisible();
    await undoBtn.click();
    await expect(page.locator('#roche-board-list .board-row:not([hidden])')).toHaveCount(30);

    // 5. Optimistic Star & + Queue toggles persist across reload and pass axe WCAG AA
    const firstRow = page.locator('#roche-board-list .board-row[data-external-id="40001"]');
    await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === 'POST' && r.url().includes('/promote-view')
      ),
      firstRow.locator('.inbox-star-btn').click(),
    ]);
    await expect(firstRow.locator('.inbox-star-btn')).toHaveAttribute('aria-pressed', 'true');
    await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === 'POST' && r.url().includes('/promote-view')
      ),
      firstRow.locator('button[data-board-action="board-queue"]').click(),
    ]);
    await expect(firstRow.locator('button[data-board-action="unqueue"]')).toContainText('In Queue');
    await assertAccessible(page, '/boards (after star & queue)');

    // Reload and verify Star + Queue hydrated accurately from server `is_favorite` / `in_want_to_read`
    await page.reload({ waitUntil: 'load' });
    const reloadedFirstRow = page.locator('#roche-board-list .board-row[data-external-id="40001"]');
    await expect(reloadedFirstRow.locator('.inbox-star-btn')).toHaveAttribute('aria-pressed', 'true');
    await expect(reloadedFirstRow.locator('button[data-board-action="unqueue"]')).toContainText('In Queue');

    // 6. Dismissed view passes axe WCAG AA & restoring last item reveals empty state cleanly
    await gotoAndAssertOk(
      page,
      baseUrl,
      `/boards/${boardId}?view=dismissed`,
      `/boards/${boardId}`
    );
    const restoreBtn = page
      .locator('#roche-board-list button[data-board-action="board-restore"]')
      .first();
    await expect(restoreBtn).toBeVisible();
    await assertAccessible(page, '/boards (dismissed view)');
    await restoreBtn.click();
    await expect(page.locator('#roche-board-dismissed-empty')).toBeVisible();
    await assertAccessible(page, '/boards (dismissed empty after restore)');

    // 7. On-demand Board Reader: Ask HN prose view + tailored PDF fallback card pass axe WCAG AA
    await gotoAndAssertOk(
      page,
      baseUrl,
      `/boards/${boardId}/read/40001`,
      `/boards/${boardId}/read/40001`
    );
    await expect(page.locator('.reader-prose')).toContainText('print-inspired type scales');
    await expect(page.locator('.reader-discussion-link')).toContainText('Hacker News (38 comments)');
    await expect(page.locator('.reader-star-btn')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.reader-quick-tools')).toContainText('In Queue');
    await assertAccessible(page, '/boards/{id}/read/40001 (Ask HN prose)');

    await gotoAndAssertOk(
      page,
      baseUrl,
      `/boards/${boardId}/read/40002`,
      `/boards/${boardId}/read/40002`
    );
    await expect(page.locator('.board-reader-fallback')).toBeVisible();
    await expect(page.locator('.board-reader-fallback')).toContainText('PDF document');
    await expect(page.locator('.board-reader-fallback')).toContainText('Open PDF');
    await expect(page.locator('.board-reader-fallback')).toContainText(
      'Open Hacker News Discussion (64 comments)'
    );
    await expect(page.locator('.js-font-dec')).toHaveCount(0);
    await expect(page.locator('.js-font-inc')).toHaveCount(0);
    await assertAccessible(page, '/boards/{id}/read/40002 (PDF fallback card)');
  });
});
