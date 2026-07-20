import * as vscode from 'vscode';
import * as path from 'path';
import { sci2CodeService } from '../services/sci2CodeService';
import { ensurePrefixes } from '../features/citationInsertion';

export class CitationPickerPanel {
  public static currentPanel: CitationPickerPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private readonly _extensionUri: vscode.Uri;
  private _disposables: vscode.Disposable[] = [];

  public static async createOrShow(extensionUri: vscode.Uri) {
    const column = vscode.ViewColumn.Beside;

    // If panel already exists, reveal it
    if (CitationPickerPanel.currentPanel) {
      CitationPickerPanel.currentPanel._panel.reveal(column);
      return;
    }

    // Initialize Sci2Code service
    const initialized = await sci2CodeService.initialize();
    if (!initialized) {
      return;
    }

    // Create new panel
    const panel = vscode.window.createWebviewPanel(
      'citationPicker',
      'Select Citation',
      column,
      {
        enableScripts: true,
        retainContextWhenHidden: true,
        localResourceRoots: [
          vscode.Uri.joinPath(extensionUri, 'webview-ui', 'build')
        ]
      }
    );

    CitationPickerPanel.currentPanel = new CitationPickerPanel(panel, extensionUri);
  }

  private constructor(panel: vscode.WebviewPanel, extensionUri: vscode.Uri) {
    this._panel = panel;
    this._extensionUri = extensionUri;

    // Set HTML content
    this._panel.webview.html = this._getHtmlContent(this._panel.webview);

    // Handle messages from webview
    this._panel.webview.onDidReceiveMessage(
      async message => {
        switch (message.type) {
          case 'requestCitations':
            await this.loadCitations();
            break;
            
          case 'selectCitation':
            await this.handleCitationSelection(message.key, message.format);
            break;

          case 'searchCitations':
            await this.searchCitations(message.query);
            break;

          case 'close':
            this._panel.dispose();
            break;
        }
      },
      null,
      this._disposables
    );

    // Handle panel disposal
    this._panel.onDidDispose(() => this.dispose(), null, this._disposables);

    // Load citations when panel is created
    this.loadCitations();
  }

  private async loadCitations() {
    try {
      const items = await sci2CodeService.getZoteroLibrary();
      this._panel.webview.postMessage({
        type: 'citationsData',
        items: items
      });
    } catch (error) {
      this._panel.webview.postMessage({
        type: 'error',
        message: 'Failed to load citations'
      });
    }
  }

  private async searchCitations(query: string) {
    try {
      const allItems = await sci2CodeService.getZoteroLibrary();
      const filtered = allItems.filter(item => {
        const title = item.data?.title?.toLowerCase() || '';
        const creators = item.data?.creators?.map((c: any) => 
          `${c.firstName} ${c.lastName}`.toLowerCase()
        ).join(' ') || '';
        const searchTerm = query.toLowerCase();
        
        return title.includes(searchTerm) || creators.includes(searchTerm);
      });

      this._panel.webview.postMessage({
        type: 'citationsData',
        items: filtered
      });
    } catch (error) {
      this._panel.webview.postMessage({
        type: 'error',
        message: 'Search failed'
      });
    }
  }

  private async handleCitationSelection(key: string, format: 'turtle' | 'rdfxml') {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      vscode.window.showWarningMessage('No active editor found.');
      return;
    }

    const formattedCitation = await sci2CodeService.formatCitationForOntology(key, format);
    if (!formattedCitation) {
      vscode.window.showErrorMessage('Failed to format citation.');
      return;
    }

    // The fragment relies on the host document's root tag already declaring
    // rdf/rdfs/owl/dc/foaf/prov — ensure that before inserting.
    await ensurePrefixes(editor.document, format);

    // Insert at cursor position
    const position = editor.selection.active;
    await editor.edit(editBuilder => {
      editBuilder.insert(position, '\n' + formattedCitation + '\n');
    });

    // Get metadata for success message
    const metadata = await sci2CodeService.getCitationMetadata(key);
    vscode.window.showInformationMessage(`Inserted: ${metadata?.title || 'Citation'}`);

    // Close panel
    this._panel.dispose();
  }

  private _getHtmlContent(webview: vscode.Webview): string {
    const nonce = getNonce();

    return `<!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <meta http-equiv="Content-Security-Policy" content="
            default-src 'none'; 
            style-src ${webview.cspSource} 'unsafe-inline'; 
            script-src 'nonce-${nonce}';
        ">
        <title>Citation Picker</title>
        <style>
            body {
                padding: 20px;
                font-family: var(--vscode-font-family);
                color: var(--vscode-foreground);
                background-color: var(--vscode-editor-background);
            }
            .search-box {
                width: 100%;
                padding: 8px;
                margin-bottom: 16px;
                background: var(--vscode-input-background);
                color: var(--vscode-input-foreground);
                border: 1px solid var(--vscode-input-border);
                border-radius: 4px;
            }
            .citation-list {
                max-height: 500px;
                overflow-y: auto;
            }
            .citation-item {
                padding: 12px;
                margin-bottom: 8px;
                background: var(--vscode-editor-inactiveSelectionBackground);
                border-radius: 4px;
                cursor: pointer;
                transition: background 0.2s;
            }
            .citation-item:hover {
                background: var(--vscode-list-hoverBackground);
            }
            .citation-title {
                font-weight: bold;
                margin-bottom: 4px;
            }
            .citation-meta {
                font-size: 0.9em;
                color: var(--vscode-descriptionForeground);
            }
            .loading {
                text-align: center;
                padding: 20px;
                color: var(--vscode-descriptionForeground);
            }
            .format-selector {
                margin-bottom: 16px;
            }
            .format-button {
                padding: 6px 12px;
                margin-right: 8px;
                background: var(--vscode-button-secondaryBackground);
                color: var(--vscode-button-secondaryForeground);
                border: none;
                border-radius: 4px;
                cursor: pointer;
            }
            .format-button.active {
                background: var(--vscode-button-background);
                color: var(--vscode-button-foreground);
            }
        </style>
    </head>
    <body>
        <h2>Select Citation</h2>
        
        <div class="format-selector">
            <label>Format:</label>
            <button class="format-button active" data-format="turtle">Turtle</button>
            <button class="format-button" data-format="rdfxml">RDF/XML</button>
        </div>

        <input 
            type="text" 
            id="searchBox" 
            class="search-box" 
            placeholder="Search citations by title or author..."
        />
        
        <div id="citationList" class="citation-list">
            <div class="loading">Loading citations...</div>
        </div>

        <script nonce="${nonce}">
            const vscode = acquireVsCodeApi();
            let currentFormat = 'turtle';
            let allCitations = [];

            // Request citations on load
            vscode.postMessage({ type: 'requestCitations' });

            // Handle format selection
            document.querySelectorAll('.format-button').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    document.querySelectorAll('.format-button').forEach(b => 
                        b.classList.remove('active')
                    );
                    e.target.classList.add('active');
                    currentFormat = e.target.dataset.format;
                });
            });

            // Handle search
            const searchBox = document.getElementById('searchBox');
            let searchTimeout;
            searchBox.addEventListener('input', (e) => {
                clearTimeout(searchTimeout);
                searchTimeout = setTimeout(() => {
                    const query = e.target.value;
                    if (query.length >= 2) {
                        vscode.postMessage({ 
                            type: 'searchCitations', 
                            query: query 
                        });
                    } else if (query.length === 0) {
                        renderCitations(allCitations);
                    }
                }, 300);
            });

            // Handle messages from extension
            window.addEventListener('message', event => {
                const message = event.data;
                
                switch (message.type) {
                    case 'citationsData':
                        allCitations = message.items;
                        renderCitations(message.items);
                        break;
                    case 'error':
                        document.getElementById('citationList').innerHTML = 
                            '<div class="loading" style="color: var(--vscode-errorForeground);">' + 
                            message.message + '</div>';
                        break;
                }
            });

            function renderCitations(items) {
                const listEl = document.getElementById('citationList');
                
                if (items.length === 0) {
                    listEl.innerHTML = '<div class="loading">No citations found</div>';
                    return;
                }

                listEl.innerHTML = items.map(item => {
                    const title = item.data?.title || 'Untitled';
                    const creators = item.data?.creators?.map(c => 
                        (c.firstName + ' ' + c.lastName).trim()
                    ).join(', ') || 'Unknown';
                    const year = item.data?.date ? 
                        new Date(item.data.date).getFullYear() : '';
                    const type = item.data?.itemType || 'Item';

                    return \`
                        <div class="citation-item" data-key="\${item.key}">
                            <div class="citation-title">\${title}</div>
                            <div class="citation-meta">
                                \${creators} • \${type} \${year ? '(' + year + ')' : ''}
                            </div>
                        </div>
                    \`;
                }).join('');

                // Add click handlers
                document.querySelectorAll('.citation-item').forEach(el => {
                    el.addEventListener('click', () => {
                        vscode.postMessage({ 
                            type: 'selectCitation', 
                            key: el.dataset.key,
                            format: currentFormat
                        });
                    });
                });
            }
        </script>
    </body>
    </html>`;
  }

  public dispose() {
    CitationPickerPanel.currentPanel = undefined;
    this._panel.dispose();

    while (this._disposables.length) {
      const disposable = this._disposables.pop();
      if (disposable) {
        disposable.dispose();
      }
    }
  }
}

function getNonce() {
  let text = '';
  const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  for (let i = 0; i < 32; i++) {
    text += possible.charAt(Math.floor(Math.random() * possible.length));
  }
  return text;
}