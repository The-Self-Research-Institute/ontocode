// Fix: Removed triple-slash directive for node types as we are removing node-specific dependencies.

import * as vscode from 'vscode';
// Fix: Removed 'path' and 'fs' imports to use VSCode's API and web standards.
import FormData from 'form-data';
import axios, { AxiosError } from 'axios';
import { insertCitationCommand } from './features/citationInsertion';
import { CitationPickerPanel } from './webview/citationPicker';

const TOKEN_KEY = 'ontocode.authToken';
const GATEWAY_URL = 'http://localhost:8082'; // Gateway port

// Type definitions for messages between VS Code and the webview
type WebviewMessage =
  | { type: 'storedAuthToken'; token: string | null }
  | { type: 'loggedOut' }
  | { type: 'showLogin' }
  | { type: 'showLoading' }
  | { type: 'fileReady'; projectId: string }
  | { type: 'loadingFailed'; error: string }
  // Fix: Added message type for API responses from the proxy
  | { type: 'apiResponse'; requestId: string; response?: any; error?: any };

type ExtensionMessage =
  | { type: 'error'; value: string }
  | { type: 'saveAuthToken'; token: string }
  | { type: 'requestAuthToken' }
  | { type: 'logout' }
  // Fix: Added message types for API requests to the proxy
  | { type: 'apiGet'; requestId: string; url: string; params?: Record<string, unknown> }
  | { type: 'apiPost'; requestId: string; url: string; body?: unknown }
  | { type: 'apiDelete'; requestId: string; url: string; params?: Record<string, unknown> }
  | { type: 'webviewReady' }; // <-- FIX: Add message from webview


export function activate(context: vscode.ExtensionContext) {
    console.log('OntoCode extension is now active!');

    // Register all commands
    context.subscriptions.push(
        // Fix: Made command handler async to support async panel creation/file upload.
        vscode.commands.registerCommand('ontocode.edit', async () => {
            // Fix: Use context.extensionUri to get the extension's URI.
            const panel = await OntoCodePanel.createOrShow(context.extensionUri, context);
            // FIX: Don't trigger upload here. Set it as pending.
            panel.setPendingUpload(true);
        }),
        // Fix: Made command handler async to support async panel creation/file upload.
        vscode.commands.registerCommand('ontocode.editLargeFile', async (uri: vscode.Uri) => {
            if (!uri) {
                vscode.window.showErrorMessage("This command should be run by right-clicking an OWL file in the explorer.");
                return;
            }
            // Fix: Use context.extensionUri to get the extension's URI.
            const panel = await OntoCodePanel.createOrShow(context.extensionUri, context);
            // FIX: Don't trigger upload here. Set it as pending.
            panel.setPendingUpload(false, uri);
        }),
        vscode.commands.registerCommand('ontocode.logout', async () => {
            // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
            await (context as any).secrets.delete(TOKEN_KEY);
            if (OntoCodePanel.currentPanel) {
                OntoCodePanel.currentPanel.dispose();
            }
            vscode.window.showInformationMessage('You have been successfully logged out.');
        }),
        vscode.commands.registerCommand('ontocode.insertCitation', insertCitationCommand),
        // Fix: Use context.extensionUri to get the extension's URI.
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

    // FIX: Add state to track webview readiness and pending uploads
    private _isWebviewReady: boolean = false;
    private _pendingFileUri: vscode.Uri | null = null;
    private _isPendingRegularUpload: boolean = false;

    // Fix: Made createOrShow async to handle async webview content loading.
    public static async createOrShow(extensionUri: vscode.Uri, context: vscode.ExtensionContext): Promise<OntoCodePanel> {
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
                // Fix: Replace missing Uri.joinPath with Uri.parse and string interpolation for compatibility.
                localResourceRoots: [vscode.Uri.parse(`${extensionUri.toString()}/webview-src/dist`)]
            }
        );

        OntoCodePanel.currentPanel = new OntoCodePanel(panel, extensionUri, context);
        // Fix: Awaited the update of the webview content after panel creation.
        await OntoCodePanel.currentPanel._update();
        return OntoCodePanel.currentPanel;
    }

    private constructor(panel: vscode.WebviewPanel, extensionUri: vscode.Uri, context: vscode.ExtensionContext) {
        this._panel = panel;
        this._extensionUri = extensionUri;
        this._context = context;

        // Fix: Removed synchronous _update() call from constructor. It's now called from createOrShow.
        this._panel.onDidDispose(() => this.dispose(), null, this._disposables);

        // Handle messages from the webview
        this._panel.webview.onDidReceiveMessage(
            async (message: ExtensionMessage) => {
                switch (message.type) {
                    // FIX: Add a case to handle the webview's "ready" message
                    case 'webviewReady':
                        console.log('[OntoCode] Received webviewReady message.');
                        this._isWebviewReady = true;
                        this.triggerPendingUpload(); // Trigger any upload that was waiting
                        break;
                    case 'error':
                        vscode.window.showErrorMessage(message.value);
                        break;
                    case 'saveAuthToken':
                        if (message.token) {
                            // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
                            await (this._context as any).secrets.store(TOKEN_KEY, message.token);
                            vscode.window.showInformationMessage('Authentication successful.');
                        }
                        break;
                    case 'requestAuthToken':
                        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
                        const token = await (this._context as any).secrets.get(TOKEN_KEY);
                        this.postMessage({ type: 'storedAuthToken', token: token || null });
                        break;
                    case 'logout':
                        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
                        await (this._context as any).secrets.delete(TOKEN_KEY);
                        this.postMessage({ type: 'loggedOut' });
                        break;
                    // Fix: Added cases to handle API proxy requests from the webview
                    case 'apiGet':
                    case 'apiPost':
                    case 'apiDelete':
                        this.handleApiRequest(message);
                        break;
                }
            },
            null,
            this._disposables
        );
    }

    // FIX: New method to check for and trigger pending uploads
    private triggerPendingUpload() {
        if (this._isPendingRegularUpload) {
            this._isPendingRegularUpload = false;
            console.log('[OntoCode] Webview is ready, triggering pending regular file upload.');
            this.triggerFileUpload();
        } else if (this._pendingFileUri) {
            const uri = this._pendingFileUri;
            this._pendingFileUri = null;
            console.log('[OntoCode] Webview is ready, triggering pending large file upload.');
            this.triggerLargeFileUpload(uri);
        }
    }

    // FIX: New method to set a pending upload from the activate function
    public setPendingUpload(isRegular: boolean, uri: vscode.Uri | null = null) {
        if (isRegular) {
            this._isPendingRegularUpload = true;
        } else if (uri) {
            this._pendingFileUri = uri;
        }
        
        // If webview is *already* ready (e.g., panel was just revealed), trigger now.
        if (this._isWebviewReady) {
            this.triggerPendingUpload();
        }
    }
    
    /**
     * Fix: New method to handle API requests from the webview, acting as a proxy.
     * This centralizes API calls, attaches auth tokens, and bypasses CORS issues.
     */
    private async handleApiRequest(message: Extract<ExtensionMessage, { type: 'apiGet' | 'apiPost' | 'apiDelete' }>) {
        const { requestId, type, url } = message;
        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        // Do not proceed if unauthenticated, unless it's a login/signup endpoint
        // For simplicity, we assume all proxied requests need a token.
        if (!token) {
            this.postMessage({ type: 'apiResponse', requestId, error: { message: 'User is not authenticated.', status: 401 }});
            return;
        }
        
        const headers = {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
        };

        try {
            let response;
            const fullUrl = `${GATEWAY_URL}${url}`;
            console.log(`[Proxy] ${type.replace('api', '').toUpperCase()}: ${fullUrl}`);

            switch (type) {
                case 'apiGet':
                    response = await axios.get(fullUrl, { headers, params: message.params });
                    break;
                case 'apiPost':
                    response = await axios.post(fullUrl, message.body, { headers });
                    break;
                case 'apiDelete':
                    response = await axios.delete(fullUrl, { headers, params: message.params });
                    break;
            }

            this.postMessage({ type: 'apiResponse', requestId, response: { data: response.data, status: response.status } });
        } catch (e: unknown) {
            // Fix: Explicitly type errorResponse to allow for optional properties like status and data.
            // This prevents a TypeScript error when assigning a more complex error object from an AxiosError.
            let errorResponse: { message: string, status?: number, data?: any } = { message: 'An unknown error occurred in the API proxy.' };
            // Fix: Correctly handle errors by casting `e` to AxiosError within the type guard.
            // This resolves issues where `e` is not correctly type-narrowed to `AxiosError`,
            // causing errors when accessing properties like `message` and `response`.
            if (axios.isAxiosError(e)) {
                const axiosError = e as AxiosError;
                errorResponse = {
                    message: axiosError.message,
                    status: axiosError.response?.status,
                    data: axiosError.response?.data,
                };
            } else if (e instanceof Error) {
                errorResponse = { message: e.message };
            }
            console.error('[Proxy] API Request Error:', errorResponse);
            this.postMessage({ type: 'apiResponse', requestId, error: errorResponse });
        }
    }

    public postMessage(message: WebviewMessage) {
        this._panel.webview.postMessage(message);
    }

    /**
     * Handles uploading a large file from a file URI (e.g., from the Explorer context menu).
     */
    // Fix: Refactored to use async vscode.workspace.fs.readFile instead of node 'fs'.
    public async triggerLargeFileUpload(fileUri: vscode.Uri) {
        console.log(`[OntoCode] Triggering large file upload for: ${fileUri.fsPath}`);
        const fullPath = fileUri.path;
        const fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
        // Fix: Cast workspace to `any` to access the `fs` property, bypassing outdated type definitions.
        const fileData = await (vscode.workspace as any).fs.readFile(fileUri);
        const projectId = fileName.endsWith('.owl') ? fileName.slice(0, -4) : fileName;
        
        // Delegate to the shared upload logic
        this._uploadOntology(projectId, fileName, fileData);
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
        // Fix: Replaced path.basename with string manipulation on the URI path.
        const fileName = targetEditor.document.uri.path.substring(targetEditor.document.uri.path.lastIndexOf('/') + 1);
        // Fix: Replaced Buffer.from with TextEncoder to produce a Uint8Array, avoiding Node.js globals.
        const fileBuffer = new TextEncoder().encode(fileContent);
        const projectId = fileName.endsWith('.owl') ? fileName.slice(0, -4) : fileName;

        // Delegate to the shared upload logic
        this._uploadOntology(projectId, fileName, fileBuffer);
    }

    /**
     * Private helper method to handle the actual upload logic.
     * Uploads ontology file to the gateway which routes to the OWL Editor service.
     */
    // Fix: Changed fileData parameter from 'fs.ReadStream | Buffer' to 'Uint8Array'.
    private async _uploadOntology(projectId: string, fileName: string, fileData: Uint8Array) {
        console.log(`[OntoCode] Starting upload for project: ${projectId}, file: ${fileName}`);
        
        // 1. Check for authentication token
        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
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
            // Fix: Updated file size logging to work with Uint8Array instead of Buffer.
            console.log(`[OntoCode] File size: ${fileData.length} bytes`);
            
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
                this._isWebviewReady = false;
                this.postMessage({ type: 'fileReady', projectId: projectId });
                vscode.window.showInformationMessage(`Ontology "${fileName}" uploaded successfully. Processing started...`);
            } else {
                throw new Error(`Upload failed with status ${response.status}: ${JSON.stringify(response.data)}`);
            }

        } catch (e: unknown) {
            console.error('[OntoCode] Upload error:', e);
            
            let errorMessage = 'An unknown error occurred';
            
            if (axios.isAxiosError(e)) {
                const error = e as AxiosError;
                if (error.response) {
                    console.error('[OntoCode] Error response status:', error.response.status);
                    console.error('[OntoCode] Error response headers:', error.response.headers);
                    console.error('[OntoCode] Error response data:', error.response.data);
                    
                    const responseData = error.response.data as { error?: string; message?: string };
                    errorMessage = responseData?.error || responseData?.message || `Server error: ${error.response.status}`;
                } else if (error.request) {
                    console.error('[OntoCode] No response received:', error.request);
                    errorMessage = 'No response from server. Is the gateway running on port 8082?';
                } else {
                    console.error('[OntoCode] Error setting up request:', error.message);
                    errorMessage = error.message;
                }
                
                if (error.code === 'ECONNREFUSED') {
                    errorMessage = 'Cannot connect to gateway on port 8082. Please ensure the gateway is running.';
                } else if (error.code === 'ETIMEDOUT') {
                    errorMessage = 'Upload timed out. The file may be too large or the server is not responding.';
                } else if (error.message.includes('Maximum number of redirects')) {
                    errorMessage = 'Gateway configuration error: Too many redirects. Check gateway routing configuration.';
                }
            } else if (e instanceof Error) {
                errorMessage = e.message;
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
    // Fix: Made _update async to support async HTML content fetching.
    private async _update() {
        this._panel.webview.html = await this._getHtmlForWebview(this._panel.webview);
    }
    
    /**
     * Generate HTML for the webview
     */
    private async _getHtmlForWebview(webview: vscode.Webview): Promise<string> {
        // Path to the build directory on disk
        const buildPath = vscode.Uri.parse(`${this._extensionUri.toString()}/webview-src/dist`);
        
        // Path to the index.html file
        const indexPath = vscode.Uri.parse(`${buildPath.toString()}/index.html`);

        // Get the base URI to use in the webview for resolving relative paths
        const baseUri = (webview as any).asWebviewUri(buildPath).toString() + '/';
        
        // Read the template HTML
        const fileBytes = await (vscode.workspace as any).fs.readFile(indexPath);
        let htmlContent = new TextDecoder('utf-8').decode(fileBytes);
        const nonce = getNonce();

        // The VSCode API script that needs to be injected
        const vscodeApiInjectionScript = `
            <script nonce="${nonce}">
                const vscode = acquireVsCodeApi();
                window.vscode = vscode;
            </script>
        `;
        
        // Remove any existing CSP meta tags to avoid conflicts
        htmlContent = htmlContent.replace(/<meta[^>]*Content-Security-Policy[^>]*>/gi, '');
        
        // Inject our new CSP, a <base> tag, and the API script into the <head>
        htmlContent = htmlContent.replace(
            /(<head>)/,
            `$1
            <meta http-equiv="Content-Security-Policy" content="
                default-src 'none'; 
                img-src ${(webview as any).cspSource} https: data: blob:; 
                script-src 'nonce-${nonce}' https://cdn.tailwindcss.com https://unpkg.com ${(webview as any).cspSource};
                style-src ${(webview as any).cspSource} 'unsafe-inline' https://unpkg.com;
                font-src ${(webview as any).cspSource} data:; 
                connect-src ${GATEWAY_URL};
            ">
            <base href="${baseUri}">
            ${vscodeApiInjectionScript}`
        );
        
        // Add nonce to our main application script. The <base> tag handles the path resolution,
        // so we can change the src to be relative.
        htmlContent = htmlContent.replace(/(href|src)="([^"]+)"/g, (match, attr, rawPath) => {
            if (rawPath.startsWith('https:') || rawPath.startsWith('http:') || rawPath.startsWith('data:')) {
                return match; // Return the original string (e.g., 'href="https://cdn.tailwindcss.com"')
            }
            // Fix: Replace missing Uri.joinPath with Uri.parse and string interpolation for compatibility.
            const resourcePath = vscode.Uri.parse(
                `${buildPath.toString()}/${rawPath.startsWith('/') ? rawPath.substring(1) : rawPath}`
            );
            // Fix: Cast webview to `any` to access `asWebviewUri` method, bypassing outdated type definitions.
            return `${attr}="${(webview as any).asWebviewUri(resourcePath)}"`;
        });


        return htmlContent;
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
    const possible = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz01289';
    for (let i = 0; i < 32; i++) {
        text += possible.charAt(Math.floor(Math.random() * possible.length));
    }
    return text;
}