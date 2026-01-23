// Fix: Removed triple-slash directive for node types as we are removing node-specific dependencies.

import * as vscode from 'vscode';
import axios, { AxiosError } from 'axios';
import { insertCitationCommand } from './features/citationInsertion';
import { CitationPickerPanel } from './webview/citationPicker';
// Use web-compatible collaboration manager in browser environment
import { CollaborationManager } from './collaboration/CollaborationManager.web';
import { ICollaborationManager } from './collaboration/types';
import { EditCapture } from './collaboration/EditCapture';
import { RemoteEditApplier } from './collaboration/RemoteEditApplier';

/**
 * Utility: Convert Uint8Array to base64 string (web-compatible)
 */
function uint8ArrayToBase64(bytes: Uint8Array): string {
    let binary = '';
    const len = bytes.byteLength;
    for (let i = 0; i < len; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
}

/**
 * Utility: Convert base64 string to Uint8Array (web-compatible)
 */
function base64ToUint8Array(base64: string): Uint8Array {
    const binaryString = atob(base64);
    const len = binaryString.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binaryString.charCodeAt(i);
    }
    return bytes;
}

const TOKEN_KEY = 'ontocode.authToken';
// Production endpoints (commented for local development)
const GATEWAY_URL = 'http://13.218.153.101'; // Gateway IPv4
const OWL_EDITOR_URL = GATEWAY_URL; // WebSocket endpoint routed via gateway
const PLUGIN_SERVICE_URL = 'http://13.218.153.101:8087'; // Plugin service port

// Local development endpoints - Gateway port 80 routes to all backend services
// const GATEWAY_URL = 'http://localhost:80'; // Gateway port 80 (routes to auth:8086, editor:8083)
// const OWL_EDITOR_URL = GATEWAY_URL; // OWL Editor service (WebSocket endpoint via Gateway)
// const PLUGIN_SERVICE_URL = 'http://localhost:8087'; // Plugin service direct port

/**
 * Parse JWT token to extract user information
 * @param token JWT token string
 * @returns Decoded token payload or null if invalid
 */
function parseJwtToken(token: string): { userId?: string; username?: string; sub?: string; email?: string; isAdmin?: boolean; workspaceId?: string } | null {
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
  | { type: 'showLoading'; projectId: string }
  | { type: 'fileReady'; projectId: string }
  | { type: 'loadingFailed'; error: string }
  // Fix: Added message type for API responses from the proxy
  | { type: 'apiResponse'; requestId: string; response?: any; error?: any }
  | { type: 'proxyResponse'; reqId: string; data?: any; error?: any }
  | { type: 'invitationToken'; token: string }
  // Collaborative editing messages
  | { type: 'remoteEdit'; edit: any }
  | { type: 'presenceUpdate'; presence: any }
  | { type: 'lockUpdate'; lock: any }
  | { type: 'collaborationStatus'; connected: boolean }
  | { type: 'importStatusUpdate'; status: any }
  | { type: 'shareNotification'; notification: any }
  | { type: 'cursorUpdate'; userId: string; userName: string; position: { x: number; y: number }; timestamp: number }
  | { type: 'pendingFileUpload'; fileName: string; fileContent: string; fileSize: number }
  | { type: 'showSubscriptionPlans' }; // Navigate to subscription plans page

type ExtensionMessage =
  | { type: 'error'; value: string }
  | { type: 'saveAuthToken'; token: string }
  | { type: 'requestAuthToken' }
  | { type: 'logout' }
  // Fix: Added message types for API requests to the proxy
  | { type: 'apiGet'; requestId: string; url: string; params?: Record<string, unknown> }
  | { type: 'apiPost'; requestId: string; url: string; body?: unknown }
  | { type: 'apiPut'; requestId: string; url: string; body?: unknown }
  | { type: 'apiPatch'; requestId: string; url: string; body?: unknown }
  | { type: 'apiDelete'; requestId: string; url: string; params?: Record<string, unknown> }
  | { type: 'proxyRequest'; reqId: string; config: any }
  | { type: 'webviewReady' }
  | { type: 'downloadOntology'; url: string; filename: string }
  | { type: 'downloadCurrentOntology' }
  | { type: 'fileLoaded'; projectId: string } // File selected from menu
  | { type: 'requestCollaborationStatus' } // Request current collaboration status
  | { type: 'showNotification'; notification: { type: string; title: string; message: string; actions?: string[] } } // System notification
  | { type: 'cursorMoved'; nodeId: string; nodeName: string } // User moved cursor to a node
  | { type: 'broadcastCursor'; projectId: string; userId: string; userName: string; position: { x: number; y: number }; timestamp: number } // User cursor position
  | { type: 'importLocalFile'; filePath: string; currentProjectId: string } // Import local OWL file
  | { type: 'uploadOntology'; projectId: string; fileName: string; fileContent: string; ownerEmail?: string } // Upload ontology from webview (admin flow)
  | { type: 'uploadFileToProject'; projectId: string; fileName: string; fileContent: string; fileSize: number }
  | { type: 'showSubscriptionPlans' }; // Request to show subscription plans page


export function activate(context: vscode.ExtensionContext) {
    console.log('OntoCode extension is now active!');
    console.log('[OntoCode] Extension can handle URIs like: vscode://self.ontocode-extension/invite?token=xxx');

    // Register URI handler for invitation links
    const uriHandler = vscode.window.registerUriHandler({
        handleUri: async (uri: vscode.Uri) => {
            console.log('[OntoCode] ========== URI HANDLER TRIGGERED ==========');
            console.log('[OntoCode] Full URI:', uri.toString());
            console.log('[OntoCode] URI path:', uri.path);
            console.log('[OntoCode] URI query:', uri.query);
            
            // Parse URI path and query
            const path = uri.path;
            const query = new URLSearchParams(uri.query);
            const token = query.get('token');
            
            console.log('[OntoCode] Checking path:', path);
            console.log('[OntoCode] Token present:', !!token);
            
            if (path === '/invite' || path === '/invitation') {
                if (token) {
                    console.log('[OntoCode] Processing invitation token:', token.substring(0, 20) + '...');
                    
                    vscode.window.showInformationMessage('Opening invitation...');
                    
                    // Open OntoCode webview with invitation token
                    const panel = await OntoCodePanel.createOrShow(context.extensionUri, context, false);
                    
                    // Wait for webview to be ready before sending message
                    if (panel.isWebviewReady()) {
                        console.log('[OntoCode] Webview ready, sending token immediately');
                        panel.postMessage({ 
                            type: 'invitationToken', 
                            token: token 
                        });
                    } else {
                        console.log('[OntoCode] Webview not ready, storing token for later');
                        panel._pendingInvitationToken = token;
                    }
                    
                    console.log('[OntoCode] Invitation processing complete');
                } else {
                    console.error('[OntoCode] No token in URI!');
                    vscode.window.showErrorMessage('Invalid invitation link: missing token');
                }
            } else {
                console.log('[OntoCode] Unrecognized path:', path);
            }
            console.log('[OntoCode] ========== URI HANDLER COMPLETE ==========');
        }
    });
    
    context.subscriptions.push(uriHandler);

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
    private _lastProjectId: string | null = null; // Track last opened project
    public _pendingInvitationToken: string | null = null; // Track pending invitation token

    // Collaborative editing
    private collaborationManager: ICollaborationManager | null = null;
    private editCapture: EditCapture;
    private remoteEditApplier: RemoteEditApplier;
    private currentProjectId: string | null = null;

    // Fix: Made createOrShow async to handle async webview content loading.
    public static async createOrShow(extensionUri: vscode.Uri, context: vscode.ExtensionContext, shouldTriggerUpload: boolean = false): Promise<OntoCodePanel> {
        const column = vscode.window.activeTextEditor?.viewColumn ?? vscode.ViewColumn.One;

        if (OntoCodePanel.currentPanel) {
            OntoCodePanel.currentPanel._panel.reveal(column);
            // If we should trigger upload and webview is ready, do it now
            if (shouldTriggerUpload && OntoCodePanel.currentPanel.isWebviewReady()) {
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
        return OntoCodePanel.currentPanel!;
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
                        
                        // Send pending invitation token if exists
                        if (this._pendingInvitationToken) {
                            console.log('[OntoCode] Sending pending invitation token to webview');
                            this.postMessage({ 
                                type: 'invitationToken', 
                                token: this._pendingInvitationToken 
                            });
                            this._pendingInvitationToken = null;
                        }
                        
                        // Restore last opened project if exists
                        if (this._lastProjectId) {
                            console.log('[OntoCode] Restoring last project:', this._lastProjectId);
                            this.postMessage({ type: 'fileReady', projectId: this._lastProjectId });
                        }
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
                    case 'apiPut':
                    case 'apiPatch':
                    case 'apiDelete':
                        this.handleApiRequest(message);
                        break;
                    case 'proxyRequest':
                        this.handleProxyRequest(message);
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
                    case 'broadcastCursor':
                        // Broadcast cursor position to all collaborators via WebSocket
                        if (this.collaborationManager) {
                            this.collaborationManager.broadcastCursorPosition(
                                message.projectId,
                                message.userId,
                                message.userName,
                                message.position
                            );
                        }
                        break;
                    case 'importLocalFile':
                        // Handle local file import - upload the file to the system
                        this.handleImportLocalFile(message.filePath, message.currentProjectId);
                        break;
                    case 'uploadOntology':
                        // Handle ontology upload from webview (admin flow - load file from project)
                        console.log('[OntoCode] 📤 Upload ontology request received:', message.projectId, message.fileName);
                        this.handleUploadOntologyFromWebview(message.projectId, message.fileName, message.fileContent, message.ownerEmail);
                        break;
                    case 'uploadFileToProject':
                        // Handle file upload to MongoDB project (admin flow - save to project)
                        console.log('[OntoCode] 📤 Upload file to project request:', message.projectId, message.fileName);
                        this.handleUploadFileToProject(message.projectId, message.fileName, message.fileContent, message.fileSize);
                        break;
                    case 'showSubscriptionPlans':
                        // Navigate to subscription plans page
                        console.log('[OntoCode] 📋 Showing subscription plans');
                        this.postMessage({ type: 'showSubscriptionPlans' });
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
    private async handleApiRequest(message: Extract<ExtensionMessage, { type: 'apiGet' | 'apiPost' | 'apiPut' | 'apiPatch' | 'apiDelete' }>) {
        const { requestId, type, url } = message;
        
        // Check if this is a public endpoint that doesn't require authentication
        const isPublicEndpoint = 
            url.includes('/api/auth/login') || 
            url.includes('/api/auth/signup') ||
            url.includes('/api/invitations/details/') ||
            url.includes('/api/invitations/request-resend/');
        
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

            // Set a timeout for requests (300 seconds for large ontologies and workspace operations)
            const axiosConfig = { headers, timeout: 300_000 };

            switch (type) {
                case 'apiGet':
                    response = await axios.get(fullUrl, { ...axiosConfig, params: (message as any).params });
                    break;
                case 'apiPost':
                    response = await axios.post(fullUrl, (message as any).body, axiosConfig);
                    break;
                case 'apiPut':
                    response = await axios.put(fullUrl, (message as any).body, axiosConfig);
                    break;
                case 'apiPatch':
                    response = await axios.patch(fullUrl, (message as any).body, axiosConfig);
                    break;
                case 'apiPut':
                    response = await axios.put(fullUrl, message.body, axiosConfig);
                    break;
                case 'apiPatch':
                    response = await axios.patch(fullUrl, message.body, axiosConfig);
                    break;
                case 'apiDelete':
                    response = await axios.delete(fullUrl, { ...axiosConfig, params: (message as any).params });
                    break;
            }

            this.postMessage({ type: 'apiResponse', requestId, response: response.data });
            console.log(`[Proxy] ${type.replace('api', '').toUpperCase()} ${fullUrl} - Success (${response.status})`);
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

                const errorLogPayload = {
                    url: fullUrl,
                    method: type.replace('api', '').toUpperCase(),
                    status: axiosError.response?.status,
                    statusText: axiosError.response?.statusText,
                    responseHeaders: axiosError.response?.headers,
                    data: axiosError.response?.data,
                    message: axiosError.message,
                    code: (axiosError as any).code,
                    errno: (axiosError as any).errno,
                    cause: (axiosError as any).cause,
                    requestBody: type === 'apiPost' || type === 'apiPut' || type === 'apiPatch' ? message.body : undefined
                };
                console.error('[Proxy] API Request Error:', JSON.stringify(errorLogPayload, null, 2));
            } else if (e instanceof Error) {
                errorResponse = { message: e.message };
                console.error('[Proxy] API Request Error:', e.message);
            }
            this.postMessage({ type: 'apiResponse', requestId, error: errorResponse });
        }
    }

    private async handleProxyRequest(message: any) {
        const { reqId, config } = message;
        
        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        
        const headers = { ...config.headers };
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }

        try {
            const fullUrl = config.url.startsWith('http') ? config.url : `${GATEWAY_URL}${config.url}`;
            console.log(`[Proxy] ${config.method}: ${fullUrl}`);

            const response = await axios({
                method: config.method,
                url: fullUrl,
                data: config.data,
                headers
            });

            this.postMessage({
                type: 'proxyResponse',
                reqId,
                data: response.data
            });
        } catch (error: any) {
            console.error('[Proxy] Error:', error.message);
            this.postMessage({
                type: 'proxyResponse',
                reqId,
                error: {
                    message: error.message,
                    status: error.response?.status,
                    data: error.response?.data
                }
            });
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
        
        // Check if user is logged in
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        if (!token) {
            console.log('[OntoCode] Not logged in, uploading directly to GraphDB');
            const projectId = fileName.endsWith('.owl') ? fileName.slice(0, -4) : fileName;
            this._uploadOntology(projectId, fileName, fileData);
            return;
        }
        
        // Check if user has workspace selected
        const tokenData = parseJwtToken(token);
        console.log(tokenData,"token===========================================>")
        const hasWorkspace = tokenData?.workspaceId !== undefined && tokenData?.workspaceId !== null;
        const isAdmin = tokenData?.isAdmin === true;
        
        // Admin users or workspace users should get project selection
        if (isAdmin || hasWorkspace) {
            console.log(`[OntoCode] ${isAdmin ? 'Admin' : 'Workspace'} user detected, sending file for project selection`);
            // Convert to base64 for message passing (web-compatible)
            const base64Content = uint8ArrayToBase64(fileData);
            this.postMessage({
                type: 'pendingFileUpload',
                fileName: fileName,
                fileContent: base64Content,
                fileSize: fileData.length
            });
        } else {
            console.log('[OntoCode] Non-admin user without workspace, uploading directly to GraphDB');
            const projectId = fileName.endsWith('.owl') ? fileName.slice(0, -4) : fileName;
            this._uploadOntology(projectId, fileName, fileData);
        }
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

        // Fix: Use binary file reading instead of getText() to preserve encoding
        // getText() converts to JavaScript string which corrupts non-UTF8 bytes
        const fileData = await vscode.workspace.fs.readFile(targetEditor.document.uri);
        // Fix: Replaced path.basename with string manipulation on the URI path.
        const fileName = targetEditor.document.uri.path.substring(targetEditor.document.uri.path.lastIndexOf('/') + 1);
        const projectId = fileName.endsWith('.owl') ? fileName.slice(0, -4) : fileName;

        // Delegate to the shared upload logic
        this._uploadOntology(projectId, fileName, fileData);
    }

    /**
     * Private helper method to handle the actual upload logic.
     * Uploads ontology file to the gateway which routes to the OWL Editor service.
     */
    // Fix: Changed fileData parameter from 'fs.ReadStream | Buffer' to 'Uint8Array'.
    private async _uploadOntology(projectId: string, fileName: string, fileData: Uint8Array, action?: string): Promise<void> {
        console.log(`[OntoCode] Starting upload for project: ${projectId}, file: ${fileName}, action: ${action || 'none'}`);
        
        // 1. Check for authentication token
        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        if (!token) {
            console.error('[OntoCode] No authentication token found');
            vscode.window.showErrorMessage("You must be logged in to process an ontology.");
            this.postMessage({ type: 'showLogin' });
            return;
        }
        
        // 1.5 Extract user info from token for duplicate check and role detection
        let ownerEmail = '';
        let userRoles: string[] = [];
        let workspaceId = '';
        let userId = '';
        try {
            const tokenParts = token.split('.');
            if (tokenParts.length === 3) {
                const base64Payload = tokenParts[1];
                const base64 = base64Payload.replace(/-/g, '+').replace(/_/g, '/');
                const jsonPayload = atob(base64);
                const payload = JSON.parse(jsonPayload);
                ownerEmail = payload.email || '';
                userRoles = payload.roles || [];
                workspaceId = payload.workspaceId || '';
                userId = payload.userId || payload.id || '';
                console.log(`[OntoCode] User info - email: ${ownerEmail}, roles: ${userRoles.join(',')}, workspaceId: ${workspaceId}, userId: ${userId}`);
            }
        } catch (tokenError) {
            console.error('[OntoCode] Could not extract user info from token:', tokenError);
        }
        
        // 1.6 Check if user is ROLE_USER or has no workspace - use user-based storage
        const isRoleUser = userRoles.includes('ROLE_USER') && !userRoles.includes('ROLE_ADMIN');
        const hasNoWorkspace = !workspaceId || workspaceId === '';
        const useUserStorage = isRoleUser || hasNoWorkspace;
        
        if (useUserStorage) {
            console.log(`[OntoCode] 🔒 User-based storage detected - ROLE_USER: ${isRoleUser}, No Workspace: ${hasNoWorkspace}`);
            // For user-based storage, use user-specific projectId format: user_{userId}_{filename}
            if (userId) {
                projectId = `user_${userId}_${fileName.replace('.owl', '')}`;
                console.log(`[OntoCode] Using user-based projectId: ${projectId}`);
            }
        }
        
        // 2. Check for duplicate file if action not specified
        if (!action && ownerEmail) {
            console.log(`[OntoCode] Checking for duplicate file: ${fileName}`);
            try {
                const checkUrl = `${GATEWAY_URL}/api/ontology/check-duplicate?filename=${encodeURIComponent(fileName)}&ownerEmail=${encodeURIComponent(ownerEmail)}`;
                const checkResponse = await axios.get(checkUrl, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                
                if (checkResponse.data.isDuplicate) {
                    console.log(`[OntoCode] Duplicate file detected:`, checkResponse.data);
                    
                    // Show user choice dialog
                    const choice = await vscode.window.showWarningMessage(
                        `A file named "${fileName}" already exists.`,
                        {
                            modal: true,
                            detail: `Status: ${checkResponse.data.status || 'Unknown'}\nLast Updated: ${checkResponse.data.lastUpdated ? new Date(checkResponse.data.lastUpdated).toLocaleString() : 'Unknown'}\n\nWhat would you like to do?`
                        },
                        'Replace',
                        'Create Copy',
                        'Cancel'
                    );
                    
                    if (choice === 'Cancel' || !choice) {
                        console.log('[OntoCode] User cancelled upload');
                        return;
                    }
                    
                    // Retry upload with user's choice
                    const selectedAction = choice === 'Replace' ? 'replace' : 'create_copy';
                    console.log(`[OntoCode] 🔄 User chose: ${choice} -> Calling _uploadOntology with action: ${selectedAction}`);
                    return this._uploadOntology(projectId, fileName, fileData, selectedAction);
                }
            } catch (checkError: any) {
                console.error('[OntoCode] Duplicate check failed:', checkError);
                // Continue with upload if check fails
            }
        }

        // 3. Initialize WebSocket connection FIRST (before upload)
        // This ensures we receive the IMPORT_COMPLETED message
        console.log(`[OntoCode] Initializing WebSocket before upload...`);
        try {
            await this.initializeCollaborationForProject(projectId, token);
            console.log(`[OntoCode] ✅ WebSocket initialized, proceeding with upload`);
        } catch (error) {
            console.error('[OntoCode] Failed to initialize WebSocket:', error);
            // Continue with upload anyway - collaboration is optional
        }

        // 3. Let the webview know we're starting an upload
        console.log(`[OntoCode] 📢 Sending showLoading message to webview for project: ${projectId}`);
        const showLoadingResult = this.postMessage({ type: 'showLoading', projectId });
        console.log(`[OntoCode] 📢 showLoading message sent, result:`, showLoadingResult);

        try {
            // 4. Prepare the form data for multipart upload
            // Convert Uint8Array to Blob for web extension compatibility
            // Create a new Uint8Array with ArrayBuffer to ensure compatibility
            const buffer = new Uint8Array(fileData.buffer.byteLength);
            buffer.set(new Uint8Array(fileData.buffer));
            const fileBlob = new Blob([buffer], { type: 'application/rdf+xml' });
            // Create a File object from the Blob to preserve filename
            const file = new File([fileBlob], fileName, { type: 'application/rdf+xml' });
            const formData = new FormData();
            formData.append('file', file);
            
            // Add action parameter if specified (replace or create_copy)
            if (action) {
                formData.append('action', action);
                console.log(`[OntoCode] ✅ Added action parameter to FormData: ${action}`);
            } else {
                console.log(`[OntoCode] ⚠️ No action parameter specified, backend will check for duplicates`);
            }

            // Extract user email from JWT token
            if (ownerEmail) {
                formData.append('ownerEmail', ownerEmail);
                console.log(`[OntoCode] ✅ Adding owner email: ${ownerEmail}`);
            } else {
                // Fallback to extracting from token if not already extracted
                try {
                    const tokenParts = token.split('.');
                    console.log('[OntoCode] Token parts count:', tokenParts.length);
                    if (tokenParts.length === 3) {
                        // Use web-compatible base64 decoding
                        const base64Payload = tokenParts[1];
                        console.log('[OntoCode] Base64 payload (first 50 chars):', base64Payload.substring(0, 50));
                        
                        // JWT uses base64url encoding, convert to standard base64
                        const base64 = base64Payload.replace(/-/g, '+').replace(/_/g, '/');
                        const jsonPayload = atob(base64);
                        
                        console.log('[OntoCode] Decoded payload:', jsonPayload.substring(0, 100));
                        const payload = JSON.parse(jsonPayload);
                        console.log('[OntoCode] Parsed payload keys:', Object.keys(payload));
                        
                        if (payload.email) {
                            formData.append('ownerEmail', payload.email);
                            console.log(`[OntoCode] ✅ Adding owner email: ${payload.email}`);
                        } else {
                            console.warn('[OntoCode] ⚠️ No email in token payload. Available fields:', Object.keys(payload));
                        }
                    }
                } catch (tokenError) {
                    console.error('[OntoCode] ❌ Could not extract email from token:', tokenError);
                }
            }
            
            const headers = {
                'Authorization': `Bearer ${token}`,
                // Browser FormData sets its own Content-Type with boundary
            };

            // 4. Upload to gateway endpoint
            const uploadUrl = `${GATEWAY_URL}/api/ontology/upload/${projectId}`;
            console.log(`[OntoCode] Uploading to: ${uploadUrl}`);
            console.log(`[OntoCode] Upload parameters - fileName: ${fileName}, action: ${action || 'none'}, projectId: ${projectId}`);
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
                this._lastProjectId = projectId; // Remember this project

                // WebSocket already initialized before upload - will receive IMPORT_COMPLETED
                console.log(`[OntoCode] Upload successful, WebSocket listening for IMPORT_COMPLETED`);
                
                // Check if this was a file replacement or copy
                const isReplacement = response.data?.isReplacement || false;
                const finalFileName = response.data?.filename || fileName;
                
                // Build message with project info if user is admin (backend includes projectId, projectName, workspaceId for admins)
                let message: string;
                const uploadAction = isReplacement ? 'replaced' : (action === 'create_copy' ? 'created as copy' : 'uploaded');
                
                if (response.data?.projectId && response.data?.projectName) {
                    // Admin user - include project information
                    message = `Ontology "${finalFileName}" ${uploadAction} successfully to project "${response.data.projectName}" (ID: ${response.data.projectId}). Processing...`;
                    console.log(`[OntoCode] Admin upload - Project: ${response.data.projectName}, Workspace: ${response.data.workspaceId}`);
                } else {
                    // Regular user - basic message
                    message = `Ontology "${finalFileName}" ${uploadAction} successfully. Processing...`;
                }
                
                vscode.window.showInformationMessage(message);
                
                // If it's a replacement, the file might already be processed - set a fallback timeout
                if (isReplacement) {
                    console.log(`[OntoCode] File replacement detected, will check status after 3 seconds if no IMPORT_COMPLETED`);
                    setTimeout(async () => {
                        // Check if we already received IMPORT_COMPLETED (pendingImportProjectIdRef would be cleared)
                        // If not, check the project status and trigger fileReady if COMPLETED
                        try {
                            const statusUrl = `http://ec2-13-218-153-101.compute-1.amazonaws.com/api/ontology/status/${projectId}`;
                            // const statusUrl = `http://localhost:80/api/ontology/status/${projectId}`;
                            const statusResp = await axios.get(statusUrl, { headers });
                            console.log(`[OntoCode] Fallback status check for ${projectId}:`, statusResp.data);
                            
                            if (statusResp.data?.status === 'COMPLETED') {
                                console.log(`[OntoCode] File already completed, sending fileReady`);
                                this.postMessage({ type: 'fileReady', projectId });
                            }
                        } catch (err) {
                            console.error('[OntoCode] Failed to check fallback status:', err);
                        }
                    }, 3000);
                }
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
                    errorMessage = 'No response from server. Is the gateway running on port 80?';
                } else {
                    console.error('[OntoCode] Error setting up request:', error.message);
                    errorMessage = error.message;
                }
                
                if (error.code === 'ECONNREFUSED') {
                    errorMessage = 'Cannot connect to gateway on port 80. Please ensure the gateway is running.';
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
     * Handle ontology upload from webview (admin flow - loading file from project)
     * This receives base64 file content and uploads it to the ontology editor
     */
    private async handleUploadOntologyFromWebview(projectId: string, fileName: string, base64Content: string, ownerEmail?: string) {
        console.log(`[OntoCode] 📤 Handling webview upload for project: ${projectId}, file: ${fileName}`);
        
        try {
            // Convert base64 to Uint8Array (web-compatible)
            const fileData = base64ToUint8Array(base64Content);
            
            console.log(`[OntoCode] 📦 Converted base64 to binary, size: ${fileData.length} bytes`);
            
            // Delegate to the shared upload logic
            await this._uploadOntology(projectId, fileName, fileData);
            
        } catch (error: any) {
            console.error('[OntoCode] ❌ Failed to upload from webview:', error);
            vscode.window.showErrorMessage(`Failed to upload ${fileName}: ${error?.message || 'Unknown error'}`);
            this.postMessage({ type: 'loadingFailed', error: error?.message || 'Upload failed' });
        }
    }

    /**
     * Handle file upload to MongoDB project (admin flow - save file to project)
     * This receives base64 file content and uploads it to a project's file collection
     */
    private async handleUploadFileToProject(projectId: string, fileName: string, base64Content: string, fileSize: number) {
        console.log(`[OntoCode] 📤 Uploading file to project: ${projectId}, file: ${fileName}, size: ${(fileSize / (1024 * 1024)).toFixed(2)}MB`);
        
        try {
            // Get auth token
            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (!token) {
                throw new Error('Not authenticated');
            }

            // Step 1: Check if file already exists in project
            const checkUrl = `${GATEWAY_URL}/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(fileName)}`;
            console.log(`[OntoCode] Checking for duplicate file: ${checkUrl}`);
            
            let replaceFileId: string | null = null;
            let finalFileName = fileName;
            
            try {
                const checkResponse = await axios.get(checkUrl, {
                    headers: {
                        'Authorization': `Bearer ${token}`
                    }
                });
                
                if (checkResponse.data.exists) {
                    console.log(`[OntoCode] ⚠️ File "${fileName}" already exists in project`);
                    
                    // Show confirmation dialog with options
                    const choice = await vscode.window.showWarningMessage(
                        `A file named "${fileName}" already exists in this project.`,
                        {
                            modal: true,
                            detail: 'What would you like to do?'
                        },
                        'Replace',
                        'Create Copy',
                        'Cancel'
                    );
                    
                    if (choice === 'Replace') {
                        // User chose to replace - store the existing file ID
                        replaceFileId = checkResponse.data.existingFile.id;
                        console.log(`[OntoCode] User chose to replace file. Old file ID: ${replaceFileId}`);
                    } else if (choice === 'Create Copy') {
                        // User chose to create a copy - append suffix to filename
                        const namePart = fileName.substring(0, fileName.lastIndexOf('.'));
                        const extension = fileName.substring(fileName.lastIndexOf('.'));
                        let copyNumber = 2;
                        
                        // Find a unique filename
                        while (true) {
                            const testName = `${namePart} (${copyNumber})${extension}`;
                            const testCheckResponse = await axios.get(
                                `${GATEWAY_URL}/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(testName)}`,
                                { headers: { 'Authorization': `Bearer ${token}` } }
                            );
                            
                            if (!testCheckResponse.data.exists) {
                                finalFileName = testName;
                                break;
                            }
                            copyNumber++;
                        }
                        
                        console.log(`[OntoCode] User chose to create copy. New filename: ${finalFileName}`);
                    } else {
                        // User cancelled
                        console.log('[OntoCode] User cancelled upload');
                        return;
                    }
                }
            } catch (checkError: any) {
                // If check endpoint fails, continue with upload (backward compatibility)
                console.warn('[OntoCode] Failed to check for duplicate file:', checkError.message);
            }

            // Check if it's a large file (>10MB)
            const isLargeFile = fileSize > 10 * 1024 * 1024;
            
            if (isLargeFile) {
                vscode.window.showInformationMessage(`Processing large file: ${finalFileName} (${(fileSize / (1024 * 1024)).toFixed(2)}MB)...`);
            }

            // Upload to project files endpoint with progress tracking
            const uploadUrl = `${GATEWAY_URL}/api/projects/${projectId}/files`;
            console.log(`[OntoCode] Upload URL: ${uploadUrl}`);
            
            const response = await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: `Uploading ${finalFileName}`,
                cancellable: false
            }, async (progress) => {
                progress.report({ message: 'Preparing upload...', increment: 10 });
                
                const uploadPayload: any = {
                    fileName: finalFileName,
                    fileData: `data:application/rdf+xml;base64,${base64Content}`,
                    fileSize: fileSize,
                    fileType: 'owl'
                };
                
                // If replacing, include the replaceFileId
                if (replaceFileId) {
                    uploadPayload.replaceFileId = replaceFileId;
                }
                
                const uploadResponse = await axios.post(uploadUrl, uploadPayload, {
                    headers: {
                        'Authorization': `Bearer ${token}`,
                        'Content-Type': 'application/json'
                    },
                    timeout: 300000, // 5 minute timeout for large files
                    onUploadProgress: (progressEvent) => {
                        if (progressEvent.total) {
                            const percentCompleted = Math.round((progressEvent.loaded * 100) / progressEvent.total);
                            progress.report({ 
                                message: `${percentCompleted}% uploaded`,
                                increment: percentCompleted
                            });
                        }
                    }
                });
                
                return uploadResponse;
            });

            if (response.status === 200 || response.status === 201) {
                console.log('[OntoCode] ✅ File uploaded to project successfully');
                
                const actionTaken = replaceFileId ? 'replaced' : (finalFileName !== fileName ? 'uploaded as copy' : 'uploaded');
                
                if (isLargeFile) {
                    vscode.window.showInformationMessage(`Large file "${finalFileName}" ${actionTaken} successfully! Processing in background...`);
                } else {
                    vscode.window.showInformationMessage(`File "${finalFileName}" ${actionTaken} successfully`);
                }
                
                // Notify webview to refresh file list
                this.postMessage({ type: 'fileReady', projectId: projectId });
            } else {
                throw new Error(`Upload failed with status: ${response.status}`);
            }
        } catch (error: any) {
            console.error('[OntoCode] ❌ Failed to upload file to project:', error);
            
            let errorMessage = `Failed to upload ${fileName}`;
            if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
                errorMessage += ': Upload timeout. Please try again or check your connection.';
            } else if (error.response?.status === 413) {
                errorMessage += ': File too large for server (max 300MB).';
            } else if (error.message) {
                errorMessage += `: ${error.message}`;
            }
            
            vscode.window.showErrorMessage(errorMessage);
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
     * Handle importing a local file - uploads it to the system
     */
    private async handleImportLocalFile(filePath: string, currentProjectId: string) {
        try {
            console.log('[OntoCode] === IMPORT LOCAL FILE START ===');
            console.log('[OntoCode] Original file path:', filePath);
            console.log('[OntoCode] Current project ID:', currentProjectId);
            
            // Remove file:// protocol if present
            let normalizedPath = filePath.replace(/^file:\/\/\//, '').replace(/^file:\/\//, '');
            
            // Convert forward slashes to backslashes on Windows (web-compatible check)
            // Check if the path looks like a Windows path (starts with drive letter)
            const isWindowsPath = /^[a-zA-Z]:/.test(normalizedPath);
            if (isWindowsPath) {
                normalizedPath = normalizedPath.replace(/\//g, '\\');
            }
            
            console.log('[OntoCode] Normalized path:', normalizedPath);
            
            // Check if file exists
            const fileUri = vscode.Uri.file(normalizedPath);
            console.log('[OntoCode] File URI:', fileUri.toString());
            
            let fileExists = false;
            try {
                await (vscode.workspace as any).fs.stat(fileUri);
                fileExists = true;
                console.log('[OntoCode] ✅ File exists at path');
            } catch (e) {
                console.log('[OntoCode] ❌ File not found:', e);
                vscode.window.showErrorMessage(`Could not find file at: ${normalizedPath}`);
                return;
            }
            
            console.log('[OntoCode] Reading file content...');
            // Read file content
            const fileData = await (vscode.workspace as any).fs.readFile(fileUri);
            const fileName = normalizedPath.substring(normalizedPath.lastIndexOf('\\') + 1).substring(normalizedPath.lastIndexOf('/') + 1);
            console.log('[OntoCode] File name:', fileName);
            console.log('[OntoCode] File size:', fileData.length, 'bytes');
            
            // Get auth token
            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (!token) {
                console.log('[OntoCode] ❌ No auth token found');
                vscode.window.showErrorMessage('Not authenticated. Please log in first.');
                return;
            }
            console.log('[OntoCode] ✅ Auth token retrieved');
            
            // Generate a unique project ID for the imported file
            const uploadProjectId = `imported-${Date.now()}-${Math.random().toString(36).substring(7)}`;
            console.log('[OntoCode] Generated upload project ID:', uploadProjectId);
            
            // Upload file to the system using web-compatible FormData
            const formData = new FormData();
            // Create a Blob from the Uint8Array
            const fileBlob = new Blob([fileData], { type: 'application/rdf+xml' });
            const file = new File([fileBlob], fileName, { type: 'application/rdf+xml' });
            formData.append('file', file);
            
            const uploadUrl = `${GATEWAY_URL}/api/ontology/upload/${uploadProjectId}`;
            
            console.log('[OntoCode] Upload URL:', uploadUrl);
            console.log('[OntoCode] Uploading file...');
            
            const response = await axios.post(uploadUrl, formData, {
                headers: {
                    'Authorization': `Bearer ${token}`
                },
                maxContentLength: Infinity,
                maxBodyLength: Infinity,
                timeout: 300000
            });
            
            console.log('[OntoCode] Upload response status:', response.status);
            console.log('[OntoCode] Upload response data:', response.data);
            
            if (response.status === 200 || response.status === 201) {
                console.log('[OntoCode] ✅ Import file uploaded successfully');
                vscode.window.showInformationMessage(`Imported file "${fileName}" uploaded to your files.`);
                
                // Store this project ID
                this._lastProjectId = uploadProjectId;
                
                // Notify webview to refresh file list
                console.log('[OntoCode] Sending fileReady message to webview');
                this.postMessage({ type: 'fileReady', projectId: uploadProjectId });
                console.log('[OntoCode] === IMPORT LOCAL FILE END (SUCCESS) ===');
            } else {
                console.log('[OntoCode] ❌ Upload failed with status:', response.status);
                vscode.window.showErrorMessage(`Failed to upload file. Status: ${response.status}`);
            }
        } catch (error) {
            console.error('[OntoCode] === IMPORT LOCAL FILE ERROR ===');
            console.error('[OntoCode] Import local file error:', error);
            if (error instanceof Error) {
                console.error('[OntoCode] Error message:', error.message);
                console.error('[OntoCode] Error stack:', error.stack);
            }
            vscode.window.showErrorMessage('Failed to upload imported file. Check console for details.');
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
        const buildPath = vscode.Uri.joinPath(this._extensionUri, 'webview-src', 'dist');
        
        // Path to the index.html file
        const indexPath = vscode.Uri.joinPath(buildPath, 'index.html');

        // Get the base URI to use in the webview for resolving relative paths
        const baseUri = webview.asWebviewUri(buildPath).toString() + '/';
        
        // Read the template HTML
        let htmlContent: string;
        try {
            const fileBytes = await vscode.workspace.fs.readFile(indexPath);
            htmlContent = new TextDecoder('utf-8').decode(fileBytes);
        } catch (error) {
            console.error('[OntoCode] Failed to read index.html:', error);
            return `
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Error</title>
                </head>
                <body>
                    <h1>Failed to load webview</h1>
                    <p>Error: ${error}</p>
                    <p>Tried to load: ${indexPath.toString()}</p>
                </body>
                </html>
            `;
        }
        const nonce = getNonce();

        // The VSCode API script that needs to be injected
        const vscodeApiInjectionScript = `
            <script nonce="${nonce}">
                const vscode = acquireVsCodeApi();
                window.vscode = vscode;
                window.API_BASE_URL = '${GATEWAY_URL}';
                // Fallback for minified bundle expecting a global toggleNode
                if (typeof window.toggleNode !== 'function') {
                    window.toggleNode = () => {};
                }
            </script>
            <style>
                /* Fix: Ensure code blocks are selectable and clickable */
                pre, code, .hljs, [class*="code-"] {
                    user-select: text !important;
                    -webkit-user-select: text !important;
                    cursor: text !important;
                    pointer-events: auto !important;
                }
                
                /* Fix: Remove default margins from pre tags to fix extra spacing */
                pre {
                    margin: 0 !important;
                    padding: 0 !important;
                }
            </style>
        `;
        
        // Remove any existing CSP meta tags to avoid conflicts
        htmlContent = htmlContent.replace(/<meta[^>]*Content-Security-Policy[^>]*>/gi, '');
        
        // Inject our new CSP and the API script into the <head>
        // Fix: Removed <base> tag as it breaks in-page anchor navigation (e.g. search results jumping to lines)
        htmlContent = htmlContent.replace(
            /(<head>)/,
            `$1
            <meta http-equiv="Content-Security-Policy" content="
                default-src 'none'; 
                img-src ${webview.cspSource} https: data: blob:; 
                script-src 'nonce-${nonce}' https://cdn.tailwindcss.com https://unpkg.com https://aistudiocdn.com ${GATEWAY_URL} ${PLUGIN_SERVICE_URL} ${webview.cspSource};
                style-src ${webview.cspSource} 'unsafe-inline' https://unpkg.com https://cdn.tailwindcss.com;
                font-src ${webview.cspSource} https://unpkg.com data:; 
                connect-src ${GATEWAY_URL} ${PLUGIN_SERVICE_URL} https://unpkg.com https://aistudiocdn.com;
            ">
            ${vscodeApiInjectionScript}`
        );
        
        // Add nonce to inline scripts (importmap, etc.)
        htmlContent = htmlContent.replace(/<script type="importmap">/g, `<script type="importmap" nonce="${nonce}">`);
        
        // Add nonce to our main application script.
        // Add cache busting timestamp to force reload
        const cacheBuster = Date.now();
        htmlContent = htmlContent.replace(/(href|src)="([^"]+)"/g, (match, attr, rawPath) => {
            // Fix: Ignore hash links (anchors) so they work for in-page navigation
            if (rawPath.startsWith('https:') || rawPath.startsWith('http:') || rawPath.startsWith('data:') || rawPath.startsWith('#')) {
                return match; 
            }
            // Fix: Replace missing Uri.joinPath with proper Uri.joinPath method
            const resourcePath = vscode.Uri.joinPath(
                buildPath,
                rawPath.startsWith('/') ? rawPath.substring(1) : rawPath
            );
            // Fix: Use proper webview.asWebviewUri method
            const webviewUri = webview.asWebviewUri(resourcePath);
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
     * Check if webview is ready to receive messages.
     */
    public isWebviewReady(): boolean {
        return this._isWebviewReady;
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
            
            // Extract userId, username, and email from token
            const userId = tokenData.userId || tokenData.sub || 'unknown';
            const username = tokenData.username || tokenData.sub || 'User';
            const userEmail = tokenData.email || '';
            
            console.log(`[OntoCode] Extracted user info - userId: ${userId}, username: ${username}, email: ${userEmail}`);
            
            // Call the main initialization method
            await this.initializeCollaboration(projectId, userId, username, userEmail);
        } catch (error) {
            console.error('[OntoCode] Error initializing collaboration for project:', error);
            // Don't throw - collaboration is optional, file upload should still work
        }
    }

    private async initializeCollaboration(projectId: string, userId: string, username: string, userEmail?: string): Promise<void> {
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

                onImportStatusUpdate: (status) => {
                    console.log(`[OntoCode] 📨 Import status update - Type: ${status.type}, Project: ${status.projectId}, Status: ${status.status}`);
                    
                    // Log errors with more detail
                    if (status.type === 'IMPORT_FAILED') {
                        console.error(`[OntoCode] ❌ Import failed for ${status.projectId}:`, status.statusMessage || status.metadata?.error);
                    }

                    // Forward import status to webview
                    this.postMessage({
                        type: 'importStatusUpdate',
                        status
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
                    
                    // vscode.window.showInformationMessage(
                    //     connected ? 'Connected to collaborative editing' : 'Disconnected from collaborative editing'
                    // );
                },
                
                onError: (error) => {
                    console.error('[OntoCode] Collaboration error:', error);
                    vscode.window.showErrorMessage(`Collaboration error: ${error}`);
                },

                onShareNotification: (notification) => {
                    console.log('[OntoCode] 📨 Share notification received:', notification);
                    
                    // Show a notification to the user
                    vscode.window.showInformationMessage(
                        `${notification.sharedByUsername} shared "${notification.fileName}" with you (${notification.permission} access)`
                    );
                    
                    // Forward to webview so it can refresh the file list
                    this.postMessage({
                        type: 'shareNotification',
                        notification
                    });
                },

                onCursorUpdate: (cursor) => {
                    console.log('[OntoCode] 🖱️  Cursor update received:', cursor.userName);
                    
                    // Forward cursor position to webview
                    this.postMessage({
                        type: 'cursorUpdate',
                        userId: cursor.userId,
                        userName: cursor.userName,
                        position: cursor.position,
                        timestamp: cursor.timestamp
                    });
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
            
            // Subscribe to share notifications if user email is available
            if (userEmail) {
                console.log(`[OntoCode] Subscribing to share notifications for: ${userEmail}`);
                this.collaborationManager.subscribeToShareNotifications(userEmail);
            } else {
                console.warn('[OntoCode] No user email available, share notifications will not work');
            }
            
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
