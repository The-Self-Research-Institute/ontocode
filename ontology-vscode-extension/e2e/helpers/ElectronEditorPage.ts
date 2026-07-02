import { Page, expect } from '@playwright/test';
import { EditorPage } from './EditorPage';
import { loadDesktopAuth } from './desktopApi';
import { assertElectronWindow } from '../fixtures/electronDesktop';

/**
 * Editor helper for the real Electron window (file:// or Vite in dev shell).
 */
export class ElectronEditorPage extends EditorPage {
  constructor(page: Page) {
    super(page);
  }

  async assertInElectron() {
    await assertElectronWindow(this.page);
  }

  async openEditorForProject(projectId: string) {
    await this.assertInElectron();
    await this.page.evaluate((pid) => {
      localStorage.setItem('deploymentType', 'self-hosted');
      localStorage.setItem('skipWorkspaceMode', 'true');
      localStorage.setItem('ontocode_suppress_workspace_auto_open', 'true');
      localStorage.setItem('ontocode_lastProjectId', pid);
      const auth = localStorage.getItem('authToken');
      if (!auth) {
        /* token injected via API login in worker fixture — renderer may need reload */
      }
    }, projectId);

    const auth = loadDesktopAuth();
    await this.page.evaluate((tok) => {
      localStorage.setItem('authToken', tok);
    }, auth.token);

    const base = await this.page.evaluate(() => {
      const href = window.location.href.split('#')[0].split('?')[0];
      return href;
    });
    await this.page.goto(`${base}#/editor`, { waitUntil: 'domcontentloaded', timeout: 120_000 });
    await this.page.reload({ waitUntil: 'domcontentloaded', timeout: 120_000 });
    await this.waitForEditor();
  }

  async clickIndividualsByClassTab() {
    await this.page.getByRole('button', { name: /^Individuals by class$/i }).click();
    await this.page.waitForTimeout(400);
  }
}
