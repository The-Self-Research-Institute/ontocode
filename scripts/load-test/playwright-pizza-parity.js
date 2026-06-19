#!/usr/bin/env node
/**
 * Pizza tutorial parity — class hierarchy + graph view (Protégé vs OntoCode).
 *
 * Expects PizzaTutorialWithDataV2.owl (or similar) already uploaded.
 *
 *   node playwright-pizza-parity.js --project-name "..." --file-name PizzaTutorialWithDataV2.owl
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const RESULTS_DIR = path.join(__dirname, 'results');

const cfg = {
  uiBase: process.env.UI_BASE || 'http://localhost:3001',
  email: process.env.PW_EMAIL || 'admin@coretopia.com',
  password: process.env.PW_PASSWORD || 'LocalLoadTest1!',
  headless: process.env.HEADLESS === 'true',
};

const PIZZA_IRI = 'http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#';
const REQUIRED_TOP_LEVEL = ['Person', 'Pizza'];
const EMPLOYEE_LOCAL = 'Employee';

const results = [];
function record(name, ok, detail = '') {
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ': ' + detail : ''}`);
  results.push({ name, ok, detail });
}

async function login(page) {
  await page.goto(cfg.uiBase, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.fill('#username', cfg.email);
  await page.fill('#password', cfg.password);
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForTimeout(2500);
}

async function navigateToFile(page, projectName, fileName) {
  const url = `${cfg.uiBase}/projects/${encodeURIComponent(projectName)}/files/${encodeURIComponent(fileName)}`;
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('[data-class-id]', { timeout: 180000 });
}

async function expandThingIfNeeded(page) {
  const thing = page.locator('[data-class-id*="owl#Thing"]').first();
  if (!(await thing.isVisible({ timeout: 5000 }).catch(() => false))) return false;
  const btn = thing.locator('button').first();
  const svgClass = await btn.locator('svg').getAttribute('class').catch(() => '');
  if (svgClass?.includes('chevron-right')) {
    await btn.click();
    await page.waitForTimeout(1200);
  }
  return true;
}

async function testHierarchyPizzaBranches(page) {
  await expandThingIfNeeded(page);

  for (const name of REQUIRED_TOP_LEVEL) {
    const row = page.locator(`[data-class-id$="#${name}"]`).first();
    const found = await row.isVisible({ timeout: 5000 }).catch(() => false);
    record(`Hierarchy: ${name} visible`, found);
  }

  const thingRow = page.locator('[data-class-id*="owl#Thing"]').first();
  const employeeUnderThing = await thingRow
    .locator('xpath=following-sibling::div[1]//*[@data-class-id and contains(@data-class-id, "Employee")]')
    .first()
    .isVisible({ timeout: 1500 })
    .catch(() => false);

  const employeeIriVisible = await page.locator('[data-class-id$="#Employee"]').first().isVisible({ timeout: 3000 }).catch(() => false);
  record('Hierarchy: Employee NOT direct child of Thing', !employeeUnderThing, employeeUnderThing ? 'Employee at Thing top level' : 'ok');
  record('Hierarchy: Employee exists in tree', employeeIriVisible);
}

async function openGraphAndWait(page) {
  await page.getByRole('button', { name: /^Graph$/ }).first().click();
  await page.waitForTimeout(1500);
  await page.waitForSelector('[data-testid="graph-view"]', { timeout: 30000 });
  await page.waitForFunction(() => {
    const stats = document.querySelector('[data-testid="graph-stats"]');
    return stats && Number(stats.getAttribute('data-total-nodes') || 0) > 0;
  }, { timeout: 90000 });
  await page.locator('[data-testid="graph-preset-tree"]').click();
  await page.waitForTimeout(2000);
}

async function testGraphPizzaNodes(page) {
  const stats = page.locator('[data-testid="graph-stats"]');
  const total = Number(await stats.getAttribute('data-total-nodes') || 0);
  const visible = Number(await stats.getAttribute('data-visible-nodes') || 0);
  record('Graph: loads pizza ontology', total > 20, `total=${total}`);

  if (total <= 400) {
    const ratio = total > 0 ? visible / total : 0;
    record('Graph: shows most nodes for pizza size', ratio >= 0.5, `${visible}/${total}`);
  }

  for (const name of REQUIRED_TOP_LEVEL) {
    const node = page.locator(`[data-graph-node-id$="#${name}"]`).first();
    const found = await node.isVisible({ timeout: 3000 }).catch(() => false)
      || await page.locator('[data-testid="graph-svg"] text').filter({ hasText: new RegExp(`^${name}$`, 'i') }).first().isVisible({ timeout: 2000 }).catch(() => false);
    record(`Graph: ${name} node visible`, found);
  }

  const employeeNode = page.locator(`[data-graph-node-id$="#${EMPLOYEE_LOCAL}"]`).first();
  const employeeVisible = await employeeNode.isVisible({ timeout: 2000 }).catch(() => false);
  record('Graph: Employee node present (under Person branch)', employeeVisible, employeeVisible ? 'ok' : 'not in visible set — expand Person in tree');
}

async function main() {
  const args = process.argv.slice(2);
  let projectName = '', fileName = process.env.PIZZA_FILE || 'PizzaTutorialWithDataV2.owl';
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--project-name') projectName = args[++i];
    if (args[i] === '--file-name') fileName = args[++i];
  }
  if (!projectName) {
    console.error('Pass --project-name (pizza file must already be in the project)');
    process.exit(1);
  }

  console.log(`\n🍕 Pizza Parity — ${projectName}/${fileName}\n`);

  const browser = await chromium.launch({ headless: cfg.headless });
  const page = await browser.newPage();
  await page.addInitScript(() => localStorage.setItem('deploymentType', 'self-hosted'));

  try {
    await login(page);
    await navigateToFile(page, projectName, fileName);

    console.log('\n── Class Hierarchy (Pizza) ──');
    await testHierarchyPizzaBranches(page);

    console.log('\n── Graph View (Pizza) ──');
    await openGraphAndWait(page);
    await testGraphPizzaNodes(page);
  } finally {
    await browser.close();
  }

  const passed = results.filter(r => r.ok).length;
  const report = {
    finishedAt: new Date().toISOString(),
    ontology: PIZZA_IRI,
    projectName,
    fileName,
    results,
    summary: { passed, failed: results.length - passed, total: results.length },
  };
  fs.mkdirSync(RESULTS_DIR, { recursive: true });
  fs.writeFileSync(path.join(RESULTS_DIR, 'playwright-pizza-parity.json'), JSON.stringify(report, null, 2));

  console.log(`\n${passed}/${results.length} passed`);
  if (passed < results.length) process.exit(1);
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
