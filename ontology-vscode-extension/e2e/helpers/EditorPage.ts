import { Page, expect, Locator } from '@playwright/test';

// ─── Credentials ────────────────────────────────────────────────────────────
const TEST_USER = { username: 'playwright_test', password: 'PlwTest123!' };

// Map from internal tab id → button label displayed in the UI
const TAB_LABELS: Record<string, string> = {
  Classes: 'Classes',
  ObjectProperties: 'Object properties',
  DataProperties: 'Data properties',
  AnnotationProperties: 'Annotation properties',
  Individuals: 'Individuals',
  Datatypes: 'Datatypes',
};

// ─── EditorPage  ─────────────────────────────────────────────────────────────
export class EditorPage {
  constructor(readonly page: Page) {}

  // ── Auth ──────────────────────────────────────────────────────────────────

  async login(username = TEST_USER.username, password = TEST_USER.password) {
    // Seed localStorage so the DeploymentSelector is skipped and the app
    // knows to use the self-hosted gateway (which .env.e2e points to production).
    await this.page.addInitScript(() => {
      localStorage.setItem('deploymentType', 'self-hosted');
    });
    await this.page.goto('/');
    await this.page.fill('#username', username);
    await this.page.fill('#password', password);
    await this.page.click('button[type="submit"]');
    // Wait until we're past login (project dashboard or workspace)
    await this.page.waitForSelector('#username', { state: 'detached', timeout: 20_000 });
  }

  // ── Project / file navigation ─────────────────────────────────────────────

  /** Click the first project card in the project dashboard */
  async openFirstProject() {
    // Wait for any "Entering Workspace" loading overlay to disappear first
    await this.page.waitForSelector('.fixed.inset-0 h2', { state: 'detached', timeout: 15_000 }).catch(() => {});
    // Project cards are divs with cursor-pointer containing an h3 with the project name
    const card = this.page.locator('div.cursor-pointer').filter({ has: this.page.locator('h3') }).first();
    await card.waitFor({ state: 'visible', timeout: 15_000 });
    await card.click();
  }

  /** Open a project by name (partial match) */
  async openProjectByName(name: string) {
    const card = this.page.locator('div.cursor-pointer h3')
      .filter({ hasText: name })
      .first();
    await card.click();
  }

  /** Open the first file in the project library and navigate to the editor */
  async openFirstFile() {
    // Use direct API calls (with stored JWT) to get the exact project/file names,
    // then navigate via URL so App.tsx's URL resolver handles opening the editor.
    // This avoids the timing issue of reading DOM names before the project library
    // has finished loading, and bypasses the cloud-only handleFileClick gating logic.
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

  /** Open a file by name (partial match) */
  async openFileByName(name: string) {
    await this.page.getByText(name, { exact: false }).first().click();
    await this.waitForEditor();
  }

  /** Wait until the Dashboard editor is visible and data has finished loading */
  async waitForEditor() {
    // Wait for the Classes tab button (has exact title="Classes" from Dashboard.tsx)
    await this.page.waitForSelector('button[title="Classes"]', { timeout: 90_000 });
    // Wait for the ontology data loading banner to clear before tests interact with entities
    await this.page.waitForSelector('text=Loading classes', { state: 'detached', timeout: 90_000 }).catch(() => {});
  }

  // ── Dashboard tabs ────────────────────────────────────────────────────────

  /**
   * Click an entity sub-tab.
   * Pass the internal id like 'ObjectProperties', 'Classes', 'Individuals', etc.
   * The method maps it to the real button label shown in the UI.
   */
  async clickTab(tabId: string) {
    const label = TAB_LABELS[tabId] ?? tabId;
    // Tab buttons in Dashboard.tsx have a title attribute matching the label exactly.
    // This is more reliable than text matching because the button text also includes
    // a dynamic count badge number that makes regex matching fragile.
    await this.page.locator(`button[title="${label}"]`)
      .first()
      .click();
    await this.page.waitForTimeout(400);
  }

  /** Open the main Graph tab (graph-view-plugin) */
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

  // ── Entity hierarchy ──────────────────────────────────────────────────────

  /**
   * Select an entity in the main hierarchy by its IRI or label.
   * Prefers data-class-id for IRIs; falls back to text match.
   */
  async selectEntity(idOrLabel: string) {
    if (idOrLabel.startsWith('http')) {
      await this.page.locator(`[data-class-id="${idOrLabel}"]`).first().click();
    } else {
      // Find the span label text inside a hierarchy row
      await this.page.locator('[data-class-id] span')
        .filter({ hasText: idOrLabel })
        .first()
        .click();
    }
    await this.page.waitForTimeout(200);
  }

  /** Select the first entity in the main hierarchy */
  async selectFirstEntity() {
    await this.page.locator('[data-class-id]').first().click();
    await this.page.waitForTimeout(200);
  }

  /**
   * Expand a hierarchy node by its IRI or label.
   * Clicks the expander button (chevron) on the row.
   */
  async expandNode(idOrLabel: string) {
    let row: Locator;
    if (idOrLabel.startsWith('http')) {
      row = this.page.locator(`[data-class-id="${idOrLabel}"]`).first();
    } else {
      row = this.page.locator('[data-class-id]')
        .filter({ has: this.page.locator('span').filter({ hasText: idOrLabel }) })
        .first();
    }
    // The expander is a <button> that's the first button child of the row
    await row.locator('button').first().click();
    await this.page.waitForTimeout(600);
  }

  // ── Details panel actions ─────────────────────────────────────────────────

  /** Click the "Add type" button (the plus icon with title "Add type") in IndividualEditor */
  async openAddTypeDialog() {
    await this.page.locator('button[title="Add type"]').click();
    await this.page.waitForTimeout(300);
  }

  /** Ensure the Description section/tab is active so domain/range/characteristics are visible */
  async openDescriptionSection() {
    const descBtn = this.page.getByRole('button', { name: /^Description$/ });
    if (await descBtn.count() > 0) {
      await descBtn.first().click().catch(() => {});
    }
    // Description auto-loads when the tab opens (Load button removed) — wait for
    // the loading indicator to appear and clear instead of clicking anything.
    const loadingIndicator = this.page.getByText(/Loading (description|axioms and restrictions)/i);
    await loadingIndicator.first().waitFor({ state: 'visible', timeout: 3_000 }).catch(() => {});
    await loadingIndicator.first().waitFor({ state: 'hidden', timeout: 90_000 }).catch(() => {});
    await this.page.waitForTimeout(300);
  }

  /** Click the "Domains (Intersection)" section header to open the Add Domain dialog */
  async openDomainDialog() {
    await this.openDescriptionSection();
    // The section header is a <button> with text containing "Domains"
    await this.page.locator('button').filter({ hasText: /Domains/i }).first().click();
    await this.page.waitForTimeout(300);
  }

  /** Click the "Disjoint With" section header to open the Disjoint dialog */
  async openDisjointWithDialog() {
    await this.openDescriptionSection();
    await this.page.locator('button').filter({ hasText: /Disjoint With/i }).first().click();
    await this.page.waitForTimeout(300);
  }

  // ── Class expression / selector dialogs ──────────────────────────────────

  private dialog() {
    // Dialogs are fixed overlays — get the last one so nested dialogs work
    return this.page.locator('.fixed.inset-0').last();
  }

  /** Switch the dialog to its hierarchy tab */
  async switchDialogToHierarchyTab() {
    await this.dialog().getByRole('button', { name: /hierarchy/i }).click();
    await this.page.waitForTimeout(300);
  }

  /**
   * Expand a node inside the currently open dialog.
   * Pass an IRI or label.
   */
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

  /** Click a class row inside the currently open dialog */
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

  /** Click the Add/Confirm button in the currently open dialog */
  async confirmDialog() {
    await this.dialog().getByRole('button', { name: /^(add|confirm|ok)/i }).last().click();
    await this.page.waitForTimeout(300);
  }

  /** Click Cancel/Close in the currently open dialog */
  async cancelDialog() {
    await this.dialog().getByRole('button', { name: /cancel|close/i }).first().click();
  }

  // ── File menu / Save ──────────────────────────────────────────────────────

  /** Open the "File" top menu and click the Save option */
  async clickSave() {
    // Open File menu
    await this.page.locator('.ontocode-top-menu button, button.ontocode-top-menu-button')
      .filter({ hasText: 'File' })
      .click();
    await this.page.waitForTimeout(200);
    // Click the Save button inside the dropdown
    await this.page.locator('.ontocode-top-menu-dropdown button')
      .filter({ hasText: /^Save/ })
      .click();
    await this.page.waitForTimeout(500);
  }

  /** Returns true if the orange unsaved-changes dot is visible in the Save menu item */
  async hasSaveIndicator(): Promise<boolean> {
    // Open File menu first so the dot is in view
    await this.page.locator('button').filter({ hasText: 'File' }).first().click();
    await this.page.waitForTimeout(200);
    const visible = await this.page.locator('span.text-orange-600').isVisible();
    // Close menu
    await this.page.keyboard.press('Escape');
    return visible;
  }

  /** Assert the orange unsaved-changes dot has disappeared */
  async assertSaveIndicatorGone() {
    await this.page.locator('button').filter({ hasText: 'File' }).first().click();
    await expect(this.page.locator('span.text-orange-600')).not.toBeVisible({ timeout: 5_000 });
    await this.page.keyboard.press('Escape');
  }

  // ── Annotations ──────────────────────────────────────────────────────────

  /** Click the "Add annotation" plus button in the Annotations section */
  async openAddAnnotationDialog() {
    // The button is icon-only (no text) — identified by its title attribute
    await this.page.locator('button[title="Add annotation"]').first().click();
    await this.page.waitForTimeout(300);
  }

  // ── Assertions ────────────────────────────────────────────────────────────

  /** Assert that a child node label appears in the open dialog */
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
