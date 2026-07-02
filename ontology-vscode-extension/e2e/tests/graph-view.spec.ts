/**
 * Graph view — tree layout, node visibility, Network/Tree presets.
 */
import { test, expect } from '@playwright/test';
import { EditorPage } from '../helpers/EditorPage';

test.beforeEach(async ({ page }) => {
  const editor = new EditorPage(page);
  await editor.login();
  await editor.openFirstProject();
  await editor.openFirstFile();
});

test.describe('Graph view', () => {

  test('opens and loads nodes from ontology', async ({ page }) => {
    const editor = new EditorPage(page);
    await editor.openGraphTab();

    const root = page.locator('[data-testid="graph-view"]');
    await expect(root).toBeVisible();
    await expect(root).toHaveAttribute('data-graph-loading', 'false');

    const stats = await editor.getGraphStats();
    expect(stats.total).toBeGreaterThan(0);
    expect(stats.visible).toBeGreaterThan(0);
  });

  test('defaults to tree (ontograph) mode', async ({ page }) => {
    const editor = new EditorPage(page);
    await editor.openGraphTab();
    await expect(page.locator('[data-testid="graph-view"]')).toHaveAttribute('data-graph-mode', 'ontograph');
  });

  test('small ontology shows majority of nodes on first paint', async ({ page }) => {
    const editor = new EditorPage(page);
    await editor.openGraphTab();
    const { total, visible } = await editor.getGraphStats();
    test.skip(total > 400, 'Large ontology uses lazy loading');
    expect(visible / total).toBeGreaterThanOrEqual(0.5);
  });

  test('tree preset renders labeled nodes in SVG', async ({ page }) => {
    const editor = new EditorPage(page);
    await editor.openGraphTab();
    await page.locator('[data-testid="graph-preset-tree"]').click();
    await page.waitForTimeout(1200);

    await expect(page.locator('[data-testid="graph-svg"]')).toBeVisible();
    const nodeCount = await page.locator('[data-testid="graph-svg"] [data-testid="graph-node"]').count();
    expect(nodeCount).toBeGreaterThan(0);

    const svgText = await page.locator('[data-testid="graph-svg"] text').allTextContents();
    const blob = svgText.join(' ').toLowerCase();
    expect(blob.length).toBeGreaterThan(0);
  });

  test('network preset switches to force mode', async ({ page }) => {
    const editor = new EditorPage(page);
    await editor.openGraphTab();
    await page.locator('[data-testid="graph-preset-network"]').click();
    await page.waitForTimeout(1200);
    await expect(page.locator('[data-testid="graph-view"]')).toHaveAttribute('data-graph-mode', 'force');
  });

  test('local graph pane shows neighborhood', async ({ page }) => {
    const editor = new EditorPage(page);
    await editor.openGraphTab();
    await expect(page.locator('[data-testid="graph-local-pane"]')).toBeVisible();
    await expect(page.locator('[data-testid="local-graph-view"]')).toBeVisible();
  });

  test('insights panel opens with top concepts', async ({ page }) => {
    const editor = new EditorPage(page);
    await editor.openGraphTab();
    await page.locator('[data-testid="graph-insights-toggle"]').click();
    await expect(page.locator('[data-testid="graph-analytics-panel"]')).toBeVisible({ timeout: 8000 });
    const concepts = page.locator('[data-testid="graph-top-concept"]');
    expect(await concepts.count()).toBeGreaterThan(0);
  });

});

test.describe('Class hierarchy (smoke)', () => {

  test('hierarchy rows load before graph tab', async ({ page }) => {
    const rows = page.locator('[data-class-id]');
    await expect(rows.first()).toBeVisible({ timeout: 15_000 });
    expect(await rows.count()).toBeGreaterThan(0);
  });

});
