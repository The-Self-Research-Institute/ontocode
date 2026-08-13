#!/usr/bin/env node

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const RESULTS_DIR = path.join(__dirname, 'results');

const cfg = {
  uiBase: process.env.UI_BASE || 'http://localhost:3001',
  apiBase: process.env.API_BASE || 'http://localhost',
  email: process.env.PW_EMAIL || 'admin@coretopia.com',
  password: process.env.PW_PASSWORD || 'LocalLoadTest1!',
  headless: process.env.HEADLESS === 'true',
};

function loadIdsFromLatest() {
  const p = path.join(RESULTS_DIR, 'playwright-latest.json');
  if (!fs.existsSync(p)) return null;
  const j = JSON.parse(fs.readFileSync(p, 'utf8'));
  const user = j.users?.[0];
  const upload = user?.steps?.upload?.json;
  if (!upload?.projectId || !upload?.fileId) return null;
  return {
    projectId: upload.projectId,
    fileId: upload.fileId,
    fileName: upload.filename || user?.fileName || '',
    projectName: user?.projectName || '',
  };
}

const results = [];
function record(name, ok, detail = '') {
  const icon = ok ? '✅' : '❌';
  console.log(`${icon} ${name}${detail ? ': ' + detail : ''}`);
  results.push({ name, ok, detail });
}

async function login(page) {
  await page.goto(cfg.uiBase, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('#username', { timeout: 15000 });
  await page.fill('#username', cfg.email);
  await page.fill('#password', cfg.password);
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForResponse(r => /\/api\/auth\/login/.test(r.url()), { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(2000);
  if (await page.getByText(/Select a Workspace/i).isVisible({ timeout: 5000 }).catch(() => false)) {
    await page.locator('div.cursor-pointer').filter({ has: page.locator('h3') }).first().click();
    await page.waitForTimeout(2000);
  }
}

async function navigateToFileEditor(page, projectName, fileName) {
  const editorUrl = `${cfg.uiBase}/projects/${encodeURIComponent(projectName)}/files/${encodeURIComponent(fileName)}`;
  await page.goto(editorUrl, { waitUntil: 'domcontentloaded', timeout: 20000 });
  await page.waitForTimeout(2000);

  const reached = await Promise.race([
    page.waitForSelector('[data-class-id]', { timeout: 180000 }).then(() => 'hierarchy'),
    page.locator('[data-testid="import-open-btn"]').first().waitFor({ state: 'visible', timeout: 180000 }).then(() => 'import-done'),
  ]).catch(() => null);

  if (reached === 'import-done') {
    const importOpenBtn = page.locator('[data-testid="import-open-btn"]').first();
    if (await importOpenBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await importOpenBtn.click();
      await page.waitForTimeout(3000);
    }
  }
  return reached !== null;
}

async function openGraphTab(page) {
  const graphTab = page.getByRole('button', { name: /^Graph$/ }).first();
  await graphTab.waitFor({ state: 'visible', timeout: 15000 });
  await graphTab.click();
  await page.waitForTimeout(1500);
}

async function waitForGraphReady(page, timeout = 90000) {
  await page.waitForSelector('[data-testid="graph-view"]', { timeout });
  await page.waitForFunction(() => {
    const root = document.querySelector('[data-testid="graph-view"]');
    if (!root) return false;
    if (root.getAttribute('data-graph-loading') === 'true') return false;
    const total = Number(root.querySelector('[data-testid="graph-stats"]')?.getAttribute('data-total-nodes') || 0);
    return total > 0;
  }, { timeout });
}

async function readGraphStats(page) {
  const stats = page.locator('[data-testid="graph-stats"]');
  return {
    visible: Number(await stats.getAttribute('data-visible-nodes') || 0),
    total: Number(await stats.getAttribute('data-total-nodes') || 0),
    expanded: Number(await stats.getAttribute('data-expanded-nodes') || 0),
    text: (await stats.textContent())?.trim() || '',
  };
}

async function testGraphTabOpens(page) {
  await openGraphTab(page);
  const visible = await page.locator('[data-testid="graph-view"]').isVisible({ timeout: 10000 }).catch(() => false);
  record('Graph tab opens graph view', visible);
  return visible;
}

async function testGraphLoadsNodes(page) {
  try {
    await waitForGraphReady(page);
  } catch {
    record('Graph loads ontology nodes', false, 'timeout waiting for nodes');
    return false;
  }
  const { total, visible } = await readGraphStats(page);
  const ok = total > 0;
  record('Graph loads ontology nodes', ok, `total=${total}, visible=${visible}`);
  return ok;
}

async function testSmallOntologyShowsMostNodes(page) {
  const { total, visible } = await readGraphStats(page);
  if (total === 0) {
    record('Small ontology shows most nodes on load', false, 'no nodes');
    return false;
  }
  if (total > 400) {
    record('Small ontology shows most nodes on load', true, `skipped — large ontology (${total} nodes)`);
    return true;
  }
  const ratio = visible / total;
  const ok = ratio >= 0.5;
  record('Small ontology shows most nodes on load', ok, `${visible}/${total} (${Math.round(ratio * 100)}%)`);
  return ok;
}

async function testDefaultTreeMode(page) {
  const mode = await page.locator('[data-testid="graph-view"]').getAttribute('data-graph-mode');
  const ok = mode === 'ontograph';
  record('Graph defaults to tree (ontograph) mode', ok, mode || 'unknown');
  return ok;
}

async function testTreePresetExpandsAll(page) {
  await page.locator('[data-testid="graph-preset-tree"]').click();
  await page.waitForTimeout(1200);
  const { total, visible } = await readGraphStats(page);
  if (total === 0 || total > 400) {
    record('Tree preset expands class hierarchy', true, `skipped (${total} nodes)`);
    return true;
  }
  const ok = visible >= Math.min(total, Math.ceil(total * 0.8));
  record('Tree preset expands class hierarchy', ok, `${visible}/${total} visible`);
  return ok;
}

async function testNetworkPreset(page) {
  await page.locator('[data-testid="graph-preset-network"]').click();
  await page.waitForTimeout(1200);
  const mode = await page.locator('[data-testid="graph-view"]').getAttribute('data-graph-mode');
  const svgNodes = await page.locator('[data-testid="graph-svg"] [data-testid="graph-node"]').count();
  const ok = mode === 'force' && svgNodes > 0;
  record('Network preset renders force-directed nodes', ok, `mode=${mode}, nodes=${svgNodes}`);
  return ok;
}

async function testGraphSvgHasClassLabels(page) {
  await page.locator('[data-testid="graph-preset-tree"]').click();
  await page.waitForTimeout(1500);
  const labels = await page.locator('[data-testid="graph-svg"] text').allTextContents();
  const joined = labels.join(' ').toLowerCase();
  const hasClassLabel = joined.includes('thing') || joined.includes('vehicle') || joined.includes('pizza') || joined.includes('animal');
  record('Graph SVG shows class labels', hasClassLabel, hasClassLabel ? 'found class label' : `sample: ${labels.slice(0, 5).join(', ')}`);
  return hasClassLabel;
}

async function testGraphRefresh(page) {
  const refreshBtn = page.locator('[data-testid="graph-view"] button[title="Refresh graph"]');
  const visible = await refreshBtn.isVisible({ timeout: 5000 }).catch(() => false);
  if (!visible) {
    record('Graph refresh button works', false, 'button not found');
    return false;
  }
  await refreshBtn.click();
  await page.waitForTimeout(2000);
  const { total } = await readGraphStats(page);
  const ok = total > 0;
  record('Graph refresh button works', ok, `total=${total}`);
  return ok;
}

async function testInsightsPanel(page) {
  await page.locator('[data-testid="graph-insights-toggle"]').click();
  await page.waitForTimeout(800);
  const panel = page.locator('[data-testid="graph-analytics-panel"]');
  const visible = await panel.isVisible({ timeout: 5000 }).catch(() => false);
  const concepts = await page.locator('[data-testid="graph-top-concept"]').count();
  record('Insights panel shows top concepts', visible && concepts > 0, `${concepts} concepts`);
  return visible;
}

async function testLocalGraphPane(page) {
  const pane = page.locator('[data-testid="graph-local-pane"]');
  const visible = await pane.isVisible({ timeout: 5000 }).catch(() => false);
  const local = await page.locator('[data-testid="local-graph-view"]').isVisible().catch(() => false);
  record('Local graph pane (Obsidian-style)', visible && local);
  return visible && local;
}

async function testColorByCluster(page) {
  await page.locator('[data-testid="graph-insights-toggle"]').click();
  await page.waitForTimeout(600);
  const toggle = page.locator('[data-testid="graph-color-by-cluster"]');
  if (!(await toggle.isVisible({ timeout: 3000 }).catch(() => false))) {
    record('Color clusters toggle', false, 'not found');
    return false;
  }
  await toggle.check();
  await page.waitForTimeout(800);
  record('Color clusters toggle', true);
  return true;
}

async function main() {
  const args = process.argv.slice(2);
  let projectName = '', fileName = '';
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--project-name') projectName = args[++i];
    if (args[i] === '--file-name') fileName = args[++i];
  }
  if (!projectName || !fileName) {
    const ids = loadIdsFromLatest();
    if (!ids) {
      console.error('Pass --project-name and --file-name (or run upload test first for playwright-latest.json)');
      process.exit(1);
    }
    projectName = ids.projectName;
    fileName = ids.fileName;
  }

  console.log(`\n🧪 Graph View Test — ${projectName}/${fileName}\n`);

  const browser = await chromium.launch({ headless: cfg.headless });
  const page = await browser.newPage();
  await page.addInitScript(() => localStorage.setItem('deploymentType', 'self-hosted'));

  try {
    await login(page);
    record('Login successful', !(await page.locator('text=Sign In').isVisible({ timeout: 1000 }).catch(() => false)));

    const opened = await navigateToFileEditor(page, projectName, fileName);
    record('Editor opens ontology file', opened);

    console.log('\n── Graph View ──');
    if (!(await testGraphTabOpens(page))) {
      console.log('\n⚠️  Graph tab failed — skipping remaining graph tests');
    } else {
      await testGraphLoadsNodes(page);
      await testDefaultTreeMode(page);
      await testSmallOntologyShowsMostNodes(page);
      await testTreePresetExpandsAll(page);
      await testGraphSvgHasClassLabels(page);
      await testNetworkPreset(page);
      await testGraphRefresh(page);
      await testLocalGraphPane(page);
      await testInsightsPanel(page);
      await testColorByCluster(page);
    }
  } finally {
    await browser.close();
  }

  const passed = results.filter(r => r.ok).length;
  const failed = results.filter(r => !r.ok).length;
  const report = {
    finishedAt: new Date().toISOString(),
    cfg: { uiBase: cfg.uiBase, apiBase: cfg.apiBase, headless: cfg.headless },
    projectName,
    fileName,
    results,
    summary: { passed, failed, total: results.length },
  };
  fs.mkdirSync(RESULTS_DIR, { recursive: true });
  fs.writeFileSync(path.join(RESULTS_DIR, 'playwright-graph-view.json'), JSON.stringify(report, null, 2));

  console.log(`\n${'─'.repeat(48)}`);
  console.log(`Results: ${passed}/${results.length} passed`);
  if (failed > 0) process.exit(1);
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
