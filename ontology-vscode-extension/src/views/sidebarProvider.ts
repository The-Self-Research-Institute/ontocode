import * as vscode from 'vscode';
import { sci2CodeService } from '../services/sci2CodeService';

// Local literal, not imported from extension.ts, to avoid a circular import
// (extension.ts is what constructs this provider).
const AUTH_TOKEN_KEY = 'ontocode.authToken';

class SidebarItem extends vscode.TreeItem {
  constructor(
    label: string,
    icon: vscode.ThemeIcon,
    command?: vscode.Command,
    description?: string
  ) {
    super(label, vscode.TreeItemCollapsibleState.None);
    this.iconPath = icon;
    this.command = command;
    this.description = description;
  }
}

/**
 * Flat command-launcher list for the OntoCode Activity Bar sidebar — a
 * discoverability home for features otherwise only reachable via the
 * Command Palette, a keybinding, or a right-click menu. The citation library
 * status row is a read-only snapshot (getConnectionStatus() never prompts —
 * see sci2CodeService.ts) refreshed whenever the view is (re)rendered.
 */
export class OntoCodeSidebarProvider implements vscode.TreeDataProvider<SidebarItem> {
  private _onDidChangeTreeData = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  constructor(private readonly context: vscode.ExtensionContext) {}

  refresh(): void {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: SidebarItem): vscode.TreeItem {
    return element;
  }

  async getChildren(element?: SidebarItem): Promise<SidebarItem[]> {
    if (element) return []; // flat list, no nesting

    const [status, authToken] = await Promise.all([
      sci2CodeService.getConnectionStatus(),
      this.context.secrets.get(AUTH_TOKEN_KEY),
    ]);

    const items: SidebarItem[] = [
      new SidebarItem(
        'Open OntoCode Editor',
        new vscode.ThemeIcon('edit'),
        { command: 'ontocode.openWebview', title: 'Open OntoCode Editor' }
      ),
      ...this.buildCitationLibraryItems(status),
    ];

    // Only meaningful (and only offered) when there's an actual session.
    if (authToken) {
      items.push(
        new SidebarItem(
          'Show Collaboration Status',
          new vscode.ThemeIcon('organization'),
          { command: 'ontocode.showCollaborationStatus', title: 'Show Collaboration Status' }
        ),
        new SidebarItem(
          'Logout',
          new vscode.ThemeIcon('sign-out'),
          { command: 'ontocode.logout', title: 'Logout' }
        )
      );
    }

    return items;
  }

  private buildCitationLibraryItems(status: 'connected' | 'not-configured' | 'unavailable'): SidebarItem[] {
    if (status === 'connected') {
      return [
        new SidebarItem(
          'Zotero: Connected',
          new vscode.ThemeIcon('check', new vscode.ThemeColor('charts.green')),
          { command: 'ontocode.configureZotero', title: 'Configure Zotero API Key' }
        ),
        new SidebarItem(
          'Insert Citation (Sci2Code)',
          new vscode.ThemeIcon('book'),
          { command: 'ontocode.insertCitation', title: 'Insert Citation (Sci2Code)' }
        ),
      ];
    }
    return [
      new SidebarItem(
        'Set up Citation Library (optional)',
        new vscode.ThemeIcon('book'),
        { command: 'ontocode.configureZotero', title: 'Configure Zotero API Key' }
      ),
    ];
  }
}
