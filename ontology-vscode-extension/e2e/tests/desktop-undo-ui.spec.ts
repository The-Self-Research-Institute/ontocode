import { test, expect } from '../fixtures/electronDesktop';
import { loadDesktopAuth } from '../helpers/desktopApi';
import {
  addNamed,
  addPrefix,
  changeCards,
  currentProjectId,
  deletePrefix,
  newestCardTitle,
  openActiveOntology,
  openChangeAssistant,
  openEditor,
  openEntitiesSubTab,
  pressKeyInMainProcess,
  selectEntity,
  setOntologyIri,
  undoAndWait,
} from '../helpers/undoUi';

async function post(apiBase: string, token: string, path: string, body: unknown = {}) {
  const res = await fetch(`${apiBase}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  });
  return { status: res.status, body: (await res.json().catch(() => ({}))) as Record<string, any> };
}

test.describe.configure({ mode: 'serial' });

test.describe('desktop undo and redo through the UI', () => {
  test.beforeEach(async ({ page, electronApp }) => {
    await openEditor(electronApp, page);
  });

  test('the draft controls are not shown on desktop', async ({ page }) => {
    await expect(page.getByRole('button', { name: 'Active ontology', exact: true }).first()).toBeVisible();
    await expect(page.getByText(/Private \(Draft\)|Public \(Live\)/)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Pull', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Discard', exact: true })).toHaveCount(0);
  });

  test('an ontology IRI change is named in the Change Assistant and undo and redo update the view', async ({ page, electronApp }) => {
    await openActiveOntology(page);
    const before = 'http://example.org/demo/pizza';
    await expect(page.getByTestId('ontology-iri-value')).toHaveText(before, { timeout: 60_000 });
    const changed = `${before}-changed`;

    await setOntologyIri(page, changed);
    await expect(page.getByTestId('ontology-iri-value')).toHaveText(changed, { timeout: 30_000 });

    await openChangeAssistant(page);
    expect(await newestCardTitle(page)).toContain('Ontology IRI changed');

    await undoAndWait(electronApp, page, 'undo');
    await openActiveOntology(page);
    await expect(page.getByTestId('ontology-iri-value')).toHaveText(before, { timeout: 30_000 });

    await undoAndWait(electronApp, page, 'redo');
    await expect(page.getByTestId('ontology-iri-value')).toHaveText(changed, { timeout: 30_000 });
  });

  test('built-in prefixes cannot be deleted, and a prefix you add can be deleted, undone and redone', async ({ page, electronApp }) => {
    await openActiveOntology(page);
    await page.getByRole('button', { name: 'Ontology Prefixes' }).click();
    const builtIn = page.locator('[data-testid="prefix-row"][data-prefix^="xsd"]');
    await expect(builtIn).toBeVisible({ timeout: 30_000 });
    await expect(builtIn.getByTitle('Delete')).toHaveCount(0);

    await addPrefix(page, 'pizzae2e', 'http://example.org/pizzae2e#');
    await deletePrefix(page, 'pizzae2e');
    await expect(page.locator('[data-testid="prefix-row"][data-prefix^="pizzae2e"]')).toHaveCount(0, { timeout: 30_000 });
    await openChangeAssistant(page);
    expect((await newestCardTitle(page)).toLowerCase()).toContain('pizzae2e');

    await undoAndWait(electronApp, page, 'undo');
    await openActiveOntology(page);
    await page.getByRole('button', { name: 'Ontology Prefixes' }).click();
    await expect(page.locator('[data-testid="prefix-row"][data-prefix^="pizzae2e"]')).toHaveCount(1, { timeout: 30_000 });
    await undoAndWait(electronApp, page, 'redo');
    await openActiveOntology(page);
    await page.getByRole('button', { name: 'Ontology Prefixes' }).click();
    await expect(page.locator('[data-testid="prefix-row"][data-prefix^="pizzae2e"]')).toHaveCount(0, { timeout: 30_000 });
  });

  test('a data property domain is recorded, shown, and can be undone and redone', async ({ page, electronApp }) => {
    await openEntitiesSubTab(page, 'Data properties');
    await selectEntity(page, 'hasCalories');

    await page.getByRole('button', { name: /Domains \(Intersection\)/ }).click();
    const dialog = page.locator('div.fixed.inset-0').filter({ has: page.getByRole('heading', { name: 'Add Domain Class Expression' }) });
    await dialog.locator('[data-class-id*="Vehicle"]').first().click();
    await dialog.getByRole('button', { name: 'OK', exact: true }).click();

    const domainWithVehicle = page.locator('[data-testid="multi-section-Domains (Intersection)"]', { hasText: 'Vehicle' });
    await expect(domainWithVehicle).toHaveCount(1, { timeout: 30_000 });

    await openChangeAssistant(page);
    expect(await changeCards(page).count()).toBeGreaterThan(0);

    await undoAndWait(electronApp, page, 'undo');
    await openEntitiesSubTab(page, 'Data properties');
    await selectEntity(page, 'hasCalories');
    await expect(domainWithVehicle).toHaveCount(0, { timeout: 30_000 });

    await undoAndWait(electronApp, page, 'redo');
    const refreshed = await expect(domainWithVehicle).toHaveCount(1, { timeout: 8_000 }).then(() => true, () => false);
    await openEntitiesSubTab(page, 'Data properties');
    await selectEntity(page, 'hasCalories');
    await expect(domainWithVehicle, 'redo should restore the domain on the server').toHaveCount(1, { timeout: 30_000 });
    expect(refreshed, 'the open view should refresh by itself after redo').toBe(true);
  });

  test('a sub-property of a data property can be undone and redone', async ({ page, electronApp }) => {
    await openEntitiesSubTab(page, 'Data properties');
    await selectEntity(page, 'hasSpeed');

    await addNamed(page, 'Add sub property', 'Enter property name', 'hasTopSpeed');
    await expect(page.locator('[data-class-id*="hasTopSpeed"]').first()).toBeVisible({ timeout: 30_000 });

    await undoAndWait(electronApp, page, 'undo');
    await expect(page.locator('[data-class-id*="hasTopSpeed"]')).toHaveCount(0, { timeout: 30_000 });

    await undoAndWait(electronApp, page, 'redo');
    await expect(page.locator('[data-class-id*="hasTopSpeed"]').first()).toBeVisible({ timeout: 30_000 });
  });

  test('a subclass of a class can be undone and redone', async ({ page, electronApp }) => {
    await openEntitiesSubTab(page, 'Classes');
    await selectEntity(page, 'Truck');

    await addNamed(page, 'Add subclass', 'Enter class name', 'PickupTruck');
    await expect(page.locator('[data-class-id*="PickupTruck"]').first()).toBeVisible({ timeout: 30_000 });

    await undoAndWait(electronApp, page, 'undo');
    await expect(page.locator('[data-class-id*="PickupTruck"]')).toHaveCount(0, { timeout: 30_000 });

    await undoAndWait(electronApp, page, 'redo');
    await expect(page.locator('[data-class-id*="PickupTruck"]').first()).toBeVisible({ timeout: 30_000 });
  });

  test('expanding a change set sends at most one undo preview request', async ({ page, apiBase }) => {
    const projectId = await currentProjectId(page);
    test.skip(!projectId, 'could not read the open project id');
    const auth = loadDesktopAuth();
    const made = await post(apiBase, auth.token, `/api/ontology/mutations/${projectId}?draft=false`, {
      ops: [
        { type: 'createClass', iri: 'http://e2e.test/Preview1', label: 'Preview1' },
        { type: 'createClass', iri: 'http://e2e.test/Preview2', label: 'Preview2' },
      ],
      userId: auth.user,
      username: auth.user,
    });
    expect(made.status).toBe(200);
    await openChangeAssistant(page);

    let previews = 0;
    page.on('request', (req) => {
      if (/undo\?dryRun=true/.test(req.url())) previews += 1;
    });
    await changeCards(page).first().locator('button.cs-toggle').click();
    await page.waitForTimeout(4_000);
    expect(previews).toBeLessThanOrEqual(1);
  });

  test('the Ctrl+Z and Ctrl+Y keyboard shortcuts reach the app', async ({ page, electronApp }) => {
    await openActiveOntology(page);
    await page.locator('body').click({ position: { x: 5, y: 5 } });
    const undone = page.waitForResponse((res) => /\/undo\?/.test(res.url()) && res.request().method() === 'POST', { timeout: 20_000 });
    await pressKeyInMainProcess(electronApp, 'Z', ['control']);
    expect((await undone.catch(() => null)), 'Ctrl+Z should produce an undo request').not.toBeNull();
    const redone = page.waitForResponse((res) => /\/redo\?/.test(res.url()) && res.request().method() === 'POST', { timeout: 20_000 });
    await pressKeyInMainProcess(electronApp, 'Y', ['control']);
    expect((await redone.catch(() => null)), 'Ctrl+Y should produce a redo request').not.toBeNull();
  });
});
