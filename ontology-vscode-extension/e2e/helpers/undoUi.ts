import type { ElectronApplication, Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';
import fs from 'fs';
import path from 'path';

export async function sendUndoRedo(app: ElectronApplication, kind: 'undo' | 'redo') {
  await app.evaluate(({ BrowserWindow }, which) => {
    const windows = BrowserWindow.getAllWindows();
    const target = windows.find((w) => !w.webContents.getURL().startsWith('devtools')) || windows[0];
    target.webContents.send('menu:undo-redo', which);
  }, kind);
}

export async function undoAndWait(app: ElectronApplication, page: Page, kind: 'undo' | 'redo' = 'undo') {
  const responded = page.waitForResponse(
    (res) => new RegExp(`/api/ontology/[^/]+/${kind}\\?`).test(res.url()) && res.request().method() === 'POST',
    { timeout: 30_000 },
  );
  await sendUndoRedo(app, kind);
  const res = await responded;
  expect(res.status(), `${kind} response`).toBeLessThan(400);
  return res;
}

export async function openMainTab(page: Page, name: string) {
  await page.getByRole('button', { name, exact: true }).first().click();
}

export async function openActiveOntology(page: Page) {
  await openMainTab(page, 'Active ontology');
  await expect(page.getByTestId('ontology-iri-value')).toBeVisible({ timeout: 60_000 });
}

export async function openChangeAssistant(page: Page) {
  await openMainTab(page, 'Change Assistant');
  await page.getByRole('button', { name: /^Saved/ }).click();
  await page.getByRole('button', { name: 'Refresh', exact: true }).click().catch(() => undefined);
}

export function changeCards(page: Page): Locator {
  return page.locator('div.cs-card');
}

export async function newestCardTitle(page: Page): Promise<string> {
  const feed = page.waitForResponse((res) => /changes\/recent/.test(res.url()), { timeout: 20_000 }).catch(() => null);
  await page.getByRole('button', { name: 'Refresh', exact: true }).click().catch(() => undefined);
  const response = await feed;
  if (response) {
  } else {
  }
  const title = changeCards(page).first().locator('.cs-title');
  await expect(title).toBeVisible({ timeout: 30_000 });
  return (await title.innerText()).trim();
}

export async function setOntologyIri(page: Page, iri: string) {
  await page.getByTitle('Edit Ontology IRIs').click();
  const input = page.getByPlaceholder('http://www.semanticweb.org/ontology', { exact: true });
  await input.fill(iri);
  await page.getByRole('button', { name: 'OK', exact: true }).click();
}

export async function addPrefix(page: Page, prefix: string, namespace: string) {
  await page.getByRole('button', { name: 'Ontology Prefixes' }).click();
  await page.getByRole('button', { name: 'Add Prefix' }).first().click();
  const dialog = page.locator('div.fixed.inset-0').filter({ has: page.getByRole('heading', { name: 'Add Prefix' }) });
  await dialog.getByPlaceholder('owl').fill(prefix);
  await dialog.getByPlaceholder('http://www.w3.org/2002/07/owl#').fill(namespace);
  await dialog.getByRole('button', { name: 'Add Prefix' }).click();
  await expect(page.locator(`[data-testid="prefix-row"][data-prefix^="${prefix}"]`)).toBeVisible({ timeout: 30_000 });
}

export async function deletePrefix(page: Page, prefix: string) {
  await page.getByRole('button', { name: 'Ontology Prefixes' }).click();
  const row = page.locator(`[data-testid="prefix-row"][data-prefix^="${prefix}"]`);
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByTitle('Delete').click();
}

export async function openEntitiesSubTab(page: Page, title: 'Classes' | 'Data properties') {
  await openMainTab(page, 'Entities');
  await page.locator(`button[title="${title}"]`).first().click();
}

export async function selectEntity(page: Page, iriFragment: string) {
  const node = page.locator(`[data-class-id*="${iriFragment}"]`).first();
  if (!(await node.isVisible({ timeout: 8_000 }).catch(() => false))) {
    for (const root of ['owl:topDataProperty', 'owl:topObjectProperty', 'owl:Thing']) {
      const row = page.locator('[data-class-id]')
        .filter({ has: page.locator('span').filter({ hasText: root }) })
        .first();
      if (await row.isVisible({ timeout: 1_000 }).catch(() => false)) {
        await row.locator('button').first().click().catch(() => undefined);
        await page.waitForTimeout(800);
        break;
      }
    }
  }
  await expect(node).toBeVisible({ timeout: 60_000 });
  await node.click();
}

export async function addNamed(page: Page, button: 'Add subclass' | 'Add sub property', placeholder: string, name: string) {
  await page.getByRole('button', { name: button }).click();
  await page.getByPlaceholder(placeholder).fill(name);
  await page.getByRole('button', { name: 'Create', exact: true }).click();
}

let editorOpened = false;

export async function openEditor(app: ElectronApplication, page: Page) {
  if (!editorOpened) {
    const filePath = path.resolve(__dirname, '../fixtures/demo-undo.owl');
    const content = fs.readFileSync(filePath, 'utf8');
    await app.evaluate(({ BrowserWindow }, payload) => {
      const windows = BrowserWindow.getAllWindows();
      const target = windows.find((w) => !w.webContents.getURL().startsWith('devtools')) || windows[0];
      target.webContents.send('menu:open-file', payload);
    }, { fileName: 'demo-undo.owl', fileContent: content, fileSize: content.length, filePath });
    await expect(page.getByText(/File Ready to Upload/)).toBeVisible({ timeout: 60_000 });
    await page.getByRole('button', { name: 'New Project' }).click();
    await page.getByPlaceholder('Enter project name').fill('UndoE2E');
    await page.getByRole('button', { name: 'Create Project', exact: true }).click();
    const card = page.getByText('UndoE2E', { exact: true }).first();
    await expect(card).toBeVisible({ timeout: 60_000 });
    await card.click();
    await page.getByRole('heading', { name: 'demo-undo.owl' }).first().click({ timeout: 120_000 });
    editorOpened = true;
  }
  await expect(page.getByRole('button', { name: 'Active ontology', exact: true }).first()).toBeVisible({ timeout: 240_000 });
  await page.waitForSelector('text=Loading classes', { state: 'detached', timeout: 90_000 }).catch(() => undefined);
  await dismissReleaseNotes(page);
}

export async function dismissReleaseNotes(page: Page) {
  const close = page.getByRole('button', { name: 'Close release notes' });
  if (await close.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await close.click();
    await expect(close).toBeHidden({ timeout: 10_000 });
  }
}

export async function currentProjectId(page: Page): Promise<string | null> {
  return page.evaluate(() => {
    const stored = localStorage.getItem('ontocode_lastProjectId');
    if (stored) return stored;
    const names = performance.getEntriesByType('resource').map((entry) => entry.name);
    for (let i = names.length - 1; i >= 0; i -= 1) {
      const match = names[i].match(/\/api\/ontology\/(?:mutations\/|metadata\/)?(proj-[A-Za-z0-9-]+)/);
      if (match) return match[1];
    }
    return null;
  });
}

export async function pressKeyInMainProcess(app: ElectronApplication, key: string, modifiers: string[]) {
  await app.evaluate(({ BrowserWindow }, input) => {
    const windows = BrowserWindow.getAllWindows();
    const target = windows.find((w) => !w.webContents.getURL().startsWith('devtools')) || windows[0];
    const mods = input.modifiers as Array<'control' | 'shift' | 'alt' | 'meta'>;
    target.webContents.sendInputEvent({ type: 'keyDown', keyCode: input.key, modifiers: mods });
    target.webContents.sendInputEvent({ type: 'keyUp', keyCode: input.key, modifiers: mods });
  }, { key, modifiers });
}
