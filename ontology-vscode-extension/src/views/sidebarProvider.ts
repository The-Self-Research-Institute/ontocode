import * as vscode from 'vscode';
import { sci2CodeService } from '../services/sci2CodeService';

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
    if (element) {return [];} // flat list, no nesting

    const [status, authToken] = await Promise.all([
      sci2CodeService.getConnectionStatus(),
      this.context.secrets.get(AUTH_TOKEN_KEY),
    ]);

    const items: SidebarItem[] = [
      this.buildStatusItem(status),
      new SidebarItem(
        'Insert Citation (Sci2Code)',
        new vscode.ThemeIcon('book'),
        { command: 'ontocode.insertCitation', title: 'Insert Citation (Sci2Code)' }
      ),
      new SidebarItem(

        'Configure Zotero API Key',
        new vscode.ThemeIcon('gear'),
        { command: 'ontocode.configureZotero', title: 'Configure Zotero API Key' }
      ),
      new SidebarItem(
        'Open Webview',
        new vscode.ThemeIcon('preview'),
        { command: 'ontocode.openWebview', title: 'Open Webview' }
      ),
    ];

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

  private buildStatusItem(status: 'connected' | 'not-configured' | 'unavailable'): SidebarItem {
    if (status === 'connected') {
      return new SidebarItem(
        'Zotero: Connected',
        new vscode.ThemeIcon('check', new vscode.ThemeColor('charts.green')),
        { command: 'ontocode.configureZotero', title: 'Configure Zotero API Key' }
      );
    }
    if (status === 'not-configured') {
      return new SidebarItem(
        'Zotero: Not Configured',
        new vscode.ThemeIcon('warning', new vscode.ThemeColor('charts.yellow')),
        { command: 'ontocode.configureZotero', title: 'Configure Zotero API Key' },
        'Click to configure'
      );
    }
    return new SidebarItem(
      'Zotero: Unavailable',
      new vscode.ThemeIcon('circle-slash', new vscode.ThemeColor('charts.red'))
    );
  }
}
