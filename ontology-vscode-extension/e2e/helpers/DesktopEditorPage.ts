import { Page, expect } from '@playwright/test';
import { EditorPage } from './EditorPage';
import { loadDesktopAuth } from './desktopApi';

const DESKTOP_USER = process.env.DESKTOP_E2E_USER || 'admin@ontocode.local';
const DESKTOP_PASS = process.env.DESKTOP_E2E_PASSWORD || 'ontocode-desktop';

/**
 * Desktop-backend UI helper (Chromium + Vite proxy to :18085).
 * Each test should pass its own isolated projectId from prepareIsolatedDesktopProject.
 */
export class DesktopEditorPage extends EditorPage {
  constructor(page: Page) {
    super(page);
  }

  private seedDesktopSession(projectId: string, token: string) {
    return this.page.addInitScript(
      ({ pid, tok }) => {
        localStorage.setItem('deploymentType', 'self-hosted');
        localStorage.setItem('skipWorkspaceMode', 'true');
        localStorage.setItem('ontocode_suppress_workspace_auto_open', 'true');
        localStorage.setItem('authToken', tok);
        localStorage.setItem('ontocode_lastProjectId', pid);
      },
      { pid: projectId, tok: token },
    );
  }

  async loginDesktop(username = DESKTOP_USER, password = DESKTOP_PASS) {
    await this.page.addInitScript(() => {
      localStorage.setItem('deploymentType', 'self-hosted');
      localStorage.setItem('skipWorkspaceMode', 'true');
      localStorage.setItem('ontocode_suppress_workspace_auto_open', 'true');
    });
    const loginVisible = await this.page.locator('#username').isVisible().catch(() => false);
    if (!loginVisible) {
      return;
    }
    await this.page.fill('#username', username);
    await this.page.fill('#password', password);
    await this.page.click('button[type="submit"]');
    await this.page.waitForSelector('#username', { state: 'detached', timeout: 30_000 });
  }

  async openEditorForProject(projectId: string, token?: string) {
    const auth = loadDesktopAuth();
    const jwt = token || auth.token;
    await this.seedDesktopSession(projectId, jwt);
    await this.page.goto('/editor');
    await this.loginDesktop();
    await this.waitForEditor();
  }

  async clickMainTab(label: string) {
    await this.page.getByRole('button', { name: new RegExp(`^${label}$`) }).click();
    await this.page.waitForTimeout(400);
  }

  async clickIndividualsByClassTab() {
    await this.clickMainTab('Individuals by class');
  }
}
