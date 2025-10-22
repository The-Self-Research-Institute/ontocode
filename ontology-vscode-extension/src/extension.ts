import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import FormData from 'form-data';
import axios from 'axios';
import { insertCitationCommand } from './features/citationInsertion';
import { CitationPickerPanel } from './webview/citationPicker';

const TOKEN_KEY = 'ontocode.authToken';
const GATEWAY_URL = 'http://localhost:8082'; // Gateway port

export function activate(context: vscode.ExtensionContext) {
    console.log('OntoCode extension is now active!');

    // Register all commands
    context.subscriptions.push(
        vscode.commands.registerCommand('ontocode.edit', () => {
            const panel = OntoCodePanel.createOrShow(context.extensionUri, context);
            panel.triggerFileUpload();
        }),
        vscode.commands.registerCommand('ontocode.editLargeFile', (uri: vscode.Uri) => {
            if (!uri) {
                vscode.window.showErrorMessage("This command should be run by right-clicking an OWL file in the explorer.");
                return;
            }
            const panel = OntoCodePanel.createOrShow(context.extensionUri, context);
            panel.triggerLargeFileUpload(uri);
        }),
        vscode.commands.registerCommand('ontocode.logout', async () => {
            await context.secrets.delete(TOKEN_KEY);
            if (OntoCodePanel.currentPanel) {
                OntoCodePanel.currentPanel.dispose();
            }
            vscode.window.showInformationMessage('You have been successfully logged out.');
        }),
        vscode.commands.registerCommand('ontocode.insertCitation', insertCitationCommand),
        vscode.commands.registerCommand('ontocode.openCitationPicker', () => CitationPickerPanel.createOrShow(context.extensionUri))
    );
}

export function deactivate() {
    console.log('OntoCode extension is now deactivated');
}

class OntoCodePanel {
    public static currentPanel: OntoCodePanel | undefined;
    private readonly _panel: vscode.WebviewPanel;
    private readonly _extensionUri: vscode.Uri;
    private readonly _context: vscode.ExtensionContext;
    private _disposables: vscode.Disposable[] = [];

    public static createOrShow(extensionUri: vscode.Uri, context: vscode.ExtensionContext): OntoCodePanel {
        const column = vscode.window.activeTextEditor?.viewColumn ?? vscode.ViewColumn.One;

        if (OntoCodePanel.currentPanel) {
            OntoCodePanel.currentPanel._panel.reveal(column);
            return OntoCodePanel.currentPanel;
        }

        const panel = vscode.window.createWebviewPanel(
            'ontocodeEditor',
            'OntoCode Editor',
            column,
            {
                enableScripts: true,
                retainContextWhenHidden: true,
                localResourceRoots: [vscode.Uri.joinPath(extensionUri, 'webview-src', 'dist')]
            }
        );

        OntoCodePanel.currentPanel = new OntoCodePanel(panel, extensionUri, context);
        return OntoCodePanel.currentPanel;
    }

    private constructor(panel: vscode.WebviewPanel, extensionUri: vscode.Uri, context: vscode.ExtensionContext) {
        this._panel = panel;
        this._extensionUri = extensionUri;
        this._context = context;

        this._update();
        this._panel.onDidDispose(() => this.dispose(), null, this._disposables);

        // Handle messages from the webview
        this._panel.webview.onDidReceiveMessage(
            async message => {
                switch (message.type) {
                    case 'error':
                        vscode.window.showErrorMessage(message.value);
                        break;
                    case 'saveAuthToken':
                        if (message.token) {
                            await this._context.secrets.store(TOKEN_KEY, message.token);
                            vscode.window.showInformationMessage('Authentication successful.');
                        }
                        break;
                    case 'requestAuthToken':
                        const token = await this._context.secrets.get(TOKEN_KEY);
                        this.postMessage({ type: 'storedAuthToken', token: token || null });
                        break;
                }
            },
            null,
            this._disposables
        );
    }

    public postMessage(message: any) {
        this._panel.webview.postMessage(message);
    }

    /**
     * Handles uploading a large file from a file URI (e.g., from the Explorer context menu).
     */
    public triggerLargeFileUpload(fileUri: vscode.Uri) {
        console.log(`[OntoCode] Triggering large file upload for: ${fileUri.fsPath}`);
        const filePath = fileUri.fsPath;
        const fileName = path.basename(filePath);
        const fileStream = fs.createReadStream(filePath);
        const projectId = path.basename(fileName, '.owl');
        
        // Delegate to the shared upload logic
        this._uploadOntology(projectId, fileName, fileStream);
    }

    /**
     * Handles uploading the content of the currently active editor.
     */
    public async triggerFileUpload() {
        console.log('[OntoCode] Triggering active editor file upload...');
        const targetEditor = this.findBestOwlEditor();

        if (!targetEditor) {
            vscode.window.showWarningMessage("No active .owl file found. Please open an ontology file and try again.");
            return;
        }

        await vscode.window.showTextDocument(targetEditor.document, targetEditor.viewColumn);

        const fileContent = targetEditor.document.getText();
        const fileName = path.basename(targetEditor.document.fileName);
        const fileBuffer = Buffer.from(fileContent, 'utf-8');
        const projectId = path.basename(fileName, '.owl');

        // Delegate to the shared upload logic
        this._uploadOntology(projectId, fileName, fileBuffer);
    }

    /**
     * Private helper method to handle the actual upload logic.
     * Uploads ontology file to the gateway which routes to the OWL Editor service.
     */
    private async _uploadOntology(projectId: string, fileName: string, fileData: fs.ReadStream | Buffer) {
        console.log(`[OntoCode] Starting upload for project: ${projectId}, file: ${fileName}`);
        
        // 1. Check for authentication token
        const token = await this._context.secrets.get(TOKEN_KEY);
        if (!token) {
            console.error('[OntoCode] No authentication token found');
            vscode.window.showErrorMessage("You must be logged in to process an ontology.");
            this.postMessage({ type: 'showLogin' });
            return;
        }

        // 2. Inform the webview to show a loading state
        this.postMessage({ type: 'showLoading' });

        try {
            // 3. Prepare the form data for multipart upload
            const formData = new FormData();
            formData.append('file', fileData, fileName);
            
            const headers = {
                'Authorization': `Bearer ${token}`,
                ...formData.getHeaders()
            };

            // 4. Upload to gateway endpoint
            const uploadUrl = `${GATEWAY_URL}/api/ontology/upload/${projectId}`;
            console.log(`[OntoCode] Uploading to: ${uploadUrl}`);
            console.log(`[OntoCode] File size: ${fileData instanceof Buffer ? fileData.length : 'stream'} bytes`);
            
            const response = await axios.post(uploadUrl, formData, {
                headers,
                maxRedirects: 0,  // Disable redirects to catch any redirect issues
                timeout: 300000,  // 5 minutes timeout for large files
                maxContentLength: Infinity,
                maxBodyLength: Infinity,
                validateStatus: (status) => status < 500 // Accept all non-5xx responses
            });

            console.log(`[OntoCode] Upload response status: ${response.status}`);
            console.log(`[OntoCode] Upload response data:`, response.data);

            // 5. Check if upload was successful
            if (response.status === 200 || response.status === 201) {
                console.log(`[OntoCode] Upload successful for project: ${projectId}`);
                this.postMessage({ type: 'fileReady', projectId: projectId });
                vscode.window.showInformationMessage(`Ontology "${fileName}" uploaded successfully. Processing started...`);
            } else {
                throw new Error(`Upload failed with status ${response.status}: ${JSON.stringify(response.data)}`);
            }

        } catch (e: any) {
            console.error('[OntoCode] Upload error:', e);
            
            let errorMessage = 'An unknown error occurred';
            
            if (e.response) {
                // The request was made and the server responded with a status code
                // that falls out of the range of 2xx
                console.error('[OntoCode] Error response status:', e.response.status);
                console.error('[OntoCode] Error response headers:', e.response.headers);
                console.error('[OntoCode] Error response data:', e.response.data);
                
                errorMessage = e.response.data?.error || 
                              e.response.data?.message || 
                              `Server error: ${e.response.status}`;
            } else if (e.request) {
                // The request was made but no response was received
                console.error('[OntoCode] No response received:', e.request);
                errorMessage = 'No response from server. Is the gateway running on port 8082?';
            } else {
                // Something happened in setting up the request that triggered an Error
                console.error('[OntoCode] Error setting up request:', e.message);
                errorMessage = e.message;
            }
            
            // Handle specific error types
            if (e.code === 'ECONNREFUSED') {
                errorMessage = 'Cannot connect to gateway on port 8082. Please ensure the gateway is running.';
            } else if (e.code === 'ETIMEDOUT') {
                errorMessage = 'Upload timed out. The file may be too large or the server is not responding.';
            } else if (e.message.includes('Maximum number of redirects')) {
                errorMessage = 'Gateway configuration error: Too many redirects. Check gateway routing configuration.';
            }
            
            console.error(`[OntoCode] Final error message: ${errorMessage}`);
            vscode.window.showErrorMessage(`Failed to load ontology: ${errorMessage}`);
            
            // 6. Notify webview of failure, including the error message
            this.postMessage({ type: 'loadingFailed', error: errorMessage });
        }
    }

    /**
     * Find the best OWL editor currently open in VSCode
     */
    private findBestOwlEditor(): vscode.TextEditor | undefined {
        const activeEditor = vscode.window.activeTextEditor;
        if (activeEditor && activeEditor.document.fileName.toLowerCase().endsWith('.owl')) {
            return activeEditor;
        }
        return vscode.window.visibleTextEditors.find(
            editor => editor.document.fileName.toLowerCase().endsWith('.owl')
        );
    }

    /**
     * Update the webview content
     */
    private _update() {
        this._panel.webview.html = this._getHtmlForWebview(this._panel.webview);
    }
    
    /**
     * Generate HTML for the webview
     */
    private _getHtmlForWebview(webview: vscode.Webview): string {
        const buildPath = vscode.Uri.joinPath(this._extensionUri, 'webview-src', 'dist');
        const indexPath = vscode.Uri.joinPath(buildPath, 'index.html');
        let htmlContent = fs.readFileSync(indexPath.fsPath, 'utf8');
        const nonce = getNonce();

        const vscodeApiInjectionScript = `
            <script nonce="${nonce}">
                const vscode = acquireVsCodeApi();
                window.vscode = vscode;
            </script>
        `;
        
        // Remove any existing CSP
        htmlContent = htmlContent.replace(/<meta[^>]*Content-Security-Policy[^>]*>/gi, '');
        
        // Add our CSP and VSCode API injection
        htmlContent = htmlContent.replace(
            /(<head>)/,
            `$1
            <meta http-equiv="Content-Security-Policy" content="
                default-src 'none'; 
                img-src ${webview.cspSource} https: data: blob:; 
                script-src 'nonce-${nonce}'; 
                style-src ${webview.cspSource} 'unsafe-inline'; 
                font-src ${webview.cspSource} data:; 
                connect-src ${GATEWAY_URL};
            ">
            ${vscodeApiInjectionScript}`
        );
        
        // Fix resource URIs
        htmlContent = htmlContent.replace(/(href|src)="([^"]+)"/g, (match, attr, rawPath) => {
            const resourcePath = vscode.Uri.joinPath(
                buildPath, 
                rawPath.startsWith('/') ? rawPath.substring(1) : rawPath
            );
            return `${attr}="${webview.asWebviewUri(resourcePath)}"`;
        });
        
        // Add nonce to all script tags
        return htmlContent.replace(/<script/g, `<script nonce="${nonce}"`);
    }

    /**
     * Clean up resources
     */
    public dispose() {
        console.log('[OntoCode] Disposing panel');
        OntoCodePanel.currentPanel = undefined;
        this._panel.dispose();
        
        while (this._disposables.length) {
            const disposable = this._disposables.pop();
            disposable?.dispose();
        }
    }
}

/**
 * Generate a random nonce for CSP
 */
function getNonce(): string {
    let text = '';
    const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}