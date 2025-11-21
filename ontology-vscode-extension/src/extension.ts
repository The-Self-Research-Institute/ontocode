// Fix: Removed triple-slash directive for node types as we are removing node-specific dependencies.

import * as vscode from 'vscode';
// Fix: Removed 'path' and 'fs' imports to use VSCode's API and web standards.
// Fix: Removed node.js form-data import - using native browser FormData instead
import axios, { AxiosError } from 'axios';
import { insertCitationCommand } from './features/citationInsertion';
import { CitationPickerPanel } from './webview/citationPicker';
import { CollaborationManager } from './collaboration/CollaborationManager';
import { EditCapture } from './collaboration/EditCapture';
import { RemoteEditApplier } from './collaboration/RemoteEditApplier';

const TOKEN_KEY = 'ontocode.authToken';
const GATEWAY_URL = 'http://localhost:8082'; // Gateway port
const OWL_EDITOR_URL = 'http://localhost:8083'; // OWL Editor service (WebSocket endpoint)

/**
 * Parse JWT token to extract user information
 * @param token JWT token string
 * @returns Decoded token payload or null if invalid
 */
function parseJwtToken(token: string): { userId?: string; username?: string; sub?: string } | null {
    try {
        console.log('[OntoCode] 🔍 Parsing JWT token...');
        console.log('[OntoCode] Token length:', token?.length || 0);
        console.log('[OntoCode] Token preview:', token?.substring(0, 50) + '...');
        
        if (!token || typeof token !== 'string') {
            console.error('[OntoCode] ❌ Token is null or not a string');
            return null;
        }
        
        // JWT tokens have three parts separated by dots: header.payload.signature
        const parts = token.split('.');
        console.log('[OntoCode] Token parts count:', parts.length);
        
        if (parts.length !== 3) {
            console.error('[OntoCode] ❌ Invalid JWT token format - expected 3 parts, got', parts.length);
            console.error('[OntoCode] Token value:', token);
            return null;
        }
        
        // Decode the payload (second part)
        const payload = parts[1];
        console.log('[OntoCode] Payload part length:', payload.length);
        
        // JWT uses base64url encoding, convert to standard base64
        const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = atob(base64);
        const decoded = JSON.parse(jsonPayload);
        
        console.log('[OntoCode] ✅ JWT Token Decoded Successfully:', JSON.stringify(decoded, null, 2));
        
        return decoded;
    } catch (error) {
        console.error('[OntoCode] ❌ Error parsing JWT token:', error);
        console.error('[OntoCode] Token that failed:', token);
        return null;
    }
}

// Type definitions for messages between VS Code and the webview
type WebviewMessage =
  | { type: 'storedAuthToken'; token: string | null }
  | { type: 'loggedOut' }
  | { type: 'showLogin' }
  | { type: 'showLoading' }
  | { type: 'fileReady'; projectId: string }
  | { type: 'loadingFailed'; error: string }
  // Fix: Added message type for API responses from the proxy
  | { type: 'apiResponse'; requestId: string; response?: any; error?: any }
  // Collaborative editing messages
  | { type: 'remoteEdit'; edit: any }
  | { type: 'presenceUpdate'; presence: any }
  | { type: 'lockUpdate'; lock: any }
  | { type: 'collaborationStatus'; connected: boolean };

type ExtensionMessage =
  | { type: 'error'; value: string }
  | { type: 'saveAuthToken'; token: string }
  | { type: 'requestAuthToken' }
  | { type: 'logout' }
  // Fix: Added message types for API requests to the proxy
  | { type: 'apiGet'; requestId: string; url: string; params?: Record<string, unknown> }
  | { type: 'apiPost'; requestId: string; url: string; body?: unknown }
  | { type: 'apiDelete'; requestId: string; url: string; params?: Record<string, unknown> }
  | { type: 'webviewReady' }
  | { type: 'downloadOntology'; url: string; filename: string }
  | { type: 'downloadCurrentOntology' }
  | { type: 'fileLoaded'; projectId: string } // File selected from menu
  | { type: 'requestCollaborationStatus' } // Request current collaboration status
  | { type: 'showNotification'; notification: { type: string; title: string; message: string; actions?: string[] } } // System notification
  | { type: 'cursorMoved'; nodeId: string; nodeName: string }; // User moved cursor to a node


export function activate(context: vscode.ExtensionContext) {
    console.log('OntoCode extension is now active!');

    // Register all commands
    context.subscriptions.push(
        // Fix: Made command handler async to support async panel creation/file upload.
        vscode.commands.registerCommand('ontocode.edit', async () => {
            // Check if there's an active OWL file first
            const activeEditor = vscode.window.activeTextEditor;
            const hasActiveOwl = activeEditor && activeEditor.document.fileName.toLowerCase().endsWith('.owl');
            
            // Fix: Use context.extensionUri to get the extension's URI.
            const panel = await OntoCodePanel.createOrShow(context.extensionUri, context, hasActiveOwl);
            
            if (hasActiveOwl) {
                // Upload the active OWL file
                panel.setPendingUpload(true);
            } else {
                // No active OWL file, show file picker
                console.log('[OntoCode] No active OWL file, prompting user to select one...');
                const fileUri = await vscode.window.showOpenDialog({
                    canSelectMany: false,
                    openLabel: 'Open OWL File',
                    filters: {
                        'OWL Files': ['owl'],
                        'All Files': ['*']
                    }
                });
                
                if (fileUri && fileUri[0]) {
                    console.log('[OntoCode] User selected file:', fileUri[0].fsPath);
                    panel.setPendingUpload(false, fileUri[0]);
                } else {
                    console.log('[OntoCode] User cancelled file selection');
                    vscode.window.showInformationMessage('Please select an OWL file to edit.');
                }
            }
        }),
        // Fix: Made command handler async to support async panel creation/file upload.
        vscode.commands.registerCommand('ontocode.editLargeFile', async (uri: vscode.Uri) => {
            if (!uri) {
                vscode.window.showErrorMessage("This command should be run by right-clicking an OWL file in the explorer.");
                return;
            }
            // Fix: Use context.extensionUri to get the extension's URI.
            const panel = await OntoCodePanel.createOrShow(context.extensionUri, context, true);
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
        vscode.commands.registerCommand('ontocode.showCollaborationStatus', async () => {
            console.log('[OntoCode] 📊 Showing collaboration status...');
            const token = await (context as any).secrets.get(TOKEN_KEY);
            
            if (!token) {
                const msg = '❌ Not logged in - No authentication token found';
                vscode.window.showWarningMessage(msg);
                console.log('[OntoCode]', msg);
                return;
            }
            
            console.log('[OntoCode] Token retrieved from secrets');
            const tokenData = parseJwtToken(token);
            
            if (!tokenData) {
                const msg = '❌ Invalid token - Please logout and login again';
                vscode.window.showErrorMessage(msg);
                console.error('[OntoCode]', msg);
                return;
            }
            
            const userId = tokenData?.userId || tokenData?.sub || 'unknown';
            const username = tokenData?.username || tokenData?.sub || 'User';
            
            const panel = OntoCodePanel.currentPanel;
            const isConnected = panel?.getCollaborationStatus() ?? false;
            
            const message = `✅ USERNAME: ${username}\n📝 User ID: ${userId}\n🔌 Connected: ${isConnected}`;
            vscode.window.showInformationMessage(message, { modal: true });
            console.log('[OntoCode] Collaboration Status:');
            console.log('  Username:', username);
            console.log('  User ID:', userId);
            console.log('  Connected:', isConnected);
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

    // Collaborative editing
    private collaborationManager: CollaborationManager | null = null;
    private editCapture: EditCapture;
    private remoteEditApplier: RemoteEditApplier;
    private currentProjectId: string | null = null;

    // Fix: Made createOrShow async to handle async webview content loading.
    public static async createOrShow(extensionUri: vscode.Uri, context: vscode.ExtensionContext, shouldTriggerUpload: boolean = false): Promise<OntoCodePanel> {
        const column = vscode.window.activeTextEditor?.viewColumn ?? vscode.ViewColumn.One;

        if (OntoCodePanel.currentPanel) {
            OntoCodePanel.currentPanel._panel.reveal(column);
            // If we should trigger upload and webview is ready, do it now
            if (shouldTriggerUpload && OntoCodePanel.currentPanel._isWebviewReady) {
                OntoCodePanel.currentPanel.triggerPendingUpload();
            }
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

        // Initialize collaborative editing components
        this.editCapture = new EditCapture();
        this.remoteEditApplier = new RemoteEditApplier();

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
                    case 'downloadOntology':
                        this.handleDownload(message.url, message.filename);
                        break;
                    case 'downloadCurrentOntology':
                        this.handleDownloadCurrent();
                        break;
                    case 'fileLoaded':
                        // User selected a file from the File menu
                        console.log('[OntoCode] 📂 File loaded from menu:', message.projectId);
                        console.log('[OntoCode] 🔄 Posting fileReady message to webview');
                        this.postMessage({ type: 'fileReady', projectId: message.projectId });
                        
                        // Initialize collaboration for the loaded file (works for both owned and shared files)
                        const fileToken = await (this._context as any).secrets.get(TOKEN_KEY);
                        if (fileToken) {
                            console.log('[OntoCode] 🤝 Initializing collaboration for project:', message.projectId);
                            await this.initializeCollaborationForProject(message.projectId, fileToken);
                        } else {
                            console.warn('[OntoCode] ⚠️ No auth token found, cannot initialize collaboration');
                        }
                        break;
                    case 'requestCollaborationStatus':
                        // Webview is requesting current collaboration status
                        const isConnected = this.collaborationManager?.isConnected() ?? false;
                        console.log('[OntoCode] 📊 Collaboration status requested');
                        console.log('[OntoCode]   - Manager exists:', !!this.collaborationManager);
                        console.log('[OntoCode]   - Is connected:', isConnected);
                        this.postMessage({
                            type: 'collaborationStatus',
                            connected: isConnected
                        });
                        console.log('[OntoCode] Status response sent:', isConnected);
                        break;
                    case 'showNotification':
                        this.handleNotification(message.notification);
                        break;
                    case 'cursorMoved':
                        // User moved cursor to a node in the tree
                        if (this.editCapture && this.currentProjectId) {
                            console.log('[OntoCode] 👆 Cursor moved to node:', message.nodeName);
                            const selectedNodes = message.nodeId ? [message.nodeId] : [];
                            this.editCapture.captureCursorMoved(
                                this.currentProjectId, 
                                message.nodeId,
                                selectedNodes
                            );
                        }
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
        
        // Check if this is a public endpoint (login/signup) that doesn't require authentication
        const isPublicEndpoint = url.includes('/api/auth/login') || url.includes('/api/auth/signup');
        
        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        
        // Do not proceed if unauthenticated, unless it's a login/signup endpoint
        if (!token && !isPublicEndpoint) {
            console.log('[Proxy] Request to', url, 'requires authentication');
            this.postMessage({ type: 'apiResponse', requestId, error: { message: 'User is not authenticated.', status: 401 }});
            return;
        }
        
        const headers: any = {
            
            'Content-Type': 'application/json'
        };
        
        // Only add Authorization header if we have a token
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }

        try {
            let response;
            const fullUrl = `${GATEWAY_URL}${url}`;
            console.log(`[Proxy] ${type.replace('api', '').toUpperCase()}: ${fullUrl}`, isPublicEndpoint ? '(public)' : '(authenticated)');

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

            this.postMessage({ type: 'apiResponse', requestId, response: response.data });
        } catch (e: unknown) {
            const fullUrl = `${GATEWAY_URL}${url}`; // Redeclare for error logging
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
                console.error('[Proxy] API Request Error:', {
                    url: fullUrl,
                    status: axiosError.response?.status,
                    statusText: axiosError.response?.statusText,
                    data: axiosError.response?.data,
                    message: axiosError.message,
                    requestBody: type === 'apiPost' ? message.body : undefined
                });
            } else if (e instanceof Error) {
                errorResponse = { message: e.message };
                console.error('[Proxy] API Request Error:', e.message);
            }
            this.postMessage({ type: 'apiResponse', requestId, error: errorResponse });
        }
    }

    public postMessage(message: WebviewMessage) {
        this._panel.webview.postMessage(message);
    }

    /**
     * Handle notification requests from webview
     */
    private handleNotification(notification: any) {
        const message = `${notification.title}\n${notification.message}`;
        
        switch (notification.type) {
            case 'success':
                vscode.window.showInformationMessage(message);
                break;
            case 'error':
                vscode.window.showErrorMessage(message);
                break;
            case 'warning':
                vscode.window.showWarningMessage(message);
                break;
            case 'info':
            default:
                vscode.window.showInformationMessage(message);
                break;
        }
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

        // 2. Send fileReady message BEFORE upload to show loading dialog immediately
        console.log(`[OntoCode] Sending fileReady message for project: ${projectId}`);
        this.postMessage({ type: 'fileReady', projectId: projectId });

        try {
            // 3. Prepare the form data for multipart upload
            // Convert Uint8Array to Blob for web extension compatibility
            // Create a new Uint8Array with ArrayBuffer to ensure compatibility
            const buffer = new Uint8Array(fileData.buffer.byteLength);
            buffer.set(new Uint8Array(fileData.buffer));
            const fileBlob = new Blob([buffer], { type: 'application/rdf+xml' });
            // Create a File object from the Blob to preserve filename
            const file = new File([fileBlob], fileName, { type: 'application/rdf+xml' });
            const formData = new FormData();
            formData.append('file', file);
            
            // Extract user email from JWT token
            try {
                const tokenParts = token.split('.');
                if (tokenParts.length === 3) {
                    const payload = JSON.parse(Buffer.from(tokenParts[1], 'base64').toString());
                    if (payload.email) {
                        formData.append('ownerEmail', payload.email);
                        console.log(`[OntoCode] Adding owner email: ${payload.email}`);
                    }
                }
            } catch (tokenError) {
                console.warn('[OntoCode] Could not extract email from token:', tokenError);
            }
            
            const headers = {
                'Authorization': `Bearer ${token}`,
                // Browser FormData sets its own Content-Type with boundary
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
                vscode.window.showInformationMessage(`Ontology "${fileName}" uploaded successfully. Processing started...`);
                
                // 6. Initialize collaborative editing
                await this.initializeCollaborationForProject(projectId, token);
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
     * Handle download request for a specific file
     */
    private async handleDownload(url: string, filename: string) {
        try {
            console.log(`[OntoCode] Downloading file from: ${url}`);
            console.log(`[OntoCode] Filename: ${filename}`);
            
            // Get auth token
            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (!token) {
                vscode.window.showErrorMessage('You must be logged in to download files.');
                return;
            }

            // Make request to download file
            const fullUrl = `${GATEWAY_URL}${url}`;
            console.log(`[OntoCode] Full URL: ${fullUrl}`);
            
            // Show progress notification for large ontologies
            vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: `Downloading ${filename}...`,
                cancellable: false
            }, async (progress) => {
                progress.report({ message: 'This may take several minutes for large ontologies' });
                
                const response = await axios.get(fullUrl, {
                    headers: { 'Authorization': `Bearer ${token}` },
                    responseType: 'arraybuffer',
                    timeout: 300000 // 5 minutes for large files like go-plus.owl
                });

                console.log(`[OntoCode] Response status: ${response.status}`);
                console.log(`[OntoCode] Response data length: ${response.data.byteLength} bytes`);

                // Show save dialog
                const saveUri = await vscode.window.showSaveDialog({
                    defaultUri: vscode.Uri.file(filename),
                    filters: {
                        'OWL Files': ['owl'],
                        'All Files': ['*']
                    }
                });

                if (saveUri) {
                    console.log(`[OntoCode] Saving to: ${saveUri.fsPath}`);
                    // Save file
                    await (vscode.workspace as any).fs.writeFile(saveUri, new Uint8Array(response.data));
                    vscode.window.showInformationMessage(`File saved successfully to ${saveUri.fsPath}`);
                } else {
                    console.log(`[OntoCode] User cancelled save dialog`);
                }
            });
        } catch (error) {
            console.error('[OntoCode] Download error:', error);
            if (axios.isAxiosError(error)) {
                const axiosError = error as AxiosError;
                if (axiosError.response) {
                    console.error('[OntoCode] Error response:', axiosError.response.status, axiosError.response.data);
                    vscode.window.showErrorMessage(`Download failed: ${axiosError.response.status} - ${JSON.stringify(axiosError.response.data)}`);
                } else if (axiosError.request) {
                    console.error('[OntoCode] No response received');
                    vscode.window.showErrorMessage('Download failed: No response from server. The file may be too large or the server is taking too long to export it.');
                } else {
                    console.error('[OntoCode] Error:', axiosError.message);
                    vscode.window.showErrorMessage(`Download failed: ${axiosError.message}`);
                }
            } else {
                vscode.window.showErrorMessage('Failed to download file. See console for details.');
            }
        }
    }

    /**
     * Handle download of currently loaded ontology
     */
    private async handleDownloadCurrent() {
        try {
            const activeEditor = this.findBestOwlEditor();
            if (!activeEditor) {
                vscode.window.showWarningMessage('No active .owl file found.');
                return;
            }

            const fileName = activeEditor.document.uri.path.substring(
                activeEditor.document.uri.path.lastIndexOf('/') + 1
            );
            
            // Show save dialog
            const saveUri = await vscode.window.showSaveDialog({
                defaultUri: vscode.Uri.file(fileName),
                filters: {
                    'OWL Files': ['owl'],
                    'All Files': ['*']
                }
            });

            if (saveUri) {
                // Copy file content
                const content = activeEditor.document.getText();
                await (vscode.workspace as any).fs.writeFile(
                    saveUri, 
                    new TextEncoder().encode(content)
                );
                vscode.window.showInformationMessage(`File saved successfully to ${saveUri.fsPath}`);
            }
        } catch (error) {
            console.error('[OntoCode] Download error:', error);
            vscode.window.showErrorMessage('Failed to save file. See console for details.');
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
                // Fallback for minified bundle expecting a global toggleNode
                if (typeof window.toggleNode !== 'function') {
                    window.toggleNode = () => {};
                }
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
                script-src 'nonce-${nonce}' https://cdn.tailwindcss.com https://unpkg.com https://aistudiocdn.com ${(webview as any).cspSource};
                style-src ${(webview as any).cspSource} 'unsafe-inline' https://unpkg.com https://cdn.tailwindcss.com;
                font-src ${(webview as any).cspSource} https://unpkg.com data:; 
                connect-src ${GATEWAY_URL} https://unpkg.com https://aistudiocdn.com;
            ">
            <base href="${baseUri}">
            ${vscodeApiInjectionScript}`
        );
        
        // Add nonce to inline scripts (importmap, etc.)
        htmlContent = htmlContent.replace(/<script type="importmap">/g, `<script type="importmap" nonce="${nonce}">`);
        
        // Add nonce to our main application script. The <base> tag handles the path resolution,
        // so we can change the src to be relative.
        // Add cache busting timestamp to force reload
        const cacheBuster = Date.now();
        htmlContent = htmlContent.replace(/(href|src)="([^"]+)"/g, (match, attr, rawPath) => {
            if (rawPath.startsWith('https:') || rawPath.startsWith('http:') || rawPath.startsWith('data:')) {
                return match; // Return the original string (e.g., 'href="https://cdn.tailwindcss.com"')
            }
            // Fix: Replace missing Uri.joinPath with Uri.parse and string interpolation for compatibility.
            const resourcePath = vscode.Uri.parse(
                `${buildPath.toString()}/${rawPath.startsWith('/') ? rawPath.substring(1) : rawPath}`
            );
            // Fix: Cast webview to `any` to access `asWebviewUri` method, bypassing outdated type definitions.
            const webviewUri = (webview as any).asWebviewUri(resourcePath);
            // Add cache buster for JS and CSS files
            if (rawPath.includes('.js') || rawPath.includes('.css')) {
                return `${attr}="${webviewUri}?v=${cacheBuster}"`;
            }
            return `${attr}="${webviewUri}"`;
        });


        return htmlContent;
    }

    /**
     * Clean up resources
     */
    public dispose() {
        console.log('[OntoCode] Disposing panel');
        
        // Disconnect collaboration
        if (this.collaborationManager) {
            this.collaborationManager.disconnect().catch(err => {
                console.error('[OntoCode] Error disconnecting collaboration:', err);
            });
        }
        
        this.editCapture.dispose();
        
        OntoCodePanel.currentPanel = undefined;
        this._panel.dispose();
        
        while (this._disposables.length) {
            const disposable = this._disposables.pop();
            disposable?.dispose();
        }
    }

    /**
     * Get current collaboration connection status.
     */
    public getCollaborationStatus(): boolean {
        return this.collaborationManager?.isConnected() ?? false;
    }

    /**
     * Initialize collaborative editing for a project.
     */
    /**
     * Initialize collaboration for a project using the auth token.
     * Extracts user info from JWT and calls initializeCollaboration.
     */
    private async initializeCollaborationForProject(projectId: string, token: string): Promise<void> {
        try {
            // Parse JWT token to extract user info
            const tokenData = parseJwtToken(token);
            if (!tokenData) {
                console.error('[OntoCode] Failed to parse JWT token for collaboration');
                return;
            }
            
            // Extract userId and username from token
            // JWT tokens typically have 'sub' (subject) for userId and 'username' or 'name' field
            const userId = tokenData.userId || tokenData.sub || 'unknown';
            const username = tokenData.username || tokenData.sub || 'User';
            
            console.log(`[OntoCode] Extracted user info - userId: ${userId}, username: ${username}`);
            
            // Call the main initialization method
            await this.initializeCollaboration(projectId, userId, username);
        } catch (error) {
            console.error('[OntoCode] Error initializing collaboration for project:', error);
            // Don't throw - collaboration is optional, file upload should still work
        }
    }

    private async initializeCollaboration(projectId: string, userId: string, username: string): Promise<void> {
        try {
            console.log('[OntoCode] ========================================');
            console.log('[OntoCode] Initializing Collaboration');
            console.log('[OntoCode] Project ID:', projectId);
            console.log('[OntoCode] User ID:', userId);
            console.log('[OntoCode] USERNAME:', username);
            console.log('[OntoCode] WebSocket URL:', OWL_EDITOR_URL);
            console.log('[OntoCode] ========================================');
            
            // Create collaboration manager (connects to OWL Editor WebSocket)
            this.collaborationManager = new CollaborationManager(OWL_EDITOR_URL, userId, username);
            this.editCapture.setCollaborationManager(this.collaborationManager);

            // Set up event handlers
            this.collaborationManager.setHandlers({
                onEditReceived: async (edit) => {
                    console.log('[OntoCode] Received remote edit:', edit);
                    this.editCapture.setApplyingRemoteEdit(true);
                    await this.remoteEditApplier.applyRemoteEdit(edit);
                    this.editCapture.setApplyingRemoteEdit(false);
                    
                    // Notify webview of remote edit
                    this.postMessage({
                        type: 'remoteEdit',
                        edit
                    });
                },
                
                onPresenceUpdate: (presence) => {
                    console.log('[OntoCode] Presence update:', presence);
                    
                    // Notify webview of presence change
                    this.postMessage({
                        type: 'presenceUpdate',
                        presence
                    });
                },
                
                onLockUpdate: (lock) => {
                    console.log('[OntoCode] Lock update:', lock);
                    
                    // Notify webview of lock change
                    this.postMessage({
                        type: 'lockUpdate',
                        lock
                    });
                },
                
                onConnectionChange: (connected) => {
                    console.log('[OntoCode] 🔄 Connection status changed:', connected);
                    console.log('[OntoCode] Sending collaborationStatus message to webview...');
                    
                    // Notify webview of connection status
                    this.postMessage({
                        type: 'collaborationStatus',
                        connected
                    });
                    
                    console.log('[OntoCode] ✅ collaborationStatus message sent to webview');
                    
                    vscode.window.showInformationMessage(
                        connected ? 'Connected to collaborative editing' : 'Disconnected from collaborative editing'
                    );
                },
                
                onError: (error) => {
                    console.error('[OntoCode] Collaboration error:', error);
                    vscode.window.showErrorMessage(`Collaboration error: ${error}`);
                }
            });

            // Set up remote edit applier
            this.remoteEditApplier.setEditHandler(async (edit) => {
                // This will be called to apply remote edits
                // The actual UI update is handled by the webview
                console.log('[OntoCode] Applying remote edit to state:', edit.type);
            });

            this.remoteEditApplier.setConflictHandler((edit, reason) => {
                console.warn('[OntoCode] Edit conflict:', reason, edit);
                vscode.window.showWarningMessage(`Edit conflict: ${reason}`);
            });

            // Connect to server
            await this.collaborationManager.connect();
            
            // Join the project
            await this.collaborationManager.joinProject(projectId);
            
            this.currentProjectId = projectId;
            
            console.log('[OntoCode] Collaboration initialized successfully');
            
        } catch (error) {
            console.error('[OntoCode] Failed to initialize collaboration:', error);
            vscode.window.showErrorMessage(`Failed to enable collaborative editing: ${error}`);
        }
    }

    /**
     * Disconnect from collaborative editing.
     */
    private async disconnectCollaboration(): Promise<void> {
        if (this.collaborationManager) {
            try {
                await this.collaborationManager.disconnect();
                this.collaborationManager = null;
                this.currentProjectId = null;
                console.log('[OntoCode] Disconnected from collaboration');
            } catch (error) {
                console.error('[OntoCode] Error disconnecting:', error);
            }
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
