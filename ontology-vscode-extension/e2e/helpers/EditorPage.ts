import { Page, expect, Locator } from '@playwright/test';

const TEST_USER = { username: 'playwright_test', password: 'PlwTest123!' };

const TAB_LABELS: Record<string, string> = {
  Classes: 'Classes',
  ObjectProperties: 'Object properties',
  DataProperties: 'Data properties',
  AnnotationProperties: 'Annotation properties',
  Individuals: 'Individuals',
  Datatypes: 'Datatypes',
};

export class EditorPage {
  constructor(readonly page: Page) {}

  async login(username = TEST_USER.username, password = TEST_USER.password) {

    await this.page.addInitScript(() => {
      localStorage.setItem('deploymentType', 'self-hosted');
    });
    await this.page.goto('/');
    await this.page.fill('#username', username);
    await this.page.fill('#password', password);
    await this.page.click('button[type="submit"]');

    await this.page.waitForSelector('#username', { state: 'detached', timeout: 20_000 });
  }

  async openFirstProject() {

    await this.page.waitForSelector('.fixed.inset-0 h2', { state: 'detached', timeout: 15_000 }).catch(() => {});

    const card = this.page.locator('div.cursor-pointer').filter({ has: this.page.locator('h3') }).first();
    await card.waitFor({ state: 'visible', timeout: 15_000 });
    await card.click();
  }

  async openProjectByName(name: string) {
    const card = this.page.locator('div.cursor-pointer h3')
      .filter({ hasText: name })
      .first();
    await card.click();
  }

  async openFirstFile() {

    const { projectName, fileName } = await this.page.evaluate(async () => {
      const token = localStorage.getItem('authToken');
      const headers = { 'Authorization': `Bearer ${token}` };

      const projResp = await fetch('/api/projects/my', { headers });
      const projData = await projResp.json();
      const project = (projData.projects ?? [])[0];

      const fileResp = await fetch(`/api/projects/${project.projectId}/files`, { headers });
      const fileData = await fileResp.json();
      const list = fileData.files ?? [];
      const preferred = list.find((f: { name?: string; fileName?: string }) =>
        (f.name ?? f.fileName) === 'class-hierarchy-test.owl',
      );
      const file = preferred ?? list[0];
      if (!project?.name || !file) {
        throw new Error('No project/file available for editor tests');
      }

      return { projectName: project.name as string, fileName: (file.name ?? file.fileName) as string };
    });

    await this.page.goto(`/projects/${encodeURIComponent(projectName)}/files/${encodeURIComponent(fileName)}`);
    await this.waitForEditor();
  }

  async openFileByName(name: string) {
    await this.page.getByText(name, { exact: false }).first().click();
    await this.waitForEditor();
  }

  async waitForEditor() {

    await this.page.waitForSelector('button[title="Classes"]', { timeout: 90_000 });

    await this.page.waitForSelector('text=Loading classes', { state: 'detached', timeout: 90_000 }).catch(() => {});
  }

  async clickTab(tabId: string) {
    const label = TAB_LABELS[tabId] ?? tabId;

    await this.page.locator(`button[title="${label}"]`)
      .first()
      .click();
    await this.page.waitForTimeout(400);
  }

  async openGraphTab() {
    await this.page.getByRole('button', { name: /^Graph$/ }).first().click();
    await this.page.waitForSelector('[data-testid="graph-view"]', { timeout: 60_000 });
    await this.page.waitForFunction(() => {
      const root = document.querySelector('[data-testid="graph-view"]');
      if (!root || root.getAttribute('data-graph-loading') === 'true') return false;
      const total = Number(root.querySelector('[data-testid="graph-stats"]')?.getAttribute('data-total-nodes') || 0);
      return total > 0;
    }, { timeout: 90_000 }).catch(() => {});
  }

  async getGraphStats() {
    const stats = this.page.locator('[data-testid="graph-stats"]');
    return {
      visible: Number(await stats.getAttribute('data-visible-nodes') || 0),
      total: Number(await stats.getAttribute('data-total-nodes') || 0),
      expanded: Number(await stats.getAttribute('data-expanded-nodes') || 0),
    };
  }

  async selectEntity(idOrLabel: string) {
    if (idOrLabel.startsWith('http')) {
      await this.page.locator(`[data-class-id="${idOrLabel}"]`).first().click();
    } else {

      await this.page.locator('[data-class-id] span')
        .filter({ hasText: idOrLabel })
        .first()
        .click();
    }
    await this.page.waitForTimeout(200);
  }

  async selectFirstEntity() {
    await this.page.locator('[data-class-id]').first().click();
    await this.page.waitForTimeout(200);
  }

  async expandNode(idOrLabel: string) {
    let row: Locator;
    if (idOrLabel.startsWith('http')) {
      row = this.page.locator(`[data-class-id="${idOrLabel}"]`).first();
    } else {
      row = this.page.locator('[data-class-id]')
        .filter({ has: this.page.locator('span').filter({ hasText: idOrLabel }) })
        .first();
    }

    await row.locator('button').first().click();
    await this.page.waitForTimeout(600);
  }

  async openAddTypeDialog() {
    await this.page.locator('button[title="Add type"]').click();
    await this.page.waitForTimeout(300);
  }

  async openDescriptionSection() {
    const descBtn = this.page.getByRole('button', { name: /^Description$/ });
    if (await descBtn.count() > 0) {
      await descBtn.first().click().catch(() => {});
    }

    const loadingIndicator = this.page.getByText(/Loading (description|axioms and restrictions)/i);
    await loadingIndicator.first().waitFor({ state: 'visible', timeout: 3_000 }).catch(() => {});
    await loadingIndicator.first().waitFor({ state: 'hidden', timeout: 90_000 }).catch(() => {});
    await this.page.waitForTimeout(300);
  }

  async openDomainDialog() {
    await this.openDescriptionSection();

    await this.page.locator('button').filter({ hasText: /Domains/i }).first().click();
    await this.page.waitForTimeout(300);
  }

  async openDisjointWithDialog() {
    await this.openDescriptionSection();
    await this.page.locator('button').filter({ hasText: /Disjoint With/i }).first().click();
    await this.page.waitForTimeout(300);
  }

  private dialog() {

    return this.page.locator('.fixed.inset-0').last();
  }

  async switchDialogToHierarchyTab() {
    await this.dialog().getByRole('button', { name: /hierarchy/i }).click();
    await this.page.waitForTimeout(300);
  }

  async expandDialogNode(idOrLabel: string) {
    const dlg = this.dialog();
    let row: Locator;
    if (idOrLabel.startsWith('http')) {
      row = dlg.locator(`[data-class-id="${idOrLabel}"]`).first();
    } else {
      row = dlg.locator('[data-class-id]')
        .filter({ has: this.page.locator('span').filter({ hasText: idOrLabel }) })
        .first();
    }
    await row.locator('button').first().click();
    await this.page.waitForTimeout(600);
  }

  async selectDialogClass(idOrLabel: string) {
    const dlg = this.dialog();
    if (idOrLabel.startsWith('http')) {
      await dlg.locator(`[data-class-id="${idOrLabel}"]`).first().click();
    } else {
      await dlg.locator('[data-class-id]')
        .filter({ has: this.page.locator('span').filter({ hasText: idOrLabel }) })
        .first()
        .click();
    }
  }

  async confirmDialog() {
    await this.dialog().getByRole('button', { name: /^(add|confirm|ok)/i }).last().click();
    await this.page.waitForTimeout(300);
  }

  async cancelDialog() {
    await this.dialog().getByRole('button', { name: /cancel|close/i }).first().click();
  }

  async clickSave() {

    await this.page.locator('.ontocode-top-menu button, button.ontocode-top-menu-button')
      .filter({ hasText: 'File' })
      .click();
    await this.page.waitForTimeout(200);

    await this.page.locator('.ontocode-top-menu-dropdown button')
      .filter({ hasText: /^Save/ })
      .click();
    await this.page.waitForTimeout(500);
  }

  async hasSaveIndicator(): Promise<boolean> {

    await this.page.locator('button').filter({ hasText: 'File' }).first().click();
    await this.page.waitForTimeout(200);
    const visible = await this.page.locator('span.text-orange-600').isVisible();

    await this.page.keyboard.press('Escape');
    return visible;
  }

  async assertSaveIndicatorGone() {
    await this.page.locator('button').filter({ hasText: 'File' }).first().click();
    await expect(this.page.locator('span.text-orange-600')).not.toBeVisible({ timeout: 5_000 });
    await this.page.keyboard.press('Escape');
  }

  async openAddAnnotationDialog() {

    await this.page.locator('button[title="Add annotation"]').first().click();
    await this.page.waitForTimeout(300);
  }

  async assertDialogNodeVisible(idOrLabel: string) {
    const dlg = this.dialog();
    if (idOrLabel.startsWith('http')) {
      await expect(dlg.locator(`[data-class-id="${idOrLabel}"]`).first()).toBeVisible({ timeout: 5_000 });
    } else {
      await expect(
        dlg.locator('[data-class-id] span').filter({ hasText: idOrLabel }).first()
      ).toBeVisible({ timeout: 5_000 });
    }
  }
}
