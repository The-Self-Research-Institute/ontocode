// Fix: Removed triple-slash directive for node types as we are removing node-specific dependencies.

import * as vscode from 'vscode';
import axios, { AxiosError } from 'axios';
import * as path from 'path';
import { insertCitationCommand } from './features/citationInsertion';
import { CitationPickerPanel } from './webview/citationPicker';
import { sci2CodeService } from './services/sci2CodeService';
import { zoteroApiService } from './services/zoteroApiService';
import { issueReportService } from './services/issueReportService';
import { extractDoiFromZoteroData } from './utils/doi';
// Use web-compatible collaboration manager in browser environment
import { CollaborationManager } from './collaboration/CollaborationManager.web';
import { ICollaborationManager } from './collaboration/types';
import { EditCapture } from './collaboration/EditCapture';
import { RemoteEditApplier } from './collaboration/RemoteEditApplier';
import { shouldCompressFile, splitIntoChunks } from './utils/uploadOptimizer';

// This file is bundled for two different runtimes: the desktop extension host
// (real Node.js, full zlib support) and the web extension (browser web worker,
// no zlib). Automatic decompression must stay off only for the latter — leaving
// it off unconditionally corrupts every gzip/br response (Zotero, our own API)
// in the desktop build, since axios still advertises gzip/br support in its
// request headers regardless of this flag.
const isNodeRuntime = typeof process !== 'undefined' && !!(process.versions && process.versions.node);
axios.defaults.decompress = isNodeRuntime;

/**
 * Generates a chunked-upload session id. Uses the standard Web Crypto API (available as a
 * global in both the desktop extension host's Node runtime and the web extension's browser
 * worker) rather than Node's `crypto` module, since the latter isn't available in the web build.
 */
function generateUploadId(): string {
    const g = globalThis as any;
    if (g.crypto && typeof g.crypto.randomUUID === 'function') {
        return g.crypto.randomUUID();
    }
    // Fallback for older runtimes without crypto.randomUUID
    return `upload-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** SHA-256 hex digest of a chunk, via the Web Crypto API — works in both runtimes (see above). */
async function sha256Hex(data: Uint8Array): Promise<string> {
    const g = globalThis as any;
    const digest: ArrayBuffer = await g.crypto.subtle.digest('SHA-256', data);
    return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('');
}

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

function buildDefaultCopyName(fileName: string, copyIndex: number = 1): string {
    const dotIndex = fileName.lastIndexOf('.');
    if (dotIndex > 0) {
        const baseName = fileName.substring(0, dotIndex);
        const extension = fileName.substring(dotIndex);
        return `${baseName}-copy-${copyIndex}${extension}`;
    }
    return `${fileName}-copy-${copyIndex}`;
}

function normalizeCopyName(inputName: string, originalExtension: string): string {
    const trimmed = inputName.trim();
    if (!originalExtension) {
        return trimmed;
    }
    const lower = trimmed.toLowerCase();
    const extLower = originalExtension.toLowerCase();
    if (lower.endsWith(extLower)) {
        return trimmed;
    }
    return `${trimmed}${originalExtension}`;
}

function extractExtension(fileName: string): string {
    const dotIndex = fileName.lastIndexOf('.');
    return dotIndex > 0 ? fileName.substring(dotIndex) : '';
}

function isSupportedOntologyExtension(fileName: string): boolean {
    return /\.(owl|rdf|ttl|n3|nt|jsonld|zip)$/i.test(fileName);
}

function isSupportedNewOntologyExtension(fileName: string): boolean {
    return /\.(owl|rdf|ttl|n3|nt|jsonld)$/i.test(fileName);
}

const TOKEN_KEY = 'ontocode.authToken';
const DEPLOYMENT_TYPE_KEY = 'ontocode.deploymentType';

// Function to get URLs based on deployment type
function getUrlsForDeployment(deploymentType: 'self-hosted' | 'cloud'): { gateway: string; editor: string; plugin: string } {
    if (deploymentType === 'self-hosted') {
        return {
            gateway: process.env.SELF_HOSTED_GATEWAY_URL || 'http://localhost:80',
            editor: process.env.SELF_HOSTED_EDITOR_URL || 'http://localhost:8083',
            plugin: process.env.SELF_HOSTED_PLUGIN_URL || 'http://localhost:8087'
        };
    } else {
        return {
            gateway: process.env.CLOUD_GATEWAY_URL || 'https://ontocodeapi.selfresearch.org',
            editor: process.env.CLOUD_EDITOR_URL || 'https://ontocodeapi.selfresearch.org',
            plugin: process.env.CLOUD_PLUGIN_URL || 'https://ontocodeapi.selfresearch.org:8087'
        };
    }
}

// Default deployment type from environment or cloud
const defaultDeploymentType = (process.env.DEFAULT_DEPLOYMENT_TYPE || 'cloud') as 'self-hosted' | 'cloud';
const defaultUrls = getUrlsForDeployment(defaultDeploymentType);
console.log(defaultUrls, "default")
let GATEWAY_URL = defaultUrls.gateway;
let OWL_EDITOR_URL = defaultUrls.editor;
let PLUGIN_SERVICE_URL = defaultUrls.plugin;

// Update URLs based on stored deployment preference
async function updateDeploymentUrls(context: vscode.ExtensionContext) {
    try {
        const deploymentType = (await (context as any).secrets.get(DEPLOYMENT_TYPE_KEY)) as 'self-hosted' | 'cloud' | undefined;
        if (deploymentType) {
            const urls = getUrlsForDeployment(deploymentType);
            GATEWAY_URL = urls.gateway;
            OWL_EDITOR_URL = urls.editor;
            PLUGIN_SERVICE_URL = urls.plugin;

            // Update issue report service with new editor URL
            issueReportService.setEditorUrl(OWL_EDITOR_URL);

            console.log(`[OntoCode] Using ${deploymentType} deployment URLs:`, urls);
        } else {
            console.log('[OntoCode] No deployment type stored, using cloud (default)');
        }
    } catch (error) {
        console.error('[OntoCode] Error loading deployment type:', error);
    }
}

/**
 * Check if running in web extension context (vscode-web)
 */
function isWebExtensionContext(): boolean {
    return typeof process === 'undefined' || !process.versions || !process.versions.electron;
}

/**
 * Parse JWT token to extract user information
 * @param token JWT token string
 * @returns Decoded token payload or null if invalid
 */
function parseJwtToken(token: string): { userId?: string; username?: string; sub?: string; email?: string; isAdmin?: boolean; workspaceId?: string; subscriptionPlan?: string } | null {
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
    | { type: 'showLoading'; projectId: string; fileName?: string }
    | { type: 'fileReady'; projectId: string; uploadedFileId?: string; uploadedFileName?: string }
    | { type: 'openProjectFile'; projectId: string; fileId: string; fileName: string }
    | { type: 'loadingFailed'; error: string }
    | { type: 'importFailed'; projectId: string; error: string }
    | { type: 'importTimeout'; projectId: string }
    | { type: 'updateLoadingStatus'; projectId: string; message: string; estimatedMinutes?: number; attempt?: number; maxAttempts?: number }
    | { type: 'duplicateFilePrompt'; requestId: string; context: 'project' | 'ontology'; fileName: string; projectId?: string; ownerEmail?: string; defaultCopyName?: string; detail?: string; allowOpenExisting?: boolean; error?: string }
    // Fix: Added message type for API responses from the proxy
    | { type: 'apiResponse'; requestId: string; response?: any; error?: any }
    | { type: 'proxyResponse'; reqId: string; data?: any; error?: any }
    | { type: 'invitationToken'; token: string }
    | { type: 'clearInvitationState' }
    // Collaborative editing messages
    | { type: 'remoteEdit'; edit: any }
    | { type: 'presenceUpdate'; presence: any }
    | { type: 'lockUpdate'; lock: any }
    | { type: 'collaborationStatus'; connected: boolean }
    | { type: 'importStatusUpdate'; status: any }
    | { type: 'shareNotification'; notification: any }
    | { type: 'cursorUpdate'; userId: string; userName: string; position: { x: number; y: number }; timestamp: number }
    | { type: 'pendingFileUpload'; fileName: string; fileContent: string; fileSize: number; importMode?: string; partition?: string }
    | { type: 'uploadProgress'; projectId: string; percent: number; loaded: number; total: number; message: string }
    | { type: 'showSubscriptionPlans' }
    // Citation messages
    | { type: 'zoteroLibraryData'; items: any[]; hasMore?: boolean; totalResults?: number; loadedSoFar?: number; librarySessionId?: number }
    | { type: 'zoteroLibraryDataAppend'; items: any[]; hasMore?: boolean; totalResults?: number; loadedSoFar?: number; librarySessionId?: number }
    | { type: 'zoteroLibraryDataComplete'; librarySessionId?: number }
    | { type: 'zoteroLibraryError'; error: string; librarySessionId?: number }
    | { type: 'zoteroConfigData'; config: { apiKey: string; userId: string; libraryType: string; groupId?: string } | null }
    | { type: 'citationFormatted'; citation: string; metadata: any; projectId: string }
    | { type: 'uploadOntologyContentDone'; success: boolean; projectId: string } // Navigate to subscription plans page
    | { type: 'downloadOntologyComplete'; requestId?: number }
    | { type: 'downloadOntologyFailed'; requestId?: number; error?: string; cancelled?: boolean };

type ExtensionMessage =
    | { type: 'error'; value: string }
    | { type: 'saveAuthToken'; token: string }
    | { type: 'requestAuthToken' }
    | { type: 'logout' }
    | { type: 'openLocalFile'; projectId?: string | null; importMode?: string; partition?: string }
    | { type: 'createNewFile'; projectId?: string | null; importMode?: string; partition?: string }
    | { type: 'createNewFileWithName'; fileName: string; projectId?: string | null; importMode?: string; partition?: string }
    | { type: 'duplicateFilePromptResponse'; requestId: string; action: 'open_existing' | 'replace' | 'create_copy' | 'cancel'; copyName?: string }
    // Fix: Added message types for API requests to the proxy
    | { type: 'apiGet'; requestId: string; url: string; params?: Record<string, unknown> }
    | { type: 'apiPost'; requestId: string; url: string; body?: unknown; params?: Record<string, unknown> }
    | { type: 'apiPut'; requestId: string; url: string; body?: unknown; params?: Record<string, unknown> }
    | { type: 'apiPatch'; requestId: string; url: string; body?: unknown; params?: Record<string, unknown> }
    | { type: 'apiDelete'; requestId: string; url: string; params?: Record<string, unknown> }
    | { type: 'proxyRequest'; reqId: string; config: any }
    | { type: 'webviewReady' }
    | { type: 'downloadOntology'; url: string; filename: string; projectId: string; format: string; requestId?: number }
    | { type: 'downloadCurrentOntology' }
    | { type: 'downloadFile'; content: string; filename: string; format: string }
    | { type: 'openExternalUrl'; url: string } // Open a URL in the OS default browser (webview navigation is sandboxed)
    | { type: 'fileLoaded'; projectId: string } // File selected from menu
    | { type: 'requestCollaborationStatus' } // Request current collaboration status
    | { type: 'showNotification'; notification: { type: string; title: string; message: string; actions?: string[] } } // System notification
    | { type: 'cursorMoved'; nodeId: string; nodeName: string } // User moved cursor to a node
    | { type: 'broadcastCursor'; projectId: string; userId: string; userName: string; position: { x: number; y: number }; timestamp: number } // User cursor position
    | { type: 'importLocalFile'; filePath: string; currentProjectId: string } // Import local OWL file
    | { type: 'uploadOntology'; projectId: string; fileName: string; fileContent: string; ownerEmail?: string; workspaceId?: string; skipDuplicateCheck?: boolean; importMode?: string; partition?: string } // Upload ontology from webview (admin flow)
    | { type: 'uploadFileToProject'; projectId: string; fileName: string; fileContent: string; fileSize: number }
    | { type: 'showSubscriptionPlans' } // Request to show subscription plans page
    | { type: 'setApiBaseUrl'; url: string; deploymentType?: 'self-hosted' | 'cloud' }
    | { type: 'clearLastProjectState' }
    | { type: 'requestZoteroLibrary'; searchQuery?: string } // Zotero quick search (`q`), optional — empty = whole library
    | { type: 'requestZoteroLibraryMore' } // Request next Zotero page (infinite scroll)
    | { type: 'insertCitation'; citationKey: string; format: 'turtle' | 'rdfxml'; projectId: string; lineNumber?: number } // Insert citation from Zotero
    | { type: 'insertManualCitation'; citation: any; format: 'turtle' | 'rdfxml'; projectId: string; lineNumber?: number } // Insert manual citation
    | { type: 'insertCitationToGraphDB'; citation: string; format: string; projectId: string; metadata: any } // Insert citation directly to GraphDB
    | { type: 'removeCitationFromGraphDB'; citationUri: string; projectId: string } // Remove citation from GraphDB
    | { type: 'uploadOntologyContent'; content: string; format: string; projectId: string } // Upload modified ontology content
    | { type: 'requestZoteroConfig' } // Request Zotero credentials from VS Code workspace settings
    | { type: 'saveZoteroConfig'; config: { apiKey: string; userId: string; libraryType: 'user' | 'group'; groupId?: string } } // Save Zotero credentials to VS Code workspace settings
    | { type: 'clearZoteroConfig' }; // Clear Zotero credentials from VS Code workspace settings

type DuplicatePromptAction = 'open_existing' | 'replace' | 'create_copy' | 'cancel';
type DuplicatePromptResult = { action: DuplicatePromptAction; copyName?: string };


export async function activate(context: vscode.ExtensionContext) {
    console.log('OntoCode extension is now active!');
    console.log('[OntoCode] Extension can handle URIs like: vscode://self.ontocode-extension/invite?token=xxx');

    // Initialize issue report service with default URL
    issueReportService.setEditorUrl(OWL_EDITOR_URL);

    // Load deployment URLs on activation (must await to ensure correct URLs in webview HTML)
    await updateDeploymentUrls(context);
    console.log('[OntoCode] Deployment URLs loaded');

    // Check for invitation token in URL (for test-web environment)
    if (typeof window !== 'undefined' && window.location) {
        const urlParams = new URLSearchParams(window.location.search);
        const inviteToken = urlParams.get('token');

        if (inviteToken) {
            console.log('[OntoCode] Found invitation token in URL:', inviteToken.substring(0, 20) + '...');

            // Open OntoCode webview with invitation token
            setTimeout(async () => {
                vscode.window.showInformationMessage('Opening invitation in OntoCode...');
                const panel = await OntoCodePanel.createOrShow(context.extensionUri, context, false);

                // Store token as pending
                panel._pendingInvitationToken = inviteToken;

                // Send token when webview is ready
                if (panel.isWebviewReady()) {
                    panel.postMessage({ type: 'clearInvitationState' });
                    setTimeout(() => {
                        panel.postMessage({ type: 'invitationToken', token: inviteToken });
                        panel._pendingInvitationToken = null;
                    }, 100);
                }
            }, 500);
        }
    }

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

                    vscode.window.showInformationMessage('Opening invitation in OntoCode...');

                    // Open OntoCode webview with invitation token
                    const panel = await OntoCodePanel.createOrShow(context.extensionUri, context, false);

                    // Always store the token as pending first
                    panel._pendingInvitationToken = token;

                    // If webview is ready, send the token immediately
                    // Use a small delay to ensure the webview state is properly initialized
                    if (panel.isWebviewReady()) {
                        console.log('[OntoCode] Webview ready, sending token immediately');
                        // Send a clear existing state message first, then send the invitation token
                        panel.postMessage({ type: 'clearInvitationState' });
                        setTimeout(() => {
                            panel.postMessage({
                                type: 'invitationToken',
                                token: token
                            });
                            panel._pendingInvitationToken = null;
                        }, 100);
                    } else {
                        console.log('[OntoCode] Webview not ready, token stored for later delivery');
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

    // FIX: Register WebviewPanelSerializer to ensure the webview content is restored on reload
    if (vscode.window.registerWebviewPanelSerializer) {
        console.log('[OntoCode] Registering WebviewPanelSerializer for ontocodeEditor');
        vscode.window.registerWebviewPanelSerializer('ontocodeEditor', {
            async deserializeWebviewPanel(webviewPanel: vscode.WebviewPanel, state: any) {
                console.log('[OntoCode] Reviving webview panel from serialized state');

                // Reset the webview options to ensure correct localResourceRoots
                webviewPanel.webview.options = {
                    enableScripts: true,
                    localResourceRoots: [vscode.Uri.joinPath(context.extensionUri, 'webview-src', 'dist')]
                };

                await OntoCodePanel.revive(webviewPanel, context.extensionUri, context);
            }
        });
    }

    // Register all commands
    context.subscriptions.push(
        // Fix: Made command handler async to support async panel creation/file upload.
        vscode.commands.registerCommand('ontocode.edit', async () => {
            const isWeb = isWebExtensionContext();
            console.log(`[OntoCode] Edit command triggered (Web mode: ${isWeb})`);

            // Check if there's an active ontology file first
            const activeEditor = vscode.window.activeTextEditor;
            const fileName = activeEditor?.document.fileName.toLowerCase() || '';
            const hasActiveOntology = activeEditor && (fileName.endsWith('.owl') || fileName.endsWith('.ttl') || fileName.endsWith('.rdf'));

            // Fix: Use context.extensionUri to get the extension's URI.
            const panel = await OntoCodePanel.createOrShow(context.extensionUri, context, hasActiveOntology);

            if (hasActiveOntology) {
                // Upload the active ontology file
                panel.setPendingUpload(true);
            } else {
                // No active ontology file, show file picker
                console.log('[OntoCode] No active ontology file, prompting user to select one...');
                const fileUri = await vscode.window.showOpenDialog({
                    canSelectMany: false,
                    openLabel: 'Open Ontology File',
                    filters: {
                        'Ontology Files': ['owl', 'ttl', 'rdf', 'n3', 'nt', 'jsonld', 'zip'],
                        'All Files': ['*']
                    }
                });

                if (fileUri && fileUri[0]) {
                    const selectedUri = fileUri[0];
                    console.log('[OntoCode] User selected file:', selectedUri.toString());
                    console.log('[OntoCode] File URI scheme:', selectedUri.scheme);
                    panel.setPendingUpload(false, selectedUri);
                } else {
                    console.log('[OntoCode] User cancelled file selection');
                    // Only show message in desktop mode - web users understand file picker cancellation
                    if (!isWeb) {
                        vscode.window.showInformationMessage('Please select an ontology file or package (.owl, .ttl, .rdf, .zip) to edit.');
                    }
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
        vscode.commands.registerCommand('ontocode.insertCitation', () => insertCitationCommand(context, GATEWAY_URL)),
        // Fix: Use context.extensionUri to get the extension's URI.
        vscode.commands.registerCommand('ontocode.openCitationPicker', () => CitationPickerPanel.createOrShow(context.extensionUri)),
        vscode.commands.registerCommand('ontocode.configureZotero', async () => {
            await zoteroApiService.showConfigInstructions();
        }),
        vscode.commands.registerCommand('ontocode.testZoteroConnection', async () => {
            await zoteroApiService.testConnection();
        }),
        vscode.commands.registerCommand('ontocode.openWebview', async () => {
            await OntoCodePanel.createOrShow(context.extensionUri, context, false);
        }),
        vscode.commands.registerCommand('ontocode.testInvitationFlow', async () => {
            const token = await vscode.window.showInputBox({
                prompt: 'Enter invitation token to test',
                placeHolder: 'Paste invitation token here',
                ignoreFocusOut: true,
                validateInput: (value) => {
                    if (!value || value.trim().length === 0) {
                        return 'Token is required';
                    }
                    return null;
                }
            });

            if (token) {
                vscode.window.showInformationMessage('Opening invitation...');
                const panel = await OntoCodePanel.createOrShow(context.extensionUri, context, false);

                // Store token as pending
                panel._pendingInvitationToken = token;

                // Send token when webview is ready
                if (panel.isWebviewReady()) {
                    panel.postMessage({ type: 'clearInvitationState' });
                    setTimeout(() => {
                        panel.postMessage({ type: 'invitationToken', token: token });
                        panel._pendingInvitationToken = null;
                    }, 100);
                }
            }
        })
    );

    // Export API for other extensions or internal use (replaces external Sci2Code dependency).
    // No mock/demo fallback here — sci2CodeService.initialize() already confirms
    // Zotero is configured (via isAuthenticated below) and prompts to configure it
    // if not, before any of these are called. Silently substituting fake data on
    // a fetch error or empty library used to be indistinguishable from real data
    // in the citation picker, risking a fabricated citation landing in a real
    // ontology — see zoteroApiService.fetchLibrary for real error surfacing.
    return {
        getZoteroLibrary: async (): Promise<any[]> => {
            console.log('[OntoCode] getZoteroLibrary called');
            return await zoteroApiService.fetchLibrary(10000);
        },
        getZoteroItem: async (key: string): Promise<any | null> => {
            console.log('[OntoCode] getZoteroItem called for key:', key);
            if (!zoteroApiService.isConfigured()) return null;
            const item = await zoteroApiService.fetchItem(key);
            return item ? item.data : null;
        },
        formatCitationForOntology: async (key: string, format?: 'turtle' | 'rdfxml', overrideDoi?: string): Promise<string> => {
            console.log('[OntoCode] formatCitationForOntology called for key:', key, 'format:', format);

            // Fetch the real item from Zotero — no mock fallback (see comment above).
            let data: { title: string; creators: Array<{ firstName: string; lastName: string }>; date?: string; DOI?: string; doi?: string; extra?: string; url?: string } | undefined;
            if (zoteroApiService.isConfigured()) {
                const realItem = await zoteroApiService.fetchItem(key);
                if (realItem) data = realItem.data;
            }
            if (!data) return '';

            const escapeTurtle = (s: string) => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
            const escapeXml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
            const escapedKey = key.replace(/[^a-zA-Z0-9]/g, '');
            const authors = data.creators?.map(c => `${c.firstName} ${c.lastName}`).join(', ') || 'Unknown';
            const year = data.date ? (data.date.match(/\d{4}/)?.[0] || '') : '';
            // overrideDoi wins when the caller already validated/corrected it
            // (citationInsertion.ts's DOI validation step) — otherwise fall back to
            // whatever Zotero field actually has it (uppercase DOI, lowercase doi,
            // or embedded in the free-text `extra` field/url), not just data.DOI.
            const resolvedDoi = overrideDoi || extractDoiFromZoteroData(data);

            // Same prov:/dc:/foaf:/rdfs: shape ensurePrefixes() in citationInsertion.ts
            // provisions in the document — the previous bibo:-based template declared
            // a prefix ensurePrefixes never inserts.
            if (format === 'rdfxml') {
                let xml = `<?xml version="1.0"?>\n`;
                xml += `<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"\n`;
                xml += `         xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"\n`;
                xml += `         xmlns:owl="http://www.w3.org/2002/07/owl#"\n`;
                xml += `         xmlns:dc="http://purl.org/dc/elements/1.1/"\n`;
                xml += `         xmlns:foaf="http://xmlns.com/foaf/0.1/"\n`;
                xml += `         xmlns:prov="http://www.w3.org/ns/prov#"\n`;
                xml += `         xmlns:xsd="http://www.w3.org/2001/XMLSchema#">\n\n`;
                xml += `    <!-- Zotero Citation: ${escapeXml(data.title)} -->\n`;
                xml += `    <owl:NamedIndividual rdf:about="urn:citation:${escapedKey}">\n`;
                xml += `        <rdf:type rdf:resource="http://www.w3.org/ns/prov#Entity"/>\n`;
                xml += `        <dc:title>${escapeXml(data.title)}</dc:title>\n`;
                xml += `        <dc:creator>${escapeXml(authors)}</dc:creator>\n`;
                if (year) xml += `        <dc:date rdf:datatype="http://www.w3.org/2001/XMLSchema#gYear">${year}</dc:date>\n`;
                if (resolvedDoi) xml += `        <dc:identifier>doi:${escapeXml(resolvedDoi)}</dc:identifier>\n`;
                if (data.url) xml += `        <foaf:homepage rdf:resource="${escapeXml(data.url)}"/>\n`;
                xml += `        <rdfs:comment>Zotero citation</rdfs:comment>\n`;
                xml += `    </owl:NamedIndividual>\n`;
                xml += `</rdf:RDF>`;
                return xml;
            }

            let ttl = `@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n`;
            ttl += `@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n`;
            ttl += `@prefix owl: <http://www.w3.org/2002/07/owl#> .\n`;
            ttl += `@prefix dc: <http://purl.org/dc/elements/1.1/> .\n`;
            ttl += `@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n`;
            ttl += `@prefix prov: <http://www.w3.org/ns/prov#> .\n`;
            ttl += `@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n`;
            ttl += `###  Zotero Citation: ${data.title}\n`;
            ttl += `<urn:citation:${escapedKey}> rdf:type owl:NamedIndividual ,\n`;
            ttl += `         prov:Entity ;\n`;
            ttl += `    dc:title "${escapeTurtle(data.title)}" ;\n`;
            ttl += `    dc:creator "${escapeTurtle(authors)}" ;\n`;
            if (year) ttl += `    dc:date "${year}"^^xsd:gYear ;\n`;
            if (resolvedDoi) ttl += `    dc:identifier "doi:${escapeTurtle(resolvedDoi)}" ;\n`;
            if (data.url) ttl += `    foaf:homepage <${data.url}> ;\n`;
            ttl += `    rdfs:comment "Zotero citation" .\n`;
            return ttl;
        },
        getCitationMetadata: async (key: string): Promise<any | null> => {
            console.log('[OntoCode] getCitationMetadata called for key:', key);

            if (!zoteroApiService.isConfigured()) return null;
            const item = await zoteroApiService.fetchItem(key);
            return item ? item.data : null;
        },
        isAuthenticated: async (): Promise<boolean> => {
            // "Authenticated" here means "Zotero is configured" — this used to
            // check OntoCode's own login token, which is unrelated and meant a
            // logged-out-of-OntoCode user (or a logged-in one with no Zotero key
            // set up) got a confusing "log in to Zotero" prompt that called a
            // VS Code command (sci2code.login) which doesn't exist anywhere.
            return zoteroApiService.isConfigured();
        }
    };
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
    private _pendingImportOptions: { importMode?: string; partition?: string } | null = null;
    private _isPendingRegularUpload: boolean = false;
    private _pendingAuthUpload: { projectId: string; fileName: string; fileData: Uint8Array; importMode?: string; partition?: string } | null = null;
    private _lastProjectId: string | null = null; // Track last opened project
    public _pendingInvitationToken: string | null = null; // Track pending invitation token
    private _pendingDuplicatePrompts = new Map<string, { resolve: (result: DuplicatePromptResult | null) => void; timeout: ReturnType<typeof setTimeout> }>();

    // Zotero infinite-scroll paging state (per webview session)
    private _zoteroPaging: {
        start: number;
        totalResults: number;
        pageSize: number;
        loading: boolean;
        done: boolean;
        searchQuery?: string;
        sessionId: number;
    } | null = null;
    /** Monotonic session id per library/search request — webview ignores stale paging events */
    private _zoteroLibrarySessionSeq = 0;

    // Collaborative editing
    private collaborationManager: ICollaborationManager | null = null;
    private editCapture: EditCapture;
    private remoteEditApplier: RemoteEditApplier;
    private currentProjectId: string | null = null;

    private async getStoredDeploymentType(): Promise<'self-hosted' | 'cloud' | null> {
        try {
            const deploymentType = await (this._context as any).secrets.get(DEPLOYMENT_TYPE_KEY);
            if (deploymentType === 'self-hosted' || deploymentType === 'cloud') {
                return deploymentType;
            }
        } catch (error) {
            console.warn('[OntoCode] Failed to read deployment type from secrets:', error);
        }
        return null;
    }

    private async shouldUseWorkspaceFlow(tokenData?: { workspaceId?: string | null; isAdmin?: boolean }): Promise<boolean> {
        const deploymentType = await this.getStoredDeploymentType();
        console.log(tokenData, "token");
        if (deploymentType === 'self-hosted') {
            return false;
        }
        if (deploymentType === 'cloud') {
            return true;
        }

        const hasWorkspace = tokenData?.workspaceId !== undefined && tokenData?.workspaceId !== null && tokenData?.workspaceId !== '';
        const isAdmin = tokenData?.isAdmin === true;
        return isAdmin;
    }

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
                localResourceRoots: [vscode.Uri.joinPath(extensionUri, 'webview-src', 'dist')]
            }
        );

        OntoCodePanel.currentPanel = new OntoCodePanel(panel, extensionUri, context);
        // Fix: Awaited the update of the webview content after panel creation.
        await OntoCodePanel.currentPanel._update();
        return OntoCodePanel.currentPanel!;
    }

    // FIX: Revive method for WebviewPanelSerializer to restore panel on reload
    public static async revive(panel: vscode.WebviewPanel, extensionUri: vscode.Uri, context: vscode.ExtensionContext) {
        OntoCodePanel.currentPanel = new OntoCodePanel(panel, extensionUri, context);
        await OntoCodePanel.currentPanel._update();
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
                    case 'clearLastProjectState':
                        this._lastProjectId = null;
                        break;
                    case 'saveAuthToken':
                        if (message.token) {
                            // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
                            await (this._context as any).secrets.store(TOKEN_KEY, message.token);
                            vscode.window.showInformationMessage('Authentication successful.');
                            await this.resumePendingAuthUpload();
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
                        this._pendingAuthUpload = null;
                        this.postMessage({ type: 'loggedOut' });
                        break;
                    case 'openLocalFile':
                        await this.handleOpenLocalFile(message.projectId || null, message.importMode, message.partition);
                        break;
                    case 'createNewFile':
                        await this.handleCreateNewFile(message.projectId || null, message.importMode, message.partition);
                        break;
                    case 'createNewFileWithName':
                        await this.handleCreateNewFileWithName(message.fileName, message.projectId || null, message.importMode, message.partition);
                        break;
                    case 'duplicateFilePromptResponse':
                        this.handleDuplicatePromptResponse(message);
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
                        this.handleDownload(message.projectId, message.format, message.filename, message.requestId);
                        break;
                    case 'downloadCurrentOntology':
                        this.handleDownloadCurrent();
                        break;
                    case 'downloadFile':
                        this.handleDownloadFile(message.content, message.filename);
                        break;
                    case 'openExternalUrl':
                        // VS Code webviews are sandboxed iframes — a programmatic
                        // window.location.href / window.open to an external URL is silently
                        // blocked. The webview must postMessage here instead so the extension
                        // host can hand the URL to the OS's default browser.
                        if (message.url) {
                            vscode.env.openExternal(vscode.Uri.parse(message.url));
                        }
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
                        this.handleUploadOntologyFromWebview(
                            message.projectId,
                            message.fileName,
                            message.fileContent,
                            message.ownerEmail,
                            message.skipDuplicateCheck,
                            message.importMode,
                            message.partition
                        );
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
                    case 'setApiBaseUrl':
                        // Handle deployment type selection and update URLs
                        console.log('[OntoCode] 🔧 Setting API base URL:', message.url);
                        const deploymentType = (message as any).deploymentType ||
                            (message.url.includes('localhost') ? 'self-hosted' : 'cloud');

                        // Update URLs immediately (synchronously)
                        const urls = getUrlsForDeployment(deploymentType);
                        GATEWAY_URL = urls.gateway;
                        OWL_EDITOR_URL = urls.editor;
                        PLUGIN_SERVICE_URL = urls.plugin;
                        console.log('[OntoCode] ✅ URLs updated immediately for', deploymentType, ':', urls);
                        console.log('[OntoCode] 📍 GATEWAY_URL is now:', GATEWAY_URL);

                        // Save to secrets for persistence (asynchronously)
                        (this._context as any).secrets.store(DEPLOYMENT_TYPE_KEY, deploymentType).then(() => {
                            console.log('[OntoCode] ✅ Deployment type saved to secrets:', deploymentType);
                        }).catch((err: any) => {
                            console.error('[OntoCode] ❌ Failed to save deployment type:', err);
                        });
                        break;
                    case 'requestZoteroLibrary': {
                        const raw = (message as { searchQuery?: string }).searchQuery?.trim();
                        await this.handleRequestZoteroLibrary(raw || undefined);
                        break;
                    }
                    case 'requestZoteroLibraryMore':
                        // Load the next page of Zotero citations (infinite scroll)
                        await this.handleRequestZoteroLibraryMore();
                        break;
                    case 'requestZoteroConfig': {
                        const cfg = zoteroApiService.getPublicConfig();
                        this.postMessage({ type: 'zoteroConfigData', config: cfg });
                        break;
                    }
                    case 'saveZoteroConfig': {
                        const { config: zCfg } = message as { config: { apiKey: string; userId: string; libraryType: 'user' | 'group'; groupId?: string } };
                        await zoteroApiService.saveConfig(zCfg);
                        break;
                    }
                    case 'clearZoteroConfig': {
                        await zoteroApiService.clearConfig();
                        break;
                    }
                    case 'insertCitation':
                        // Handle citation insertion from Zotero
                        await this.handleInsertCitation(message.citationKey, message.format, message.projectId, message.lineNumber || 0);
                        break;
                    case 'insertManualCitation':
                        // Handle manual citation insertion
                        await this.handleInsertManualCitation(message.citation, message.format, message.projectId, message.lineNumber || 0);
                        break;
                    case 'insertCitationToGraphDB':
                        // Handle direct citation insertion to GraphDB (for persistence across format changes)
                        await this.handleInsertCitationToGraphDB(message.citation, message.format, message.projectId, message.metadata);
                        break;
                    case 'removeCitationFromGraphDB':
                        // Handle citation removal from GraphDB
                        await this.handleRemoveCitationFromGraphDB(message.citationUri, message.projectId);
                        break;
                    case 'uploadOntologyContent':
                        // Handle uploading modified ontology content
                        await this.handleUploadOntologyContent(message.content, message.format, message.projectId);
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
            const options = this._pendingImportOptions;
            this._pendingFileUri = null;
            this._pendingImportOptions = null;
            console.log('[OntoCode] Webview is ready, triggering pending large file upload.');
            this.triggerLargeFileUpload(uri, options?.importMode, options?.partition);
        }
    }

    // FIX: New method to set a pending upload from the activate function
    public setPendingUpload(isRegular: boolean, uri: vscode.Uri | null = null, importMode?: string, partition?: string) {
        if (isRegular) {
            this._isPendingRegularUpload = true;
        } else if (uri) {
            this._pendingFileUri = uri;
        }

        if (importMode || partition) {
            this._pendingImportOptions = { importMode, partition };
        }

        // If webview is *already* ready (e.g., panel was just revealed), trigger now.
        if (this._isWebviewReady) {
            this.triggerPendingUpload();
        }
    }

    private async requestDuplicatePrompt(payload: {
        context: 'project' | 'ontology';
        fileName: string;
        projectId?: string;
        ownerEmail?: string;
        defaultCopyName?: string;
        detail?: string;
        allowOpenExisting?: boolean;
        error?: string;
    }): Promise<DuplicatePromptResult | null> {
        if (!this._isWebviewReady) {
            return null;
        }

        const requestId = `dup-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        return new Promise((resolve) => {
            const timeout = setTimeout(() => {
                this._pendingDuplicatePrompts.delete(requestId);
                resolve(null);
            }, 300_000);

            this._pendingDuplicatePrompts.set(requestId, { resolve, timeout });
            this.postMessage({
                type: 'duplicateFilePrompt',
                requestId,
                ...payload
            });
        });
    }

    private handleDuplicatePromptResponse(message: { requestId: string; action: DuplicatePromptAction; copyName?: string }) {
        const pending = this._pendingDuplicatePrompts.get(message.requestId);
        if (!pending) {
            return;
        }
        clearTimeout(pending.timeout);
        this._pendingDuplicatePrompts.delete(message.requestId);
        pending.resolve({ action: message.action, copyName: message.copyName });
    }

    private async resumePendingAuthUpload() {
        if (!this._pendingAuthUpload) {
            return;
        }

        // Clear first to avoid loops if anything below triggers another auth prompt
        const pending = this._pendingAuthUpload;
        this._pendingAuthUpload = null;

        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        if (!token) {
            // Still not authenticated; keep it cleared to avoid repeated retries
            return;
        }

        const tokenData = parseJwtToken(token);
        const useWorkspaceFlow = await this.shouldUseWorkspaceFlow(tokenData || undefined);

        if (useWorkspaceFlow) {
            console.log('[OntoCode] Workspace flow detected, sending pending file for project selection.');
            const base64Content = uint8ArrayToBase64(pending.fileData);
            this.postMessage({
                type: 'pendingFileUpload',
                fileName: pending.fileName,
                fileContent: base64Content,
                fileSize: pending.fileData.length,
                importMode: pending.importMode,
                partition: pending.partition
            });
        } else {
            console.log('[OntoCode] Auth restored, uploading pending file directly.');
            await this._uploadOntology(
                pending.projectId,
                pending.fileName,
                pending.fileData,
                undefined,
                undefined,
                undefined,
                pending.importMode,
                pending.partition
            );
        }
    }

    private async handleOpenLocalFile(projectId?: string | null, importMode?: string, partition?: string) {
        const isWeb = isWebExtensionContext();
        console.log(`[OntoCode] Opening file dialog... (Web mode: ${isWeb})`);

        const fileUri = await vscode.window.showOpenDialog({
            canSelectMany: false,
            openLabel: 'Open Ontology File',
            filters: {
                'Ontology Files': ['owl', 'rdf', 'ttl', 'n3', 'nt', 'jsonld', 'zip'],
                'All Files': ['*']
            }
        });

        if (fileUri && fileUri[0]) {
            const selectedUri = fileUri[0];
            console.log('[OntoCode] User selected local file from webview:', selectedUri.toString());
            console.log('[OntoCode] File URI scheme:', selectedUri.scheme);
            if (!projectId) {
                this.setPendingUpload(false, selectedUri, importMode, partition);
                return;
            }

            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (!token) {
                vscode.window.showErrorMessage('You must be logged in to open a project file.');
                this.postMessage({ type: 'showLogin' });
                return;
            }

            const perfStart = Date.now();
            console.log(`[OntoCode] [PERF] ⏱️ Starting file open pipeline at ${new Date().toISOString()}`);

            const readStart = Date.now();
            const fileData = await (vscode.workspace as any).fs.readFile(selectedUri);
            console.log(`[OntoCode] [PERF] File read from disk: ${Date.now() - readStart}ms (${(fileData.length / (1024 * 1024)).toFixed(2)}MB)`);

            const fileName = selectedUri.path.substring(selectedUri.path.lastIndexOf('/') + 1);
            const fileSize = fileData.length;

            const base64Start = Date.now();
            const base64Content = uint8ArrayToBase64(fileData);
            console.log(`[OntoCode] [PERF] Base64 encoding: ${Date.now() - base64Start}ms (base64 size: ${(base64Content.length / (1024 * 1024)).toFixed(2)}MB)`);

            // Check deployment type
            const deploymentType = await this.getStoredDeploymentType();
            const isCloudDeployment = deploymentType === 'cloud';

            let existingFileId: string | null = null;
            let existingFileName: string | null = null;
            let skipDuplicateCheck = false;

            // For cloud deployment: skip duplicate check entirely
            if (isCloudDeployment) {
                console.log('[OntoCode] ☁️ Cloud deployment: skipping duplicate check');
                skipDuplicateCheck = true;
            } else {
                // For self-hosted: check for duplicates
                try {
                    const checkUrl = `${GATEWAY_URL}/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(fileName)}`;
                    const checkResponse = await axios.get(checkUrl, {
                        headers: { 'Authorization': `Bearer ${token}` }
                    });

                    if (checkResponse.data?.exists) {
                        const existing = checkResponse.data.existingFile || {};
                        existingFileId = existing.fileId || existing.id || null;
                        existingFileName = existing.fileName || fileName;

                        // Self-hosted: show dialog
                        const defaultCopyName = buildDefaultCopyName(fileName, 1);
                        const duplicateDecision = await this.requestDuplicatePrompt({
                            context: 'project',
                            fileName,
                            projectId,
                            defaultCopyName,
                            allowOpenExisting: true,
                            detail: 'Do you want to overwrite it, create a copy, or open the existing file?'
                        });

                        if (!duplicateDecision) {
                            const choice = await vscode.window.showWarningMessage(
                                `A file named "${fileName}" already exists in this project.`,
                                { modal: true, detail: 'Do you want to overwrite it, create a copy, or open the existing file?' },
                                'Overwrite',
                                'Create Copy',
                                'Open Existing',
                                'Cancel'
                            );

                            if (choice === 'Open Existing') {
                                if (existingFileId) {
                                    this.postMessage({
                                        type: 'openProjectFile',
                                        projectId,
                                        fileId: existingFileId,
                                        fileName: existingFileName || fileName
                                    });
                                } else {
                                    vscode.window.showErrorMessage('Could not locate the existing file to open.');
                                }
                                return;
                            }

                            if (choice === 'Create Copy') {
                                const originalExt = extractExtension(fileName);
                                let copyIndex = 1;
                                while (true) {
                                    const defaultName = buildDefaultCopyName(fileName, copyIndex);
                                    const copyInput = await vscode.window.showInputBox({
                                        title: 'Create Copy',
                                        prompt: 'Enter a name for the copy',
                                        value: defaultName,
                                        ignoreFocusOut: true,
                                        validateInput: (value) => {
                                            const trimmed = value.trim();
                                            if (!trimmed) return 'Name is required.';
                                            const normalized = normalizeCopyName(trimmed, originalExt);
                                            if (originalExt && !normalized.toLowerCase().endsWith(originalExt.toLowerCase())) {
                                                return `Name must end with ${originalExt}`;
                                            }
                                            if (!isSupportedOntologyExtension(normalized)) {
                                                return 'Unsupported file type.';
                                            }
                                            return null;
                                        }
                                    });

                                    if (!copyInput) {
                                        return;
                                    }

                                    const candidateName = normalizeCopyName(copyInput, originalExt);
                                    try {
                                        const dupCheck = await axios.get(
                                            `${GATEWAY_URL}/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(candidateName)}`,
                                            { headers: { 'Authorization': `Bearer ${token}` } }
                                        );
                                        if (dupCheck.data?.exists) {
                                            vscode.window.showWarningMessage(`"${candidateName}" already exists. Please choose a different name.`);
                                            copyIndex++;
                                            continue;
                                        }
                                    } catch (dupError) {
                                        console.warn('[OntoCode] Copy name duplicate check failed:', dupError);
                                    }

                                    await this.handleUploadFileToProject(projectId, candidateName, base64Content, fileSize, {
                                        skipDuplicateCheck: true,
                                        openAfterUpload: true
                                    });
                                    return;
                                }
                            }

                            if (choice !== 'Overwrite') {
                                return;
                            }
                            skipDuplicateCheck = true;
                        } else {
                            const action = duplicateDecision.action;
                            if (action === 'open_existing') {
                                if (existingFileId) {
                                    this.postMessage({
                                        type: 'openProjectFile',
                                        projectId,
                                        fileId: existingFileId,
                                        fileName: existingFileName || fileName
                                    });
                                } else {
                                    vscode.window.showErrorMessage('Could not locate the existing file to open.');
                                }
                                return;
                            }

                            if (action === 'create_copy') {
                                const originalExt = extractExtension(fileName);
                                const candidateName = normalizeCopyName(duplicateDecision.copyName || defaultCopyName, originalExt);
                                if (!isSupportedOntologyExtension(candidateName)) {
                                    vscode.window.showErrorMessage('Unsupported file type for copy.');
                                    return;
                                }
                                await this.handleUploadFileToProject(projectId, candidateName, base64Content, fileSize, {
                                    skipDuplicateCheck: true,
                                    openAfterUpload: true
                                });
                                return;
                            }

                            if (action !== 'replace') {
                                return;
                            }
                            skipDuplicateCheck = true;
                        }
                    } else {
                        skipDuplicateCheck = true;
                    }
                } catch (checkError: any) {
                    console.warn('[OntoCode] Failed to check for duplicate file:', checkError?.message || checkError);
                }
            }

            const uploadResult = await this.handleUploadFileToProject(projectId, fileName, base64Content, fileSize, {
                skipDuplicateCheck,
                replaceFileId: existingFileId,
                openAfterUpload: true
            });

            console.log(`[OntoCode] [PERF] ⏱️ Total file open pipeline: ${Date.now() - perfStart}ms`);

            if (!uploadResult) {
                return;
            }
        } else {
            console.log('[OntoCode] User cancelled local file selection from webview');
            // Don't show intrusive message when user cancels - they know what they did
        }
    }

    private async handleCreateNewFileWithName(fileName: string, projectId?: string | null, importMode?: string, partition?: string): Promise<void> {
        const isWeb = isWebExtensionContext();
        console.log(`[OntoCode] 📝 Creating new file with name: ${fileName} (Web mode: ${isWeb}, Project ID: ${projectId || 'none'})`);

        // Validate filename
        if (!fileName || !isSupportedNewOntologyExtension(fileName)) {
            console.error('[OntoCode] ❌ Invalid filename provided:', fileName);
            vscode.window.showErrorMessage('Invalid ontology filename. Must have a valid extension (.owl, .rdf, .ttl, .n3, .nt, .jsonld)');
            return;
        }

        console.log(`[OntoCode] ✅ File name validated: ${fileName}`);

        // Create minimal empty ontology content with owl:Thing as starting class
        const ontologyIRI = `http://example.org/ontologies/${fileName.replace(/\.[^/.]+$/, '')}`;
        console.log(`[OntoCode] 🔗 Generated ontology IRI: ${ontologyIRI}`);

        const emptyOntologyContent = `<?xml version="1.0"?>
<rdf:RDF xmlns="${ontologyIRI}#"
     xml:base="${ontologyIRI}"
     xmlns:owl="http://www.w3.org/2002/07/owl#"
     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
     xmlns:xml="http://www.w3.org/XML/1998/namespace"
     xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
    <owl:Ontology rdf:about="${ontologyIRI}"/>
    
    <!-- Classes -->
    <owl:Class rdf:about="http://www.w3.org/2002/07/owl#Thing"/>
</rdf:RDF>`;

        const fileContent = Buffer.from(emptyOntologyContent, 'utf-8');
        const fileSize = fileContent.length;
        const base64Content = uint8ArrayToBase64(fileContent);
        console.log(`[OntoCode] 📄 Created ontology content (${fileSize} bytes)`);

        // Handle non-workspace mode (no project ID)
        if (!projectId) {
            console.log('[OntoCode] 💾 No project context - saving to local filesystem');
            const saveUri = await vscode.window.showSaveDialog({
                defaultUri: vscode.Uri.file(fileName),
                saveLabel: 'Save New Ontology File',
                filters: {
                    'Ontology Files': ['owl', 'rdf', 'ttl', 'n3', 'nt', 'jsonld'],
                    'All Files': ['*']
                }
            });

            if (saveUri) {
                await vscode.workspace.fs.writeFile(saveUri, fileContent);
                console.log('[OntoCode] ✅ New file saved to:', saveUri.toString());
                this.setPendingUpload(false, saveUri, importMode, partition);
                vscode.window.showInformationMessage(`File "${fileName}" created successfully!`);
            } else {
                console.log('[OntoCode] ❌ User cancelled save dialog');
            }
            return;
        }

        // Handle workspace mode - upload to project
        console.log(`[OntoCode] 📤 Workspace mode - uploading to project: ${projectId}`);

        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        if (!token) {
            console.error('[OntoCode] ❌ No authentication token found');
            vscode.window.showErrorMessage('You must be logged in to create a project file.');
            this.postMessage({ type: 'showLogin' });
            return;
        }

        // Check for duplicate files in self-hosted deployments
        const deploymentType = await this.getStoredDeploymentType();
        const isCloudDeployment = deploymentType === 'cloud';
        console.log(`[OntoCode] 🔍 Deployment type: ${deploymentType}`);

        if (!isCloudDeployment) {
            // For self-hosted: check for duplicates and auto-rename if needed
            try {
                const checkUrl = `${GATEWAY_URL}/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(fileName)}`;
                console.log(`[OntoCode] 🔍 Checking for duplicates: ${checkUrl}`);

                const checkResponse = await axios.get(checkUrl, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });

                if (checkResponse.data?.exists) {
                    console.log(`[OntoCode] ⚠️ Duplicate file detected: ${fileName}`);

                    // Auto-generate a unique name by adding timestamp
                    const timestamp = new Date().getTime();
                    const baseName = fileName.replace(/\.[^/.]+$/, '');
                    const extension = fileName.substring(fileName.lastIndexOf('.'));
                    const newFileName = `${baseName}-${timestamp}${extension}`;

                    console.log(`[OntoCode] 🔄 Auto-renaming to avoid duplicate: ${newFileName}`);
                    // Recursively call with new name
                    return this.handleCreateNewFileWithName(newFileName, projectId, importMode, partition);
                }

                console.log('[OntoCode] ✅ No duplicate found - proceeding with upload');
            } catch (checkError: any) {
                console.warn('[OntoCode] ⚠️ Failed to check for duplicate file:', checkError?.message || checkError);
                // Continue with upload if check fails
            }
        } else {
            console.log('[OntoCode] ☁️ Cloud deployment - skipping duplicate check');
        }

        // Upload the new file to the project
        console.log(`[OntoCode] 📤 Uploading new file to project...`);
        const uploadResult = await this.handleUploadFileToProject(projectId, fileName, base64Content, fileSize, {
            skipDuplicateCheck: isCloudDeployment,
            openAfterUpload: true
        });

        if (uploadResult) {
            console.log(`[OntoCode] ✅ New file "${fileName}" created and uploaded successfully!`);
            vscode.window.showInformationMessage(`New file "${fileName}" created successfully!`);
        } else {
            console.error(`[OntoCode] ❌ Failed to upload new file "${fileName}"`);
        }
    }

    private async handleCreateNewFile(projectId?: string | null, importMode?: string, partition?: string): Promise<void> {
        const isWeb = isWebExtensionContext();
        console.log(`[OntoCode] 📝 Starting new file creation flow... (Web mode: ${isWeb}, Project ID: ${projectId || 'none'})`);

        // Step 1: Prompt user for file name with validation
        const fileName = await vscode.window.showInputBox({
            prompt: 'Enter a name for the new ontology file',
            placeHolder: 'my-ontology.owl',
            validateInput: (value) => {
                const trimmed = value.trim();
                if (!trimmed) return 'File name is required.';
                if (!isSupportedNewOntologyExtension(trimmed)) {
                    return 'File must have a valid ontology extension (.owl, .rdf, .ttl, .n3, .nt, .jsonld)';
                }
                return null;
            }
        });

        if (!fileName) {
            console.log('[OntoCode] ❌ User cancelled new file creation');
            return;
        }

        console.log(`[OntoCode] ✅ File name validated: ${fileName}`);

        // Step 2: Create minimal empty ontology content with owl:Thing as starting class
        const ontologyIRI = `http://example.org/ontologies/${fileName.replace(/\.[^/.]+$/, '')}`;
        console.log(`[OntoCode] 🔗 Generated ontology IRI: ${ontologyIRI}`);

        const emptyOntologyContent = `<?xml version="1.0"?>
<rdf:RDF xmlns="${ontologyIRI}#"
     xml:base="${ontologyIRI}"
     xmlns:owl="http://www.w3.org/2002/07/owl#"
     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
     xmlns:xml="http://www.w3.org/XML/1998/namespace"
     xmlns:xsd="http://www.w3.org/2001/XMLSchema#"
     xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
    <owl:Ontology rdf:about="${ontologyIRI}"/>
    
    <!-- Classes -->
    <owl:Class rdf:about="http://www.w3.org/2002/07/owl#Thing"/>
</rdf:RDF>`;

        const fileContent = Buffer.from(emptyOntologyContent, 'utf-8');
        const fileSize = fileContent.length;
        const base64Content = uint8ArrayToBase64(fileContent);
        console.log(`[OntoCode] 📄 Created ontology content (${fileSize} bytes)`);

        // Step 3: Handle non-workspace mode (no project ID)
        if (!projectId) {
            console.log('[OntoCode] 💾 No project context - saving to local filesystem');
            const saveUri = await vscode.window.showSaveDialog({
                defaultUri: vscode.Uri.file(fileName),
                saveLabel: 'Save New Ontology File',
                filters: {
                    'Ontology Files': ['owl', 'rdf', 'ttl', 'n3', 'nt', 'jsonld'],
                    'All Files': ['*']
                }
            });

            if (saveUri) {
                await vscode.workspace.fs.writeFile(saveUri, fileContent);
                console.log('[OntoCode] ✅ New file saved to:', saveUri.toString());
                this.setPendingUpload(false, saveUri, importMode, partition);
                vscode.window.showInformationMessage(`File "${fileName}" created successfully!`);
            } else {
                console.log('[OntoCode] ❌ User cancelled save dialog');
            }
            return;
        }

        // Step 4: Handle workspace mode - upload to project
        console.log(`[OntoCode] 📤 Workspace mode - uploading to project: ${projectId}`);

        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        if (!token) {
            console.error('[OntoCode] ❌ No authentication token found');
            vscode.window.showErrorMessage('You must be logged in to create a project file.');
            this.postMessage({ type: 'showLogin' });
            return;
        }

        // Step 5: Check for duplicate files in self-hosted deployments
        const deploymentType = await this.getStoredDeploymentType();
        const isCloudDeployment = deploymentType === 'cloud';
        console.log(`[OntoCode] 🔍 Deployment type: ${deploymentType}`);

        if (!isCloudDeployment) {
            // For self-hosted: check for duplicates and give user options
            try {
                const checkUrl = `${GATEWAY_URL}/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(fileName)}`;
                console.log(`[OntoCode] 🔍 Checking for duplicates: ${checkUrl}`);

                const checkResponse = await axios.get(checkUrl, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });

                if (checkResponse.data?.exists) {
                    console.log(`[OntoCode] ⚠️ Duplicate file detected: ${fileName}`);

                    const choice = await vscode.window.showWarningMessage(
                        `A file named "${fileName}" already exists in this project.`,
                        { modal: true },
                        'Choose Different Name',
                        'Cancel'
                    );

                    if (choice !== 'Choose Different Name') {
                        console.log('[OntoCode] ❌ User chose to cancel');
                        return;
                    }

                    console.log('[OntoCode] 🔄 User chose to pick different name - restarting flow');
                    // Recursively call to prompt for a new name
                    return this.handleCreateNewFile(projectId, importMode, partition);
                }

                console.log('[OntoCode] ✅ No duplicate found - proceeding with upload');
            } catch (checkError: any) {
                console.warn('[OntoCode] ⚠️ Failed to check for duplicate file:', checkError?.message || checkError);
                // Continue with upload if check fails
            }
        } else {
            console.log('[OntoCode] ☁️ Cloud deployment - skipping duplicate check');
        }

        // Step 6: Upload the new file to the project
        console.log(`[OntoCode] 📤 Uploading new file to project...`);
        const uploadResult = await this.handleUploadFileToProject(projectId, fileName, base64Content, fileSize, {
            skipDuplicateCheck: isCloudDeployment,
            openAfterUpload: true
        });

        if (uploadResult) {
            console.log(`[OntoCode] ✅ New file "${fileName}" created and uploaded successfully!`);
            vscode.window.showInformationMessage(`New file "${fileName}" created successfully!`);
        } else {
            console.error(`[OntoCode] ❌ Failed to upload new file "${fileName}"`);
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
            url.includes('/api/auth/verify') ||
            url.includes('/api/auth/resend-verification') ||
            url.includes('/api/auth/forgot-password') ||
            url.includes('/api/auth/reset-password') ||
            url.includes('/api/invitations/details/') ||
            url.includes('/api/invitations/request-resend/');

        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);

        // Do not proceed if unauthenticated, unless it's a login/signup endpoint
        if (!token && !isPublicEndpoint) {
            console.log('[Proxy] Request to', url, 'requires authentication');
            this.postMessage({ type: 'apiResponse', requestId, error: { message: 'User is not authenticated.', status: 401 } });
            return;
        }

        const headers: any = {
            'Content-Type': 'application/json',
            // Force uncompressed responses. When the API is fronted by a CDN (e.g. Cloudflare
            // on the dev/cloud host) it gzip/brotli-compresses responses; the compressed body
            // was reaching the webview undecoded (binary garbage → "No token received from
            // server"). This proxy is server-to-server, so skipping compression is negligible
            // and removes any dependency on the bundled axios version decoding it.
            'Accept-Encoding': 'identity'
        };

        // Only add Authorization header if we have a token
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }

        try {
            let response;
            const fullUrl = `${GATEWAY_URL}${url}`;
            console.log(`[Proxy] ${type.replace('api', '').toUpperCase()}: ${fullUrl}`, isPublicEndpoint ? '(public)' : '(authenticated)');

            // Set a timeout for requests (10 minutes default; 2 hours for ontology uploads up to 1GB)
            const requestTimeoutMs = url.includes('/api/ontology/upload/') || /\/api\/projects\/[^/]+\/files/.test(url)
                ? 7_200_000
                : 600_000;
            const axiosConfig: any = { headers, timeout: requestTimeoutMs };

            const extractUploadProjectId = (requestUrl: string): string | undefined => {
                const uploadMatch = requestUrl.match(/\/api\/ontology\/upload\/([^/?]+)/);
                if (uploadMatch) return decodeURIComponent(uploadMatch[1]);
                const filesMatch = requestUrl.match(/\/api\/projects\/([^/]+)\/files/);
                if (filesMatch) return decodeURIComponent(filesMatch[1]);
                return undefined;
            };

            const isUploadUrl = url.includes('/api/ontology/upload/') || /\/api\/projects\/[^/]+\/files/.test(url);
            const uploadProjectId = isUploadUrl ? extractUploadProjectId(url) : undefined;
            if (uploadProjectId && type === 'apiPost') {
                axiosConfig.onUploadProgress = (progressEvent: any) => {
                    const percentCompleted = progressEvent.total
                        ? Math.round((progressEvent.loaded * 100) / progressEvent.total)
                        : 0;
                    const statusMsg = percentCompleted >= 100
                        ? 'Upload complete. Processing on server...'
                        : `Uploading: ${percentCompleted}%`;
                    this.postMessage({
                        type: 'uploadProgress',
                        projectId: uploadProjectId,
                        percent: percentCompleted,
                        loaded: progressEvent.loaded,
                        total: progressEvent.total ?? 0,
                        message: statusMsg,
                    });
                };
            }

            // Detect multipart upload requests reconstructed from webview FormData
            const body = (message as any).body;
            let postBody = body;
            if (type === 'apiPost' && body && body._isMultipart) {
                const FormData = require('form-data');
                const form = new FormData();
                // Reconstruct the file from the transferred byte array
                if ((body._fileBase64 || body._fileBuffer) && body._fileFieldName) {
                    const buf = body._fileBase64
                        ? Buffer.from(body._fileBase64, 'base64')
                        : Buffer.from(body._fileBuffer);
                    console.log(`[Proxy] Reconstructing FormData: file=${body._originalFileName}, size=${buf.length}, type=${body.fileType}, encoding=${body._fileBase64 ? 'base64' : 'array'}`);
                    form.append(body._fileFieldName, buf, {
                        filename: body._originalFileName || 'upload',
                        contentType: body.fileType || 'application/octet-stream',
                    });
                }
                // Append remaining string fields
                for (const [k, v] of Object.entries(body)) {
                    if (k.startsWith('_') || k === 'file' || k === 'fileType') continue;
                    form.append(k, String(v));
                }
                postBody = form;
                // Override headers for multipart
                const formHeaders = form.getHeaders();
                console.log(`[Proxy] FormData headers:`, formHeaders);
                axiosConfig.headers = { ...headers, ...formHeaders };
                axiosConfig.maxContentLength = Infinity;
                axiosConfig.maxBodyLength = Infinity;
            }

            switch (type) {
                case 'apiGet':
                    response = await axios.get(fullUrl, { ...axiosConfig, params: (message as any).params });
                    break;
                case 'apiPost':
                    response = await axios.post(fullUrl, postBody, { ...axiosConfig, params: (message as any).params });
                    break;
                case 'apiPut':
                    response = await axios.put(fullUrl, (message as any).body, { ...axiosConfig, params: (message as any).params });
                    break;
                case 'apiPatch':
                    response = await axios.patch(fullUrl, (message as any).body, { ...axiosConfig, params: (message as any).params });
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
                console.error('[Proxy] Detailed Error:', {
                    code: axiosError.code,
                    message: axiosError.message,
                    stack: axiosError.stack,
                    isAxiosError: axios.isAxiosError(e)
                });
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
     * Works in both desktop VS Code and vscode-web.
     */
    // Fix: Refactored to use async vscode.workspace.fs.readFile instead of node 'fs'.
    public async triggerLargeFileUpload(fileUri: vscode.Uri, importMode?: string, partition?: string) {
        console.log(`[OntoCode] Triggering large file upload for: ${fileUri.toString()}`);
        console.log(`[OntoCode] URI scheme: ${fileUri.scheme}, path: ${fileUri.path}`);
        const fullPath = fileUri.path;
        const fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
        // Fix: Cast workspace to `any` to access the `fs` property, bypassing outdated type definitions.
        const fileData = await (vscode.workspace as any).fs.readFile(fileUri);

        // Check if user is logged in
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        if (!token) {
            console.log('[OntoCode] Not logged in, uploading directly to GraphDB');
            const ext = path.extname(fileName);
            const projectId = ['.owl', '.ttl', '.rdf'].includes(ext.toLowerCase()) ? fileName.slice(0, -ext.length) : fileName;
            this._uploadOntology(projectId, fileName, fileData, undefined, undefined, undefined, importMode, partition);
            return;
        }

        // Check if user has workspace selected
        const tokenData = parseJwtToken(token);
        console.log(tokenData, "token===========================================>")
        const useWorkspaceFlow = await this.shouldUseWorkspaceFlow(tokenData || undefined);

        // Admin users or workspace users should get project selection (cloud deployment always uses workspace flow)
        if (useWorkspaceFlow) {
            console.log('[OntoCode] Workspace flow detected, sending file for project selection');
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
            const ext = path.extname(fileName);
            const projectId = ['.owl', '.ttl', '.rdf'].includes(ext.toLowerCase()) ? fileName.slice(0, -ext.length) : fileName;
            this._uploadOntology(projectId, fileName, fileData, undefined, undefined, undefined, importMode, partition);
        }
    }

    // Helper to wait for webview to be ready
    private waitForWebviewReady(timeout: number): Promise<void> {
        return new Promise((resolve) => {
            if (this._isWebviewReady) {
                resolve();
                return;
            }

            const startTime = Date.now();
            const checkInterval = setInterval(() => {
                if (this._isWebviewReady || Date.now() - startTime > timeout) {
                    clearInterval(checkInterval);
                    console.log(`[OntoCode] Webview ready check complete. isReady: ${this._isWebviewReady}`);
                    resolve();
                }
            }, 100);
        });
    }

    /**
     * Handles uploading the content of the currently active editor.
     */
    public async triggerFileUpload() {
        console.log('[OntoCode] Triggering active editor file upload...');
        const targetEditor = this.findBestOwlEditor();

        if (!targetEditor) {
            vscode.window.showWarningMessage("No active ontology file (.owl, .ttl, or .rdf) found. Please open an ontology file and try again.");
            return;
        }

        await vscode.window.showTextDocument(targetEditor.document, targetEditor.viewColumn);

        // Fix: Use binary file reading instead of getText() to preserve encoding
        // getText() converts to JavaScript string which corrupts non-UTF8 bytes
        const fileData = await vscode.workspace.fs.readFile(targetEditor.document.uri);
        // Fix: Replaced path.basename with string manipulation on the URI path.
        const fileName = targetEditor.document.uri.path.substring(targetEditor.document.uri.path.lastIndexOf('/') + 1);

        // Check if user is logged in
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        if (!token) {
            console.log('[OntoCode] Not logged in, uploading directly to GraphDB');
            const ext = path.extname(fileName);
            const projectId = ['.owl', '.ttl', '.rdf'].includes(ext.toLowerCase()) ? fileName.slice(0, -ext.length) : fileName;
            this._uploadOntology(projectId, fileName, fileData);
            return;
        }

        // Always send file to webview for proper initialization (both cloud and self-hosted)
        console.log('[OntoCode] Sending active file to webview for upload');
        const base64Content = uint8ArrayToBase64(fileData);
        this.postMessage({
            type: 'pendingFileUpload',
            fileName: fileName,
            fileContent: base64Content,
            fileSize: fileData.length
        });
    }

    /**
     * Private helper method to handle the actual upload logic.
     * Uploads ontology file to the gateway which routes to the OWL Editor service.
     */
    // Fix: Changed fileData parameter from 'fs.ReadStream | Buffer' to 'Uint8Array'.
    private async _uploadOntology(
        projectId: string,
        fileName: string,
        fileData: Uint8Array,
        action?: string,
        ownerEmailOverride?: string,
        skipDuplicateCheck?: boolean,
        importMode?: string,
        partition?: string,
        preserveProjectId?: boolean
    ): Promise<void> {
        console.log(`[OntoCode] Starting upload for project: ${projectId}, file: ${fileName}, action: ${action || 'none'}`);

        // 1. Check for authentication token
        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        if (!token) {
            console.error('[OntoCode] No authentication token found');
            // Preserve upload to resume after authentication
            this._pendingAuthUpload = { projectId, fileName, fileData, importMode, partition };
            vscode.window.showErrorMessage("You must be logged in to process an ontology. Please log in to continue.");
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

        // 1.6 Check deployment type
        const deploymentType = await this.getStoredDeploymentType();
        const isCloudDeployment = deploymentType === 'cloud';

        // Use filename (without extension) as projectId unless explicitly preserved
        // (e.g., admin flow passes a stable ID from the webview)
        if (!preserveProjectId) {
            projectId = fileName.replace(/\.(owl|rdf|ttl|n3|nt|jsonld|zip)$/i, '');
        }
        console.log(`[OntoCode] Using projectId: ${projectId} (preserved: ${!!preserveProjectId})`);

        const resolvedOwnerEmail = ownerEmailOverride || ownerEmail;
        const authHeaders = { 'Authorization': `Bearer ${token}` };
        let duplicateCheckResult: 'duplicate' | 'unique' | 'failed' | 'skipped' = 'skipped';

        // 2. For cloud deployment: skip duplicate checking, proceed directly with upload
        if (isCloudDeployment) {
            console.log(`[OntoCode] ☁️ Cloud deployment: skipping duplicate check, proceeding with upload`);
            duplicateCheckResult = 'skipped';
        }

        // 3. For self-hosted: Check for duplicate file if action not specified
        // Skip for workspace files (hierarchical IDs with --) since they are project-scoped
        const isWorkspaceFile = projectId.includes('--');
        if (!isCloudDeployment && !action && !skipDuplicateCheck && !isWorkspaceFile && resolvedOwnerEmail) {
            console.log(`[OntoCode] Checking for duplicate file: ${fileName}`);
            try {
                const checkUrl = `${GATEWAY_URL}/api/ontology/check-duplicate?filename=${encodeURIComponent(fileName)}&ownerEmail=${encodeURIComponent(resolvedOwnerEmail)}`;
                const checkResponse = await axios.get(checkUrl, { headers: authHeaders });

                if (checkResponse.data.isDuplicate) {
                    duplicateCheckResult = 'duplicate';
                    console.log(`[OntoCode] Duplicate file detected:`, checkResponse.data);

                    const detail = `Status: ${checkResponse.data.status || 'Unknown'}\nLast Updated: ${checkResponse.data.lastUpdated ? new Date(checkResponse.data.lastUpdated).toLocaleString() : 'Unknown'}\n\nWhat would you like to do?`;
                    const defaultCopyName = buildDefaultCopyName(fileName, 1);
                    const duplicateDecision = await this.requestDuplicatePrompt({
                        context: 'ontology',
                        fileName,
                        ownerEmail: resolvedOwnerEmail,
                        defaultCopyName,
                        detail,
                        allowOpenExisting: true
                    });

                    if (!duplicateDecision) {
                        // Show user choice dialog (fallback)
                        const choice = await vscode.window.showWarningMessage(
                            `A file named "${fileName}" already exists.`,
                            {
                                modal: true,
                                detail
                            },
                            'Open Existing',
                            'Replace',
                            'Create Copy',
                            'Cancel'
                        );

                        if (choice === 'Open Existing') {
                            const existingProjectId = checkResponse.data.projectId;
                            if (existingProjectId) {
                                console.log('[OntoCode] Opening existing project:', existingProjectId);
                                this._lastProjectId = existingProjectId;
                                try {
                                    await this.initializeCollaborationForProject(existingProjectId, token);
                                } catch (error) {
                                    console.warn('[OntoCode] Failed to initialize collaboration for existing project:', error);
                                }
                                if (this._isWebviewReady) {
                                    // If the graph was empty and reimport was triggered, show loading state
                                    if (checkResponse.data.graphEmpty && checkResponse.data.reloadTriggered) {
                                        console.log('[OntoCode] Existing project graph was empty, reimport triggered. Showing loading state.');
                                        this.postMessage({ type: 'showLoading', projectId: existingProjectId, fileName });
                                    }
                                    this.postMessage({ type: 'fileReady', projectId: existingProjectId });
                                }
                            } else {
                                vscode.window.showErrorMessage('Could not determine existing project to open.');
                            }
                            return;
                        }

                        if (choice === 'Cancel' || !choice) {
                            console.log('[OntoCode] User cancelled upload');
                            return;
                        }

                        if (choice === 'Create Copy') {
                            const originalExt = extractExtension(fileName);
                            let copyIndex = 1;
                            while (true) {
                                const defaultName = buildDefaultCopyName(fileName, copyIndex);
                                const copyInput = await vscode.window.showInputBox({
                                    title: 'Create Copy',
                                    prompt: 'Enter a name for the copy',
                                    value: defaultName,
                                    ignoreFocusOut: true,
                                    validateInput: (value) => {
                                        const trimmed = value.trim();
                                        if (!trimmed) return 'Name is required.';
                                        const normalized = normalizeCopyName(trimmed, originalExt);
                                        if (originalExt && !normalized.toLowerCase().endsWith(originalExt.toLowerCase())) {
                                            return `Name must end with ${originalExt}`;
                                        }
                                        if (!isSupportedOntologyExtension(normalized)) {
                                            return 'Unsupported file type.';
                                        }
                                        return null;
                                    }
                                });

                                if (!copyInput) {
                                    return;
                                }

                                const candidateName = normalizeCopyName(copyInput, originalExt);
                                try {
                                    const dupCheck = await axios.get(
                                        `${GATEWAY_URL}/api/ontology/check-duplicate?filename=${encodeURIComponent(candidateName)}&ownerEmail=${encodeURIComponent(resolvedOwnerEmail)}`,
                                        { headers: authHeaders }
                                    );
                                    if (dupCheck.data?.isDuplicate) {
                                        vscode.window.showWarningMessage(`"${candidateName}" already exists. Please choose a different name.`);
                                        copyIndex++;
                                        continue;
                                    }
                                } catch (dupError) {
                                    console.warn('[OntoCode] Copy name duplicate check failed:', dupError);
                                }

                                const candidateBase = normalizeCopyName(candidateName, '').replace(/\.[^/.]+$/, '');
                                const copyProjectId = candidateBase;
                                console.log(`[OntoCode] Creating copy with filename: ${candidateName}`);
                                return this._uploadOntology(copyProjectId, candidateName, fileData, 'create_copy', resolvedOwnerEmail, undefined, importMode, partition);
                            }
                        }

                        // Retry upload with user's choice
                        if (choice === 'Replace') {
                            console.log(`[OntoCode] 🔄 User chose: ${choice} -> Calling _uploadOntology with action: replace`);
                            return this._uploadOntology(projectId, fileName, fileData, 'replace', resolvedOwnerEmail, undefined, importMode, partition);
                        }
                        return;
                    }

                    const action = duplicateDecision.action;
                    if (action === 'open_existing') {
                        const existingProjectId = checkResponse.data.projectId;
                        if (existingProjectId) {
                            console.log('[OntoCode] Opening existing project:', existingProjectId);
                            this._lastProjectId = existingProjectId;
                            try {
                                await this.initializeCollaborationForProject(existingProjectId, token);
                            } catch (error) {
                                console.warn('[OntoCode] Failed to initialize collaboration for existing project:', error);
                            }
                            if (this._isWebviewReady) {
                                // If the graph was empty and reimport was triggered, show loading state
                                if (checkResponse.data.graphEmpty && checkResponse.data.reloadTriggered) {
                                    console.log('[OntoCode] Existing project graph was empty, reimport triggered. Showing loading state.');
                                    this.postMessage({ type: 'showLoading', projectId: existingProjectId, fileName });
                                }
                                this.postMessage({ type: 'fileReady', projectId: existingProjectId });
                            }
                        } else {
                            vscode.window.showErrorMessage('Could not determine existing project to open.');
                        }
                        return;
                    }

                    if (action === 'cancel') {
                        console.log('[OntoCode] User cancelled upload');
                        return;
                    }

                    if (action === 'create_copy') {
                        const originalExt = extractExtension(fileName);
                        const candidateName = normalizeCopyName(duplicateDecision.copyName || defaultCopyName, originalExt);
                        if (!isSupportedOntologyExtension(candidateName)) {
                            vscode.window.showErrorMessage('Unsupported file type for copy.');
                            return;
                        }
                        const candidateBase = normalizeCopyName(candidateName, '').replace(/\.[^/.]+$/, '');
                        const copyProjectId = candidateBase;
                        console.log(`[OntoCode] Creating copy with filename: ${candidateName}`);
                        return this._uploadOntology(copyProjectId, candidateName, fileData, 'create_copy', resolvedOwnerEmail, undefined, importMode, partition);
                    }

                    if (action === 'replace') {
                        console.log(`[OntoCode] 🔄 User chose: replace -> Calling _uploadOntology with action: replace`);
                        return this._uploadOntology(projectId, fileName, fileData, 'replace', resolvedOwnerEmail, undefined, importMode, partition);
                    }
                    return;
                } else {
                    duplicateCheckResult = 'unique';
                }
            } catch (checkError: any) {
                if (axios.isAxiosError(checkError) && checkError.response?.status === 404) {
                    console.warn('[OntoCode] Duplicate check endpoint not available, skipping duplicate check.');
                    duplicateCheckResult = 'skipped';
                } else {
                    console.error('[OntoCode] Duplicate check failed:', checkError);
                    duplicateCheckResult = 'failed';
                }
                // Continue with upload if check fails
            }
        }

        if (!action && !skipDuplicateCheck && duplicateCheckResult !== 'unique') {
            try {
                const statusUrl = `${GATEWAY_URL}/api/ontology/metadata/${projectId}/timestamp`;
                const statusResp = await axios.get(statusUrl, {
                    headers: authHeaders,
                    validateStatus: (status) => status < 500
                });
                if (statusResp?.status === 200 && statusResp?.data?.success) {
                    const defaultCopyName = buildDefaultCopyName(fileName, 1);
                    const duplicateDecision = await this.requestDuplicatePrompt({
                        context: 'ontology',
                        fileName,
                        ownerEmail: resolvedOwnerEmail,
                        defaultCopyName,
                        detail: 'What would you like to do?',
                        allowOpenExisting: true
                    });

                    if (!duplicateDecision) {
                        const choice = await vscode.window.showWarningMessage(
                            `A file named "${fileName}" already exists.`,
                            {
                                modal: true,
                                detail: 'What would you like to do?'
                            },
                            'Open Existing',
                            'Replace',
                            'Create Copy',
                            'Cancel'
                        );

                        if (choice === 'Open Existing') {
                            this._lastProjectId = projectId;
                            try {
                                await this.initializeCollaborationForProject(projectId, token);
                            } catch (error) {
                                console.warn('[OntoCode] Failed to initialize collaboration for existing project:', error);
                            }
                            if (this._isWebviewReady) {
                                this.postMessage({ type: 'fileReady', projectId });
                            }
                            return;
                        }

                        if (choice === 'Create Copy') {
                            const originalExt = extractExtension(fileName);
                            let copyIndex = 1;
                            while (true) {
                                const defaultName = buildDefaultCopyName(fileName, copyIndex);
                                const copyInput = await vscode.window.showInputBox({
                                    title: 'Create Copy',
                                    prompt: 'Enter a name for the copy',
                                    value: defaultName,
                                    ignoreFocusOut: true,
                                    validateInput: (value) => {
                                        const trimmed = value.trim();
                                        if (!trimmed) return 'Name is required.';
                                        const normalized = normalizeCopyName(trimmed, originalExt);
                                        if (originalExt && !normalized.toLowerCase().endsWith(originalExt.toLowerCase())) {
                                            return `Name must end with ${originalExt}`;
                                        }
                                        if (!isSupportedOntologyExtension(normalized)) {
                                            return 'Unsupported file type.';
                                        }
                                        return null;
                                    }
                                });

                                if (!copyInput) {
                                    return;
                                }

                                const candidateName = normalizeCopyName(copyInput, originalExt);
                                const candidateBase = normalizeCopyName(candidateName, '').replace(/\.[^/.]+$/, '');
                                const copyProjectId = candidateBase;
                                return this._uploadOntology(copyProjectId, candidateName, fileData, 'create_copy', resolvedOwnerEmail, undefined, importMode, partition);
                            }
                        }

                        if (choice === 'Replace') {
                            return this._uploadOntology(projectId, fileName, fileData, 'replace', resolvedOwnerEmail, undefined, importMode, partition);
                        }
                        return;
                    }

                    const action = duplicateDecision.action;
                    if (action === 'open_existing') {
                        this._lastProjectId = projectId;
                        try {
                            await this.initializeCollaborationForProject(projectId, token);
                        } catch (error) {
                            console.warn('[OntoCode] Failed to initialize collaboration for existing project:', error);
                        }
                        if (this._isWebviewReady) {
                            this.postMessage({ type: 'fileReady', projectId });
                        }
                        return;
                    }

                    if (action === 'cancel') {
                        return;
                    }

                    if (action === 'create_copy') {
                        const originalExt = extractExtension(fileName);
                        const candidateName = normalizeCopyName(duplicateDecision.copyName || defaultCopyName, originalExt);
                        if (!isSupportedOntologyExtension(candidateName)) {
                            vscode.window.showErrorMessage('Unsupported file type for copy.');
                            return;
                        }
                        const candidateBase = normalizeCopyName(candidateName, '').replace(/\.[^/.]+$/, '');
                        const copyProjectId = candidateBase;
                        return this._uploadOntology(copyProjectId, candidateName, fileData, 'create_copy', resolvedOwnerEmail, undefined, importMode, partition);
                    }

                    if (action === 'replace') {
                        return this._uploadOntology(projectId, fileName, fileData, 'replace', resolvedOwnerEmail, undefined, importMode, partition);
                    }
                    return;
                }
            } catch (fallbackError) {
                console.warn('[OntoCode] Fallback duplicate check failed:', fallbackError);
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

        // Show notification for large files
        const fileSizeMB = fileData.length / (1024 * 1024);
        if (fileSizeMB > 50) {
            const estimatedMinutes = Math.ceil(fileSizeMB / 10); // ~1 min per 10MB for GraphDB
            vscode.window.showInformationMessage(
                `Uploading large file (${fileSizeMB.toFixed(1)}MB). GraphDB processing may take ${estimatedMinutes}+ minutes. Please wait...`,
                { modal: false }
            );
        }

        const showLoadingResult = this.postMessage({ type: 'showLoading', projectId, fileName });
        console.log(`[OntoCode] 📢 showLoading message sent, result:`, showLoadingResult);

        try {
            // 4. Prepare the form data for multipart upload
            // Convert Uint8Array to Blob for web extension compatibility
            let buffer = new Uint8Array(fileData.buffer.byteLength);
            buffer.set(new Uint8Array(fileData.buffer));

            // Preprocess RDF/XML files to ensure proper namespace declarations
            if (fileName.toLowerCase().endsWith('.rdf')) {
                console.log('[OntoCode] Preprocessing RDF/XML file for namespace declarations...');
                try {
                    const fileContent = new TextDecoder('utf-8').decode(buffer);

                    // Check if file is missing namespace declarations
                    const hasRdfRoot = /<rdf:RDF/i.test(fileContent);
                    const hasDcNamespace = /xmlns:dc=/i.test(fileContent);
                    const hasBiboNamespace = /xmlns:bibo=/i.test(fileContent);

                    // If RDF root exists but missing common namespaces, wrap it
                    // Or if no RDF root at all (fragment), wrap it
                    if (!hasRdfRoot || !hasDcNamespace || !hasBiboNamespace) {
                        console.log('[OntoCode] Adding missing namespace declarations to RDF/XML file');
                        const wrappedContent = this.wrapRdfXml(fileContent);
                        buffer = new Uint8Array(new TextEncoder().encode(wrappedContent));
                        console.log('[OntoCode] ✅ RDF/XML file preprocessed with namespace declarations');
                    } else {
                        console.log('[OntoCode] RDF/XML file already has proper namespace declarations');
                    }
                } catch (preprocessError) {
                    console.error('[OntoCode] Failed to preprocess RDF/XML file:', preprocessError);
                    // Continue with original buffer if preprocessing fails
                }
            }

            // Optional: Compress file if it's a compressible format and > 1MB
            // Skip compression for localhost uploads where there's no network benefit
            let dataToUpload = buffer;
            const isLocalUpload = GATEWAY_URL.includes('localhost') || GATEWAY_URL.includes('127.0.0.1');
            const enableCompression = !isLocalUpload && shouldCompressFile(fileName) && buffer.length > 1024 * 1024;

            if (enableCompression) {
                console.log(`[OntoCode] File is ${(buffer.length / (1024 * 1024)).toFixed(2)}MB, attempting compression...`);
                try {
                    // Check if CompressionStream is available
                    if (typeof (globalThis as any).CompressionStream !== 'undefined') {
                        const startTime = Date.now();
                        const blob = new Blob([buffer]);
                        const compressedStream = blob.stream().pipeThrough(new (globalThis as any).CompressionStream('gzip'));
                        const compressedBlob = await new Response(compressedStream).blob();
                        dataToUpload = new Uint8Array(await compressedBlob.arrayBuffer());

                        const compressionTime = Date.now() - startTime;
                        const compressionRatio = ((1 - dataToUpload.length / buffer.length) * 100).toFixed(1);
                        console.log(`[OntoCode] ✅ Compressed from ${buffer.length} to ${dataToUpload.length} bytes (${compressionRatio}% reduction) in ${compressionTime}ms`);
                    } else {
                        console.log(`[OntoCode] ⚠️ CompressionStream not available, uploading uncompressed`);
                    }
                } catch (compressionError) {
                    console.error(`[OntoCode] ⚠️ Compression failed, uploading uncompressed:`, compressionError);
                    dataToUpload = buffer;
                }
            }

            const fileBlob = new Blob([dataToUpload], { type: 'application/rdf+xml' });
            const file = new File([fileBlob], fileName, { type: 'application/rdf+xml' });
            const formData = new FormData();
            formData.append('file', file);

            // Indicate if file is compressed
            if (enableCompression && dataToUpload.length < buffer.length) {
                formData.append('compressed', 'true');
            }

            // Resolve the owner email once (used by both the chunked and single-shot paths below)
            let finalOwnerEmail = resolvedOwnerEmail;
            if (!finalOwnerEmail) {
                try {
                    const tokenParts = token.split('.');
                    if (tokenParts.length === 3) {
                        const base64Payload = tokenParts[1];
                        const base64 = base64Payload.replace(/-/g, '+').replace(/_/g, '/');
                        const payload = JSON.parse(atob(base64));
                        if (payload.email) {
                            finalOwnerEmail = payload.email;
                            console.log(`[OntoCode] ✅ Resolved owner email from token: ${payload.email}`);
                        }
                    }
                } catch (tokenError) {
                    console.error('[OntoCode] ❌ Could not extract email from token:', tokenError);
                }
            }

            // CHUNK_UPLOAD_THRESHOLD is kept well under Cloudflare's 100MB proxy cap (and any
            // similar CDN/reverse-proxy limit) so large ontologies never hit that wall regardless
            // of whether compression above happened to shrink them enough on its own — compression
            // can silently no-op (older VS Code hosts without CompressionStream, unlisted formats
            // like .omn/.ofn/.obo, or a genuine compression failure), so chunking must not assume it ran.
            const CHUNK_UPLOAD_THRESHOLD = 40 * 1024 * 1024; // 40MB
            const wasActuallyCompressed = enableCompression && dataToUpload.length < buffer.length;

            let response: any = null;
            let lastError: any = null;
            const headers = {
                'Authorization': `Bearer ${token}`,
                // Browser FormData sets its own Content-Type with boundary
            };

            if (dataToUpload.length > CHUNK_UPLOAD_THRESHOLD) {
                console.log(`[OntoCode] File is ${(dataToUpload.length / (1024 * 1024)).toFixed(1)}MB (post-compression), using chunked upload`);
                response = await this.uploadOntologyInChunks(projectId, dataToUpload, fileName, {
                    ownerEmail: finalOwnerEmail,
                    action,
                    importMode,
                    partition,
                    workspaceId,
                    compressed: wasActuallyCompressed,
                    token,
                });
            } else {
                // Add action parameter if specified (replace or create_copy)
                if (action) {
                    formData.append('action', action);
                    console.log(`[OntoCode] ✅ Added action parameter to FormData: ${action}`);
                } else {
                    console.log(`[OntoCode] ⚠️ No action parameter specified, backend will check for duplicates`);
                }

                if (finalOwnerEmail) {
                    formData.append('ownerEmail', finalOwnerEmail);
                    console.log(`[OntoCode] ✅ Adding owner email: ${finalOwnerEmail}`);
                }

                // Add workspaceId if user is in a workspace
                if (workspaceId) {
                    formData.append('workspaceId', workspaceId);
                    console.log(`[OntoCode] ✅ Adding workspaceId: ${workspaceId}`);
                } else {
                    console.log(`[OntoCode] ⚠️ No workspaceId - user not in workspace mode`);
                }

                // 4. Upload to gateway endpoint
                // workspaceId MUST be on the query string: FreeViewOnlyInterceptor runs before multipart
                // is parsed, so request.getParameter("workspaceId") only sees URL params, not FormData.
                const query = new URLSearchParams();
                if (importMode) {
                    query.set('importMode', importMode);
                }
                if (partition) {
                    query.set('partition', partition);
                }
                if (workspaceId) {
                    query.set('workspaceId', workspaceId);
                }
                const queryString = query.toString();
                const uploadUrl = `${GATEWAY_URL}/api/ontology/upload/${encodeURIComponent(projectId)}${queryString ? `?${queryString}` : ''}`;
                const fileSizeMB = (fileData.length / (1024 * 1024)).toFixed(2);

                console.log(`[OntoCode] Uploading to: ${uploadUrl}`);
                console.log(`[OntoCode] Upload parameters - fileName: ${fileName}, action: ${action || 'none'}, projectId: ${projectId}`);
                console.log(`[OntoCode] File size: ${fileData.length} bytes (${fileSizeMB} MB)`);

                // Dynamic timeout based on file size
                // Base: 10 min, add 1 min per 10MB for GraphDB processing
                const baseTimeout = 10 * 60 * 1000; // 10 minutes
                const additionalTimeout = Math.ceil(fileData.length / (10 * 1024 * 1024)) * 60 * 1000; // 1 min per 10MB
                const uploadTimeout = Math.min(baseTimeout + additionalTimeout, 7_200_000); // Max 2 hours for uploads up to 1GB

                console.log(`[OntoCode] Calculated timeout: ${(uploadTimeout / 60000).toFixed(1)} minutes (includes GraphDB processing time)`);

                // Upload with retry logic (max 3 attempts)
                const MAX_RETRIES = 3;

                for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
                    try {
                        if (attempt > 0) {
                            const delay = Math.pow(2, attempt) * 1000; // Exponential backoff: 2s, 4s
                            console.log(`[OntoCode] Retry attempt ${attempt + 1}/${MAX_RETRIES} after ${delay}ms delay...`);
                            await new Promise(resolve => setTimeout(resolve, delay));
                        }

                        response = await axios.post(uploadUrl, formData, {
                            headers,
                            maxRedirects: 0,  // Disable redirects to catch any redirect issues
                            timeout: uploadTimeout,  // Dynamic timeout based on file size
                            maxContentLength: Infinity,
                            maxBodyLength: Infinity,
                            validateStatus: (status) => status < 500, // Accept all non-5xx responses
                            onUploadProgress: (progressEvent) => {
                                const percentCompleted = progressEvent.total
                                    ? Math.round((progressEvent.loaded * 100) / progressEvent.total)
                                    : 0;
                                const statusMsg = percentCompleted === 100
                                    ? `Upload complete. Processing in GraphDB... (this may take several minutes for large files)`
                                    : `Uploading: ${percentCompleted}%`;
                                console.log(`[OntoCode] ${statusMsg} (${progressEvent.loaded} / ${progressEvent.total ?? 0} bytes)`);
                                // Send progress to webview
                                this.postMessage({
                                    type: 'uploadProgress',
                                    projectId,
                                    percent: percentCompleted,
                                    loaded: progressEvent.loaded,
                                    total: progressEvent.total ?? 0,
                                    message: statusMsg
                                });
                            }
                        });

                        // Success - break out of retry loop
                        console.log(`[OntoCode] ✅ Upload successful on attempt ${attempt + 1}`);
                        break;

                    } catch (error: any) {
                        lastError = error;
                        const status = error?.response?.status;

                        // Don't retry on authentication/authorization errors
                        if (status === 401 || status === 403) {
                            console.error(`[OntoCode] ❌ Auth error (${status}), not retrying`);
                            throw error;
                        }

                        console.error(`[OntoCode] Upload attempt ${attempt + 1} failed:`, error?.message || error);

                        // If this was the last attempt, throw the error
                        if (attempt === MAX_RETRIES - 1) {
                            throw error;
                        }
                    }
                }
            }

            if (!response) {
                throw lastError || new Error('Upload failed with no response');
            }

            console.log(`[OntoCode] Upload response status: ${response.status}`);
            console.log(`[OntoCode] Upload response data:`, response.data);

            // 5. Check if upload was successful
            if (response.status === 200 || response.status === 201) {
                console.log(`[OntoCode] Upload successful for project: ${projectId}`);
                const uploadProjectId = response.data?.projectId || projectId;
                this._lastProjectId = uploadProjectId; // Remember this project

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

                // For replacements, show loading state but do NOT send fileReady yet.
                // The import is async — sending fileReady before COMPLETED causes the
                // Dashboard to fetch data while status is still PROCESSING, which fails.
                // The fallback status polling below (or WebSocket IMPORT_COMPLETED) will
                // send fileReady once the import actually completes.
                if (isReplacement && this._isWebviewReady) {
                    this.postMessage({ type: 'showLoading', projectId: uploadProjectId, fileName: finalFileName });
                }

                // Fallback: check status and trigger fileReady if COMPLETED (covers cases where WebSocket misses IMPORT_COMPLETED)
                // Calculate adaptive timeout based on file size
                const fileSizeMB = fileData.length / (1024 * 1024);
                const estimatedMinutes = Math.max(15, Math.ceil(fileSizeMB / 10)); // At least 15 min, ~10MB per minute
                const maxAttempts = Math.max(60, Math.ceil(estimatedMinutes * 60 / 5)); // At least 60 attempts
                console.log(`[OntoCode] File size: ${fileSizeMB.toFixed(1)}MB, estimated time: ${estimatedMinutes} minutes, max attempts: ${maxAttempts}`);

                // Send initial estimated time
                this.postMessage({
                    type: 'updateLoadingStatus',
                    projectId: uploadProjectId,
                    message: `Processing ${fileSizeMB.toFixed(1)}MB file. Estimated time: ${estimatedMinutes} minute${estimatedMinutes > 1 ? 's' : ''}...`,
                    estimatedMinutes
                });

                const scheduleStatusCheck = (attempt: number) => {
                    // Optimized delays: very small files (e.g. 20KB) often finish before the
                    // first poll, so check almost immediately on attempt 1, then back off.
                    const getDelay = (att: number) => {
                        if (att === 1) return 250;        // 250ms — catches small/fast imports
                        if (att <= 3) return 1000;        // 1s x 2 attempts = 2s
                        if (att <= 6) return 3000;        // 3s x 3 attempts = 9s
                        if (att <= 10) return 10000;      // 10s x 4 attempts = 40s
                        if (att <= 15) return 20000;      // 20s x 5 attempts = 100s
                        return 30000;                     // 30s x remaining attempts
                    };
                    const delay = getDelay(attempt);
                    console.log(`[OntoCode] Scheduling fallback status check (attempt ${attempt}/${maxAttempts}) in ${delay}ms for ${uploadProjectId}`);
                    setTimeout(async () => {
                        try {
                            const encodedProjectId = encodeURIComponent(uploadProjectId);
                            const statusUrl = `${GATEWAY_URL}/api/ontology/status/${encodedProjectId}`;
                            const statusResp = await axios.get(statusUrl, { headers });
                            console.log(`[OntoCode] Fallback status check for ${uploadProjectId}:`, statusResp.data);

                            const statusPayload = statusResp.data?.data || statusResp.data;
                            const status = statusPayload?.status;
                            const statusMessage = statusPayload?.statusMessage;

                            // Send progress update to webview
                            if (statusMessage && attempt % 2 === 0) { // Update every 2nd attempt to avoid spam
                                // Extract real progress from backend statusMessage (e.g., "Importing... (90%) | ETA...")
                                const progressMatch = statusMessage.match(/\((\d+)%\)/);
                                const progressPercent = progressMatch ? parseInt(progressMatch[1], 10) : Math.min(95, Math.floor((attempt / maxAttempts) * 100));
                                this.postMessage({
                                    type: 'updateLoadingStatus',
                                    projectId: uploadProjectId,
                                    message: statusMessage.includes('%') ? statusMessage : `${statusMessage} (${progressPercent}% complete)`,
                                    attempt,
                                    maxAttempts
                                });
                            }

                            if (status === 'COMPLETED') {
                                console.log(`[OntoCode] ✅ File completed via fallback status check, sending fileReady to webview`);
                                console.log(`[OntoCode] Webview ready state: ${this._isWebviewReady}`);
                                this.postMessage({ type: 'fileReady', projectId: uploadProjectId });
                                return;
                            }
                            // Continue polling unless failed or max attempts reached
                            if (status !== 'FAILED' && attempt < maxAttempts) {
                                scheduleStatusCheck(attempt + 1);
                            } else if (status === 'FAILED') {
                                console.error(`[OntoCode] Import failed for ${uploadProjectId}`);
                                this.postMessage({ type: 'importFailed', projectId: uploadProjectId, error: statusResp.data?.error });
                            } else if (attempt >= maxAttempts) {
                                console.error(`[OntoCode] Import timeout for ${uploadProjectId} - max attempts exceeded`);
                                this.postMessage({ type: 'importTimeout', projectId: uploadProjectId });
                            }
                        } catch (err) {
                            console.error('[OntoCode] Failed to check fallback status:', err);
                            if (attempt < maxAttempts) {
                                scheduleStatusCheck(attempt + 1);
                            } else {
                                console.error(`[OntoCode] Import check timeout for ${uploadProjectId}`);
                                this.postMessage({ type: 'importTimeout', projectId: uploadProjectId });
                            }
                        }
                    }, delay);
                };

                scheduleStatusCheck(1);

            } else if (response.status === 409 && response.data?.isDuplicate && !action) {
                const conflictProjectId = response.data?.projectId;
                if (!conflictProjectId) {
                    const conflictMessage = response.data?.error || 'A file with this name already exists.';
                    vscode.window.showErrorMessage(conflictMessage);
                    return;
                }

                const choice = await vscode.window.showWarningMessage(
                    `A file named "${fileName}" already exists.`,
                    { modal: true, detail: 'Do you want to overwrite it, create a copy, or open the existing file?' },
                    'Open Existing',
                    'Replace',
                    'Create Copy',
                    'Cancel'
                );

                if (choice === 'Open Existing') {
                    this._lastProjectId = conflictProjectId;
                    try {
                        await this.initializeCollaborationForProject(conflictProjectId, token);
                    } catch (error) {
                        console.warn('[OntoCode] Failed to collaboration for existing project:', error);
                    }
                    if (this._isWebviewReady) {
                        this.postMessage({ type: 'fileReady', projectId: conflictProjectId });
                    }
                    return;
                }

                if (choice === 'Create Copy') {
                    const originalExt = extractExtension(fileName);
                    let copyIndex = 1;
                    while (true) {
                        const defaultName = buildDefaultCopyName(fileName, copyIndex);
                        const copyInput = await vscode.window.showInputBox({
                            title: 'Create Copy',
                            prompt: 'Enter a name for the copy',
                            value: defaultName,
                            ignoreFocusOut: true,
                            validateInput: (value) => {
                                const trimmed = value.trim();
                                if (!trimmed) return 'Name is required.';
                                const normalized = normalizeCopyName(trimmed, originalExt);
                                if (originalExt && !normalized.toLowerCase().endsWith(originalExt.toLowerCase())) {
                                    return `Name must end with ${originalExt}`;
                                }
                                if (!isSupportedOntologyExtension(normalized)) {
                                    return 'Unsupported file type.';
                                }
                                return null;
                            }
                        });

                        if (!copyInput) {
                            return;
                        }

                        const candidateName = normalizeCopyName(copyInput, originalExt);
                        if (resolvedOwnerEmail) {
                            try {
                                const dupCheck = await axios.get(
                                    `${GATEWAY_URL}/api/ontology/check-duplicate?filename=${encodeURIComponent(candidateName)}&ownerEmail=${encodeURIComponent(resolvedOwnerEmail)}`,
                                    { headers: authHeaders }
                                );
                                if (dupCheck.data?.isDuplicate) {
                                    vscode.window.showWarningMessage(`"${candidateName}" already exists. Please choose a different name.`);
                                    copyIndex++;
                                    continue;
                                }
                            } catch (dupError) {
                                console.warn('[OntoCode] Copy name duplicate check failed:', dupError);
                            }
                        }

                        const candidateBase = normalizeCopyName(candidateName, '').replace(/\.[^/.]+$/, '');
                        const copyProjectId = candidateBase;
                        return this._uploadOntology(copyProjectId, candidateName, fileData, 'create_copy', resolvedOwnerEmail, undefined, importMode, partition);
                    }
                }

                if (choice === 'Replace') {
                    return this._uploadOntology(projectId, fileName, fileData, 'replace', resolvedOwnerEmail, undefined, importMode, partition);
                }
                return;
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
     * Splits an already-compressed-or-not payload into chunks and uploads them sequentially to
     * /api/ontology/upload-chunk/{projectId}, so no single HTTP request ever approaches a proxy's
     * size cap (e.g. Cloudflare's 100MB limit) regardless of total file size. Chunks are uploaded
     * in order with per-chunk retry; the final chunk's response carries the same shape as the
     * single-shot /upload endpoint's response (success/projectId/gridfsFileId/...), and this method
     * returns an axios-response-shaped object so callers can treat it identically either way.
     */
    private async uploadOntologyInChunks(
        projectId: string,
        data: Uint8Array,
        fileName: string,
        opts: {
            ownerEmail?: string;
            action?: string;
            importMode?: string;
            partition?: string;
            workspaceId?: string;
            compressed: boolean;
            token: string;
        },
    ): Promise<{ status: number; data: any }> {
        const CHUNK_SIZE = 20 * 1024 * 1024; // 20MB per chunk
        const MAX_RETRIES_PER_CHUNK = 3;

        const uploadId = generateUploadId();
        const chunks = splitIntoChunks(data, CHUNK_SIZE);
        const totalChunks = chunks.length;
        const totalBytes = data.length;

        console.log(`[OntoCode] Chunked upload starting: uploadId=${uploadId}, ${totalChunks} chunks of ~${(CHUNK_SIZE / (1024 * 1024)).toFixed(0)}MB, ${(totalBytes / (1024 * 1024)).toFixed(1)}MB total`);

        let uploadedBytes = 0;
        let finalResponseData: any = null;

        for (let i = 0; i < totalChunks; i++) {
            const chunk = chunks[i];
            const chunkHash = await sha256Hex(chunk);
            const isLastChunk = i === totalChunks - 1;

            let lastError: any = null;
            let succeeded = false;

            for (let attempt = 0; attempt < MAX_RETRIES_PER_CHUNK; attempt++) {
                try {
                    if (attempt > 0) {
                        const delay = Math.pow(2, attempt) * 1000;
                        console.log(`[OntoCode] Retrying chunk ${i + 1}/${totalChunks} after ${delay}ms (attempt ${attempt + 1}/${MAX_RETRIES_PER_CHUNK})`);
                        await new Promise(resolve => setTimeout(resolve, delay));
                    }

                    const form = new FormData();
                    form.append('chunk', new Blob([chunk as BlobPart]), fileName);
                    form.append('uploadId', uploadId);
                    form.append('chunkIndex', String(i));
                    form.append('totalChunks', String(totalChunks));
                    form.append('chunkHash', chunkHash);
                    form.append('fileName', fileName);
                    if (opts.ownerEmail) form.append('ownerEmail', opts.ownerEmail);
                    if (opts.action) form.append('action', opts.action);
                    if (opts.importMode) form.append('importMode', opts.importMode);
                    if (opts.partition) form.append('partition', opts.partition);
                    if (opts.workspaceId) form.append('workspaceId', opts.workspaceId);
                    form.append('compressed', String(opts.compressed));

                    const chunkResp = await axios.post(
                        `${GATEWAY_URL}/api/ontology/upload-chunk/${encodeURIComponent(projectId)}`,
                        form,
                        {
                            headers: { 'Authorization': `Bearer ${opts.token}` },
                            timeout: 5 * 60 * 1000, // 5 min per chunk is generous for 20MB
                            maxContentLength: Infinity,
                            maxBodyLength: Infinity,
                            validateStatus: (status) => status < 500,
                        },
                    );

                    if (chunkResp.status >= 400) {
                        throw Object.assign(new Error(chunkResp.data?.error || `Chunk upload failed with status ${chunkResp.status}`), { response: chunkResp });
                    }

                    uploadedBytes += chunk.length;
                    const percent = Math.round((uploadedBytes / totalBytes) * 100);
                    this.postMessage({
                        type: 'uploadProgress',
                        projectId,
                        percent,
                        loaded: uploadedBytes,
                        total: totalBytes,
                        message: isLastChunk && chunkResp.data?.success && chunkResp.data?.gridfsFileId
                            ? 'Upload complete. Processing in GraphDB... (this may take several minutes for large files)'
                            : `Uploading chunk ${i + 1}/${totalChunks}: ${percent}%`,
                    });

                    if (isLastChunk) {
                        finalResponseData = chunkResp.data;
                    }
                    succeeded = true;
                    break;
                } catch (error: any) {
                    lastError = error;
                    const status = error?.response?.status;
                    if (status === 401 || status === 403) {
                        throw error; // Don't retry auth errors
                    }
                    console.error(`[OntoCode] Chunk ${i + 1}/${totalChunks} attempt ${attempt + 1} failed:`, error?.message || error);
                    if (attempt === MAX_RETRIES_PER_CHUNK - 1) {
                        throw lastError;
                    }
                }
            }

            if (!succeeded) {
                throw lastError || new Error(`Failed to upload chunk ${i + 1}/${totalChunks}`);
            }
        }

        if (!finalResponseData) {
            throw new Error('Chunked upload finished without a final response from the server');
        }

        return { status: 200, data: finalResponseData };
    }

    /**
     * Handle ontology upload from webview (admin flow - loading file from project)
     * This receives base64 file content and uploads it to the ontology editor
     */
    private async handleUploadOntologyFromWebview(
        projectId: string,
        fileName: string,
        base64Content: string,
        ownerEmail?: string,
        skipDuplicateCheck?: boolean,
        importMode?: string,
        partition?: string
    ) {
        console.log(`[OntoCode] 📤 Handling webview upload for project: ${projectId}, file: ${fileName}`);

        try {
            // Fast path: check if this file already exists in GraphDB before converting/uploading
            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (token) {
                try {
                    const statusUrl = `${GATEWAY_URL}/api/ontology/status/${encodeURIComponent(projectId)}`;
                    const statusResp = await axios.get(statusUrl, {
                        headers: { 'Authorization': `Bearer ${token}` },
                        validateStatus: (status) => status < 500
                    });
                    const status = statusResp?.data?.data?.status || statusResp?.data?.status;
                    if (status === 'COMPLETED') {
                        console.log(`[OntoCode] ✅ File already exists in GraphDB, sending fileReady directly: ${projectId}`);
                        this._lastProjectId = projectId;
                        try {
                            await this.initializeCollaborationForProject(projectId, token);
                        } catch (e) {
                            console.warn('[OntoCode] Failed to initialize collaboration:', e);
                        }
                        if (this._isWebviewReady) {
                            this.postMessage({ type: 'fileReady', projectId });
                        }
                        return;
                    }
                } catch (checkErr) {
                    console.log('[OntoCode] Status check failed, proceeding with upload:', checkErr);
                }
            }

            // Convert base64 to Uint8Array (web-compatible)
            const fileData = base64ToUint8Array(base64Content);

            console.log(`[OntoCode] 📦 Converted base64 to binary, size: ${fileData.length} bytes`);

            // Delegate to the shared upload logic, preserving the stable projectId from the webview
            await this._uploadOntology(projectId, fileName, fileData, undefined, ownerEmail, skipDuplicateCheck, importMode, partition, true);

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
    private async handleUploadFileToProject(
        projectId: string,
        fileName: string,
        base64Content: string,
        fileSize: number,
        options?: { skipDuplicateCheck?: boolean; replaceFileId?: string | null; openAfterUpload?: boolean }
    ): Promise<{ fileId: string; fileName: string } | null> {
        console.log(`[OntoCode] 📤 Uploading file to project: ${projectId}, file: ${fileName}, size: ${(fileSize / (1024 * 1024)).toFixed(2)}MB`);
        const uploadPerfStart = Date.now();

        try {
            // Get auth token
            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (!token) {
                throw new Error('Not authenticated');
            }

            // Step 1: Check if file already exists in project
            const checkUrl = `${GATEWAY_URL}/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(fileName)}`;
            console.log(`[OntoCode] Checking for duplicate file: ${checkUrl}`);

            let replaceFileId: string | null = options?.replaceFileId ?? null;
            let finalFileName = fileName;

            // Check deployment type first
            const deploymentType = await this.getStoredDeploymentType();
            const isCloudDeployment = deploymentType === 'cloud';

            if (isCloudDeployment) {
                console.log('[OntoCode] ☁️ Cloud deployment: skipping duplicate file check');
            }

            if (!options?.skipDuplicateCheck && !isCloudDeployment) {
                // Only check for duplicates in self-hosted mode
                try {
                    const checkResponse = await axios.get(checkUrl, {
                        headers: {
                            'Authorization': `Bearer ${token}`
                        }
                    });

                    if (checkResponse.data.exists) {
                        console.log(`[OntoCode] ⚠️ File "${fileName}" already exists in project`);

                        // Self-hosted: show confirmation dialog with options
                        const choice = await vscode.window.showWarningMessage(
                            `A file named "${fileName}" already exists in this project.`,
                            {
                                modal: true,
                                detail: 'What would you like to do?'
                            },
                            'Open Existing',
                            'Replace',
                            'Create Copy',
                            'Cancel'
                        );

                        if (choice === 'Open Existing') {
                            const existingId = checkResponse.data.existingFile?.fileId || checkResponse.data.existingFile?.id || null;
                            const existingName = checkResponse.data.existingFile?.fileName || fileName;
                            if (existingId) {
                                this.postMessage({
                                    type: 'openProjectFile',
                                    projectId,
                                    fileId: existingId,
                                    fileName: existingName
                                });
                            } else {
                                vscode.window.showErrorMessage('Could not locate the existing file to open.');
                            }
                            return null;
                        } else if (choice === 'Replace') {
                            // User chose to replace - store the existing file ID
                            replaceFileId = checkResponse.data.existingFile?.fileId || checkResponse.data.existingFile?.id || null;
                            console.log(`[OntoCode] User chose to replace file. Old file ID: ${replaceFileId}`);
                        } else if (choice === 'Create Copy') {
                            const originalExt = extractExtension(fileName);
                            let copyIndex = 1;
                            while (true) {
                                const defaultName = buildDefaultCopyName(fileName, copyIndex);
                                const copyInput = await vscode.window.showInputBox({
                                    title: 'Create Copy',
                                    prompt: 'Enter a name for the copy',
                                    value: defaultName,
                                    ignoreFocusOut: true,
                                    validateInput: (value) => {
                                        const trimmed = value.trim();
                                        if (!trimmed) return 'Name is required.';
                                        const normalized = normalizeCopyName(trimmed, originalExt);
                                        if (originalExt && !normalized.toLowerCase().endsWith(originalExt.toLowerCase())) {
                                            return `Name must end with ${originalExt}`;
                                        }
                                        if (!isSupportedOntologyExtension(normalized)) {
                                            return 'Unsupported file type.';
                                        }
                                        return null;
                                    }
                                });

                                if (!copyInput) {
                                    return null;
                                }

                                const candidateName = normalizeCopyName(copyInput, originalExt);
                                try {
                                    const dupCheck = await axios.get(
                                        `${GATEWAY_URL}/api/projects/${projectId}/files/check?fileName=${encodeURIComponent(candidateName)}`,
                                        { headers: { 'Authorization': `Bearer ${token}` } }
                                    );
                                    if (dupCheck.data?.exists) {
                                        vscode.window.showWarningMessage(`"${candidateName}" already exists. Please choose a different name.`);
                                        copyIndex++;
                                        continue;
                                    }
                                } catch (dupError) {
                                    console.warn('[OntoCode] Copy name duplicate check failed:', dupError);
                                }

                                finalFileName = candidateName;
                                break;
                            }
                        } else {
                            // User cancelled
                            console.log('[OntoCode] User cancelled upload');
                            return null;
                        }
                    }
                } catch (checkError: any) {
                    // If check endpoint fails, continue with upload (backward compatibility)
                    console.warn('[OntoCode] Failed to check for duplicate file:', checkError.message);
                }
            }

            // Check if it's a large file (>10MB)
            const isLargeFile = fileSize > 10 * 1024 * 1024;

            if (isLargeFile) {
                vscode.window.showInformationMessage(`Processing large file: ${finalFileName} (${(fileSize / (1024 * 1024)).toFixed(2)}MB)...`);
            }

            // Upload to project files endpoint with progress tracking
            const uploadUrl = `${GATEWAY_URL}/api/projects/${projectId}/files`;
            console.log(`[OntoCode] Upload URL: ${uploadUrl}`);

            console.log(`[OntoCode] [PERF] Pre-upload checks (duplicate, deployment): ${Date.now() - uploadPerfStart}ms`);

            const httpUploadStart = Date.now();
            const response = await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: `Uploading ${finalFileName}`,
                cancellable: false
            }, async (progress) => {
                progress.report({ message: 'Preparing upload...', increment: 10 });

                // Decode base64 to binary and build a Blob for multipart upload
                const binaryStr = atob(base64Content);
                const bytes = new Uint8Array(binaryStr.length);
                for (let i = 0; i < binaryStr.length; i++) {
                    bytes[i] = binaryStr.charCodeAt(i);
                }
                const fileBlob = new Blob([bytes], { type: 'application/rdf+xml' });
                const formData = new FormData();
                formData.append('file', fileBlob, finalFileName);
                formData.append('fileName', finalFileName);
                formData.append('fileType', 'application/rdf+xml');
                if (replaceFileId) {
                    formData.append('replaceFileId', replaceFileId);
                }

                const uploadResponse = await axios.post(uploadUrl, formData, {
                    headers: {
                        'Authorization': `Bearer ${token}`,
                    },
                    timeout: 7_200_000, // 2 hours for large ontology uploads (up to 1GB)
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

            console.log(`[OntoCode] [PERF] HTTP upload (POST to gateway): ${Date.now() - httpUploadStart}ms`);

            if (response.status === 200 || response.status === 201) {
                console.log('[OntoCode] ✅ File uploaded to project successfully');

                const actionTaken = replaceFileId ? 'replaced' : (finalFileName !== fileName ? 'uploaded as copy' : 'uploaded');

                if (isLargeFile) {
                    vscode.window.showInformationMessage(`Large file "${finalFileName}" ${actionTaken} successfully! Processing in background...`);
                } else {
                    vscode.window.showInformationMessage(`File "${finalFileName}" ${actionTaken} successfully`);
                }

                // Notify webview to refresh file list
                const uploadedFileId = response.data?.fileId || response.data?.id;
                const uploadedFileName = response.data?.filename || finalFileName;

                console.log(`[OntoCode] 📋 File uploaded - ID: ${uploadedFileId}, Name: ${uploadedFileName}`);

                // Longer delay to ensure backend has saved to database (increased from 500ms to 1000ms)
                console.log(`[OntoCode] ⏳ Waiting 1 second for database commit...`);
                await new Promise(resolve => setTimeout(resolve, 1000));

                // Send fileReady to trigger file list refresh
                this.postMessage({ type: 'fileReady', projectId: projectId, uploadedFileId, uploadedFileName });
                console.log(`[OntoCode] 📤 Sent fileReady message for project: ${projectId}, fileId: ${uploadedFileId}`);

                if (options?.openAfterUpload && uploadedFileId) {
                    // Another delay before opening to ensure file list is refreshed (increased to 500ms)
                    console.log(`[OntoCode] ⏳ Waiting 500ms before opening file...`);
                    await new Promise(resolve => setTimeout(resolve, 500));

                    console.log(`[OntoCode] 📂 Opening newly created file: ${uploadedFileName}`);
                    this.postMessage({
                        type: 'openProjectFile',
                        projectId,
                        fileId: uploadedFileId,
                        fileName: uploadedFileName
                    });
                }

                if (uploadedFileId) {
                    return { fileId: uploadedFileId, fileName: uploadedFileName };
                }

                return null;
            } else {
                throw new Error(`Upload failed with status: ${response.status}`);
            }
        } catch (error: any) {
            console.error('[OntoCode] ❌ Failed to upload file to project:', error);
            console.error('[OntoCode] Error response:', error.response);
            console.error('[OntoCode] Error response data:', error.response?.data);

            let errorMessage = `Failed to upload ${fileName}`;
            if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
                errorMessage += ': Upload timeout. Please try again or check your connection.';
            } else if (error.status === 413) {
                // Storage limit exceeded or file too large
                const responseData = error.data;
                console.log('[OntoCode] Storage limit response data:', responseData);
                if (responseData?.message) {
                    errorMessage = responseData.message;
                } else if (responseData?.error) {
                    errorMessage = responseData.error;
                } else {
                    errorMessage = 'Storage limit exceeded. Please upgrade your plan or delete existing files.';
                }
            } else if (error.response?.data?.message) {
                errorMessage = error.response.data.message;
            } else if (error.response?.data?.error) {
                errorMessage += `: ${error.response.data.error}`;
            } else if (error.message) {
                errorMessage += `: ${error.message}`;
            }

            console.log('[OntoCode] Final error message:', errorMessage);
            vscode.window.showErrorMessage(errorMessage);
            return null;
        }

        return null;
    }

    /**
     * Handle download request for a specific file
     */
    // Client-side ceiling for polling, comfortably above the backend's own 45-min
    // stuck-job watchdog (OntologyExportJobService.java) — that watchdog is what
    // actually converts a hung job into an error; this is just a last-resort guard.
    private static readonly EXPORT_POLL_INTERVAL_MS = 3000;
    private static readonly EXPORT_MAX_POLL_MS = 60 * 60 * 1000;

    /**
     * Submits the export as a background job and polls until it's ready, instead of
     * one long-blocking request. The previous single `axios.get(..., {timeout: 300000})`
     * gave up after 5 minutes even though the backend/gateway/ingress support up to 2
     * hours — for a large ontology the server was often still working when this client
     * gave up. See OntologyExportJobService.java for the job lifecycle this polls against.
     */
    private async handleDownload(projectId: string, format: string, filename: string, requestId?: number) {
        try {
            console.log(`[OntoCode] Downloading export for project: ${projectId}, format: ${format}`);

            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (!token) {
                vscode.window.showErrorMessage('You must be logged in to download files.');
                return;
            }
            const authHeaders = { 'Authorization': `Bearer ${token}` };

            await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: `Exporting ${filename}...`,
                cancellable: true
            }, async (progress, cancellationToken) => {
                progress.report({ message: 'This may take several minutes for large ontologies. Click Cancel to stop.' });

                // Abort any in-flight HTTP call the moment the user cancels, instead
                // of waiting for it to finish and only then noticing the token.
                const abortController = new AbortController();
                cancellationToken.onCancellationRequested(() => abortController.abort());
                const throwIfCancelled = () => {
                    if (cancellationToken.isCancellationRequested) {
                        throw new Error('EXPORT_CANCELLED');
                    }
                };

                const submitRes = await axios.post(
                    `${GATEWAY_URL}/api/ontology/export-async/${encodeURIComponent(projectId)}?format=${encodeURIComponent(format)}`,
                    undefined,
                    { headers: authHeaders, timeout: 30000, signal: abortController.signal }
                );
                const jobId = submitRes.data?.jobId;
                if (!jobId) {
                    throw new Error('Export could not be started.');
                }

                const deadline = Date.now() + OntoCodePanel.EXPORT_MAX_POLL_MS;
                let jobStatus = 'PENDING';
                while (jobStatus !== 'COMPLETED') {
                    throwIfCancelled();
                    const statusRes = await axios.get(
                        `${GATEWAY_URL}/api/ontology/export-async/status/${jobId}`,
                        { headers: authHeaders, timeout: 30000, signal: abortController.signal }
                    );
                    jobStatus = statusRes.data?.status;
                    if (jobStatus === 'ERROR') {
                        throw new Error(statusRes.data?.error || 'Export failed.');
                    }
                    if (jobStatus !== 'COMPLETED') {
                        if (Date.now() >= deadline) {
                            throw new Error('Export is taking much longer than expected. Please try again later.');
                        }
                        await new Promise(resolve => setTimeout(resolve, OntoCodePanel.EXPORT_POLL_INTERVAL_MS));
                    }
                }
                throwIfCancelled();

                const response = await axios.get(
                    `${GATEWAY_URL}/api/ontology/export-async/download/${jobId}`,
                    { headers: authHeaders, responseType: 'arraybuffer', timeout: 300000, signal: abortController.signal }
                );
                throwIfCancelled();

                console.log(`[OntoCode] Response status: ${response.status}`);
                console.log(`[OntoCode] Response data length: ${response.data.byteLength} bytes`);

                const saveUri = await vscode.window.showSaveDialog({
                    defaultUri: vscode.Uri.file(filename),
                    filters: {
                        'OWL Files': ['owl'],
                        'All Files': ['*']
                    }
                });

                if (saveUri) {
                    console.log(`[OntoCode] Saving to: ${saveUri.fsPath}`);
                    await (vscode.workspace as any).fs.writeFile(saveUri, new Uint8Array(response.data));
                    vscode.window.showInformationMessage(`File saved successfully to ${saveUri.fsPath}`);
                    this.postMessage({ type: 'downloadOntologyComplete', requestId });
                } else {
                    console.log(`[OntoCode] User cancelled save dialog`);
                    this.postMessage({ type: 'downloadOntologyFailed', requestId, cancelled: true });
                }
            });
        } catch (error) {
            // User clicked Cancel on the progress notification — not an error.
            // Covers both our explicit marker and an axios request aborted by the signal.
            if ((error as Error)?.message === 'EXPORT_CANCELLED' || axios.isCancel(error)
                || (error as Error)?.name === 'CanceledError') {
                console.log('[OntoCode] Export cancelled by user');
                vscode.window.showInformationMessage(`Export of ${filename} cancelled.`);
                this.postMessage({ type: 'downloadOntologyFailed', requestId, cancelled: true });
                return;
            }
            console.error('[OntoCode] Download error:', error);
            let errorMessage = 'Failed to download file. See console for details.';
            if (axios.isAxiosError(error)) {
                const axiosError = error as AxiosError;
                if (axiosError.response) {
                    console.error('[OntoCode] Error response:', axiosError.response.status, axiosError.response.data);
                    errorMessage = `Download failed: ${axiosError.response.status} - ${JSON.stringify(axiosError.response.data)}`;
                } else if (axiosError.request) {
                    console.error('[OntoCode] No response received');
                    errorMessage = 'Download failed: No response from server. The file may be too large or the server is taking too long to export it.';
                } else {
                    console.error('[OntoCode] Error:', axiosError.message);
                    errorMessage = `Download failed: ${axiosError.message}`;
                }
            } else {
                errorMessage = (error as Error)?.message || errorMessage;
            }
            vscode.window.showErrorMessage(errorMessage);
            this.postMessage({ type: 'downloadOntologyFailed', requestId, error: errorMessage });
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
     * Handle download of file content directly from webview
     */
    private async handleDownloadFile(content: string, filename: string) {
        try {
            console.log(`[OntoCode] Downloading file: ${filename}, content length: ${content.length}`);

            // Show save dialog
            const saveUri = await vscode.window.showSaveDialog({
                defaultUri: vscode.Uri.file(filename),
                filters: {
                    'Ontology Files': ['owl', 'ttl', 'rdf', 'nt', 'omn', 'ofn'],
                    'All Files': ['*']
                }
            });

            if (saveUri) {
                console.log(`[OntoCode] Saving to: ${saveUri.fsPath}`);
                // Save file with UTF-8 encoding
                await vscode.workspace.fs.writeFile(
                    saveUri,
                    new TextEncoder().encode(content)
                );
                vscode.window.showInformationMessage(`File saved successfully to ${saveUri.fsPath}`);
                console.log(`[OntoCode] File saved: ${saveUri.fsPath}, size: ${content.length} bytes`);
            } else {
                console.log(`[OntoCode] User cancelled save dialog`);
            }
        } catch (error) {
            console.error('[OntoCode] Download file error:', error);
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
            // Optional: Compress file if it's a compressible format and > 1MB
            let dataToUpload = fileData;
            const enableCompression = shouldCompressFile(fileName) && fileData.length > 1024 * 1024;

            if (enableCompression) {
                console.log(`[OntoCode] File is ${(fileData.length / (1024 * 1024)).toFixed(2)}MB, attempting compression...`);
                try {
                    if (typeof (globalThis as any).CompressionStream !== 'undefined') {
                        const startTime = Date.now();
                        const blob = new Blob([fileData]);
                        const compressedStream = blob.stream().pipeThrough(new (globalThis as any).CompressionStream('gzip'));
                        const compressedBlob = await new Response(compressedStream).blob();
                        dataToUpload = new Uint8Array(await compressedBlob.arrayBuffer());

                        const compressionTime = Date.now() - startTime;
                        const compressionRatio = ((1 - dataToUpload.length / fileData.length) * 100).toFixed(1);
                        console.log(`[OntoCode] ✅ Compressed from ${fileData.length} to ${dataToUpload.length} bytes (${compressionRatio}% reduction) in ${compressionTime}ms`);
                    }
                } catch (compressionError) {
                    console.error(`[OntoCode] ⚠️ Compression failed:`, compressionError);
                    dataToUpload = fileData;
                }
            }

            const wasActuallyCompressed = enableCompression && dataToUpload.length < fileData.length;
            const CHUNK_UPLOAD_THRESHOLD = 40 * 1024 * 1024; // 40MB — see uploadOntologyInChunks doc

            let lastError: any = null;
            let response: any = null;

            if (dataToUpload.length > CHUNK_UPLOAD_THRESHOLD) {
                console.log(`[OntoCode] File is ${(dataToUpload.length / (1024 * 1024)).toFixed(1)}MB (post-compression), using chunked upload`);
                response = await this.uploadOntologyInChunks(uploadProjectId, dataToUpload, fileName, {
                    compressed: wasActuallyCompressed,
                    token,
                });
            } else {
                const formData = new FormData();
                const fileBlob = new Blob([dataToUpload], { type: 'application/rdf+xml' });
                const file = new File([fileBlob], fileName, { type: 'application/rdf+xml' });
                formData.append('file', file);

                if (wasActuallyCompressed) {
                    formData.append('compressed', 'true');
                }

                const uploadUrl = `${GATEWAY_URL}/api/ontology/upload/${uploadProjectId}`;
                const fileSizeMB = (fileData.length / (1024 * 1024)).toFixed(2);

                console.log('[OntoCode] Upload URL:', uploadUrl);
                console.log(`[OntoCode] Uploading file... Size: ${fileSizeMB}MB`);

                // Show notification for large files
                if (fileData.length > 50 * 1024 * 1024) {
                    const estimatedMinutes = Math.ceil(fileData.length / (10 * 1024 * 1024));
                    vscode.window.showInformationMessage(
                        `Uploading large file (${fileSizeMB}MB). GraphDB processing may take ${estimatedMinutes}+ minutes.`,
                        { modal: false }
                    );
                }

                // Dynamic timeout based on file size
                const baseTimeout = 10 * 60 * 1000;
                const additionalTimeout = Math.ceil(fileData.length / (10 * 1024 * 1024)) * 60 * 1000;
                const uploadTimeout = Math.min(baseTimeout + additionalTimeout, 7_200_000);

                console.log(`[OntoCode] Calculated timeout: ${(uploadTimeout / 60000).toFixed(1)} minutes`);

                // Upload with retry logic (max 3 attempts)
                const MAX_RETRIES = 3;

                for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
                    try {
                        if (attempt > 0) {
                            const delay = Math.pow(2, attempt) * 1000;
                            console.log(`[OntoCode] Retry attempt ${attempt + 1}/${MAX_RETRIES} after ${delay}ms...`);
                            await new Promise(resolve => setTimeout(resolve, delay));
                        }

                        response = await axios.post(uploadUrl, formData, {
                            headers: {
                                'Authorization': `Bearer ${token}`
                            },
                            maxContentLength: Infinity,
                            maxBodyLength: Infinity,
                            timeout: uploadTimeout,
                            onUploadProgress: (progressEvent) => {
                                const percentCompleted = progressEvent.total
                                    ? Math.round((progressEvent.loaded * 100) / progressEvent.total)
                                    : 0;
                                const statusMsg = percentCompleted === 100
                                    ? `Upload complete. Processing in GraphDB...`
                                    : `Uploading: ${percentCompleted}%`;
                                console.log(`[OntoCode] ${statusMsg} (${progressEvent.loaded} / ${progressEvent.total} bytes)`);
                            }
                        });

                        console.log(`[OntoCode] ✅ Upload successful on attempt ${attempt + 1}`);
                        break;

                    } catch (error: any) {
                        lastError = error;
                        const status = error?.response?.status;

                        if (status === 401 || status === 403) {
                            console.error(`[OntoCode] ❌ Auth error (${status}), not retrying`);
                            throw error;
                        }

                        console.error(`[OntoCode] Upload attempt ${attempt + 1} failed:`, error?.message || error);

                        if (attempt === MAX_RETRIES - 1) {
                            throw error;
                        }
                    }
                }
            }

            if (!response) {
                throw lastError || new Error('Upload failed with no response');
            }

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

        // Detect if we're in web extension mode (running in browser, not desktop VS Code)
        const isWebExtension = typeof process === 'undefined' || !process.versions || !process.versions.electron;

        // The VSCode API script that needs to be injected
        const vscodeApiInjectionScript = `
            <script nonce="${nonce}">
                const vscode = acquireVsCodeApi();
                window.vscode = vscode;
                window.API_BASE_URL = '${GATEWAY_URL}';
                // Inject environment configuration
                window.__ONTOCODE_CONFIG__ = {
                    SELF_HOSTED_GATEWAY_URL: '${process.env.SELF_HOSTED_GATEWAY_URL || 'http://localhost:80'}',
                    SELF_HOSTED_EDITOR_URL: '${process.env.SELF_HOSTED_EDITOR_URL || 'http://localhost:80'}',
                    SELF_HOSTED_PLUGIN_URL: '${process.env.SELF_HOSTED_PLUGIN_URL || 'http://localhost:8087'}',
                    CLOUD_GATEWAY_URL: '${process.env.CLOUD_GATEWAY_URL || 'https://ontocodeapi.selfresearch.org'}',
                    CLOUD_EDITOR_URL: '${process.env.CLOUD_EDITOR_URL || 'https://ontocodeapi.selfresearch.org'}',
                    CLOUD_PLUGIN_URL: '${process.env.CLOUD_PLUGIN_URL || 'https://ontocodeapi.selfresearch.org:8087'}',
                    DEFAULT_DEPLOYMENT_TYPE: '${process.env.DEFAULT_DEPLOYMENT_TYPE || 'cloud'}',
                    IS_WEB_EXTENSION: ${isWebExtension}
                };
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
                script-src 'nonce-${nonce}' https://cdn.tailwindcss.com https://unpkg.com https://aistudiocdn.com https://js.stripe.com ${webview.cspSource} 'unsafe-eval' ${GATEWAY_URL} ${PLUGIN_SERVICE_URL} http://localhost:* http://127.0.0.1:*;
                style-src ${webview.cspSource} 'unsafe-inline' https://unpkg.com https://cdn.tailwindcss.com;
                font-src ${webview.cspSource} https://unpkg.com data:; 
                connect-src 'self' https://ontocodeapi.selfresearch.org https: wss: ws: https://ontocodeapi.selfresearch.org:* ws://13.218.153.101:* http://localhost:* http://127.0.0.1:* ws://localhost:* ws://127.0.0.1:* ${GATEWAY_URL} ${PLUGIN_SERVICE_URL};
                frame-src https://js.stripe.com https://hooks.stripe.com;
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

            // Extract userId, username, email, and subscription plan from token
            const userId = tokenData.userId || tokenData.sub || 'unknown';
            const username = tokenData.username || tokenData.sub || 'User';
            const userEmail = tokenData.email || '';
            const subscriptionPlan = tokenData.subscriptionPlan || 'free';

            console.log(`[OntoCode] Extracted user info - userId: ${userId}, username: ${username}, email: ${userEmail}, plan: ${subscriptionPlan}`);
            console.log(`[OntoCode] Token data keys:`, Object.keys(tokenData));

            // ALWAYS enable WebSocket for import notifications - required for loading dialog to close
            // WebSocket is needed to receive IMPORT_COMPLETED messages regardless of plan or delployment type
            console.log(`[OntoCode] ✅ WebSocket enabled for import notifications`);

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
            this.collaborationManager = new CollaborationManager(
                OWL_EDITOR_URL,
                userId,
                username,
                async () => (await (this._context as any).secrets.get(TOKEN_KEY)) ?? null,
            );
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

                    // Check if import completed and send fileReady
                    if (status.type === 'IMPORT_COMPLETED') {
                        console.log(`[OntoCode] ✅ Import completed via WebSocket for ${status.projectId}, sending fileReady to webview`);
                        this.postMessage({ type: 'fileReady', projectId: status.projectId });
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

    /**
     * Handle request for Zotero library from webview.
     * @param searchQuery When set, Zotero `/items?q=` quick search scope; otherwise paginate entire library.
     */
    private async handleRequestZoteroLibrary(searchQuery?: string): Promise<void> {
        const trimmed = typeof searchQuery === 'string' && searchQuery.trim() ? searchQuery.trim() : undefined;
        const sid = ++this._zoteroLibrarySessionSeq;

        try {
            console.log('[OntoCode] Handling Zotero library request', trimmed ? `(q="${trimmed}")` : '(full library)');

            // Check if Zotero is configured
            if (!zoteroApiService.isConfigured()) {
                console.log('[OntoCode] Zotero not configured, prompting for credentials');

                // Prompt user to configure Zotero
                const configured = await zoteroApiService.promptForCredentials();

                if (!configured) {
                    // User cancelled configuration
                    console.log('[OntoCode] User cancelled Zotero configuration');
                    this.postMessage({
                        type: 'zoteroLibraryError',
                        error: 'Zotero configuration cancelled. Please configure Zotero to use citations.',
                        librarySessionId: sid
                    });
                    return;
                }
            }

            // Reset paging session and fetch only the first page.
            const PAGE_SIZE = 100;
            this._zoteroPaging = {
                start: 0,
                totalResults: Infinity,
                pageSize: PAGE_SIZE,
                loading: true,
                done: false,
                sessionId: sid,
                ...(trimmed ? { searchQuery: trimmed } : {})
            };

            const { items, totalResults } = await zoteroApiService.fetchLibraryPage(
                0,
                PAGE_SIZE,
                trimmed ? { q: trimmed } : undefined
            );
            this._zoteroPaging.totalResults = totalResults;
            this._zoteroPaging.start = items?.length || 0;
            this._zoteroPaging.loading = false;
            this._zoteroPaging.done = !items || items.length === 0 || this._zoteroPaging.start >= totalResults;

            const knownTotal =
                Number.isFinite(totalResults) && totalResults >= 0 && totalResults < Number.MAX_SAFE_INTEGER
                    ? Math.floor(totalResults)
                    : undefined;

            this.postMessage({
                type: 'zoteroLibraryData',
                items: items || [],
                hasMore: !this._zoteroPaging.done,
                librarySessionId: sid,
                ...(knownTotal !== undefined ? { totalResults: knownTotal, loadedSoFar: this._zoteroPaging.start } : {})
            });

            if (this._zoteroPaging.done) {
                this.postMessage({ type: 'zoteroLibraryDataComplete', librarySessionId: sid });
            }
        } catch (error) {
            console.error('[OntoCode] Failed to load Zotero library:', error);
            this.postMessage({
                type: 'zoteroLibraryError',
                error: error instanceof Error ? error.message : 'Failed to load Zotero library',
                librarySessionId: sid
            });
        }
    }

    /**
     * Fetch the next page of Zotero library items (triggered by webview scroll).
     */
    private async handleRequestZoteroLibraryMore(): Promise<void> {
        try {
            if (!this._zoteroPaging) {
                // If the webview asks for more before initial load, just start fresh.
                await this.handleRequestZoteroLibrary(undefined);
                return;
            }

            if (this._zoteroPaging.done || this._zoteroPaging.loading) {
                return;
            }

            this._zoteroPaging.loading = true;
            const start = this._zoteroPaging.start;
            const pageSize = this._zoteroPaging.pageSize;
            const pq = this._zoteroPaging.searchQuery?.trim();

            const { items, totalResults } = await zoteroApiService.fetchLibraryPage(
                start,
                pageSize,
                pq ? { q: pq } : undefined
            );
            // totalResults can be 0 if header missing; keep the best-known value
            if (Number.isFinite(totalResults) && totalResults > 0) {
                this._zoteroPaging.totalResults = totalResults;
            }

            const got = items?.length || 0;
            this._zoteroPaging.start = start + got;
            this._zoteroPaging.loading = false;

            const done = got === 0 || this._zoteroPaging.start >= this._zoteroPaging.totalResults || got < pageSize;
            this._zoteroPaging.done = done;

            const sidActive = this._zoteroPaging.sessionId;

            if (got > 0) {
                const pt = this._zoteroPaging;
                const knownTotal =
                    pt && Number.isFinite(pt.totalResults) && pt.totalResults < Number.MAX_SAFE_INTEGER
                        ? Math.floor(pt.totalResults)
                        : undefined;
                this.postMessage({
                    type: 'zoteroLibraryDataAppend',
                    items,
                    hasMore: !done,
                    librarySessionId: sidActive,
                    ...(knownTotal !== undefined && pt ? { totalResults: knownTotal, loadedSoFar: pt.start } : {})
                });
            }

            if (done) {
                this.postMessage({ type: 'zoteroLibraryDataComplete', librarySessionId: sidActive });
            }
        } catch (error) {
            const sidErr = this._zoteroPaging?.sessionId;
            if (this._zoteroPaging) this._zoteroPaging.loading = false;
            console.error('[OntoCode] Failed to load more Zotero items:', error);
            this.postMessage({
                type: 'zoteroLibraryError',
                error: error instanceof Error ? error.message : 'Failed to load more Zotero items',
                ...(sidErr !== undefined ? { librarySessionId: sidErr } : {})
            });
        }
    }

    /**
     * Get the stored JWT token from secure storage
     */
    private async getValidJWTToken(): Promise<string | null> {
        try {
            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (token) {
                console.log('[OntoCode] JWT token retrieved from secure storage');
                return token;
            }
            console.log('[OntoCode] No JWT token found in secure storage');
            return null;
        } catch (error) {
            console.error('[OntoCode] Error retrieving JWT token:', error);
            return null;
        }
    }

    /**
     * Handle citation insertion from Zotero
     */
    private async handleInsertCitation(citationKey: string, format: 'turtle' | 'rdfxml', projectId: string, lineNumber: number = 0): Promise<void> {
        try {
            console.log('[OntoCode] Inserting citation:', citationKey);

            // Get formatted citation
            let formattedCitation = await sci2CodeService.formatCitationForOntology(citationKey, format);
            if (!formattedCitation) {
                vscode.window.showErrorMessage('Failed to format citation');
                return;
            }

            // Fix RDF/XML from Sci2Code if it's missing namespace declarations
            if (format === 'rdfxml' && formattedCitation.includes('rdf:Description') && !formattedCitation.includes('<rdf:RDF')) {
                console.log('[OntoCode] Wrapping incomplete RDF/XML with proper namespace declarations');
                formattedCitation = this.wrapRdfXml(formattedCitation);
                console.log('[OntoCode] Wrapped RDF/XML preview:', formattedCitation.substring(0, 500));
            }

            // Get citation metadata
            const metadata = await sci2CodeService.getCitationMetadata(citationKey);

            // Insert citation into backend GraphDB
            let backendSuccess = false;
            try {
                console.log('[OntoCode] Calling backend API:', `${GATEWAY_URL}/api/citations/${projectId}/insert`);

                const response = await axios.post(
                    `${GATEWAY_URL}/api/citations/${projectId}/insert`,
                    {
                        citation: formattedCitation,
                        format: format,
                        metadata: metadata,
                        lineNumber: lineNumber
                    },
                    {
                        headers: {
                            'Content-Type': 'application/json'
                        }
                    }
                );

                console.log('[OntoCode] Citation inserted into GraphDB successfully');
                console.log('[OntoCode] Backend response:', response.data);
                backendSuccess = true;
            } catch (backendError) {
                console.error('[OntoCode] Backend citation insertion failed:', backendError);
                if (axios.isAxiosError(backendError)) {
                    const errorMsg = backendError.response?.data?.error || backendError.message;
                    vscode.window.showErrorMessage(`Failed to insert citation into GraphDB: ${errorMsg}`);
                } else {
                    vscode.window.showErrorMessage(`Failed to insert citation into GraphDB: ${backendError}`);
                }
                throw backendError; // Prevent false success message
            }

            // Send formatted citation back to webview for display
            this.postMessage({
                type: 'citationFormatted',
                citation: formattedCitation,
                metadata: metadata,
                projectId: projectId
            });

            // Update repository files (CITATION.cff, references.bib, CITATIONS.md)
            if (metadata) {
                await this.updateRepositoryCitations(metadata);
            }

            vscode.window.showInformationMessage(`✓ Citation inserted into GraphDB: ${metadata?.title || 'Citation'}`);
        } catch (error) {
            console.error('[OntoCode] Failed to insert citation:', error);
            // Error message already shown in backend catch block
        }
    }

    /**
     * Handle manual citation insertion
     */
    private async handleInsertManualCitation(citation: any, format: 'turtle' | 'rdfxml', projectId: string, lineNumber: number = 0): Promise<void> {
        try {
            console.log('[OntoCode] Inserting manual citation:', citation.title);

            // Format manual citation
            const formattedCitation = sci2CodeService.formatManualCitation(citation, format);

            // Insert citation into backend GraphDB
            let backendSuccess = false;
            try {
                console.log('[OntoCode] Calling backend API:', `${GATEWAY_URL}/api/citations/${projectId}/insert`);
                console.log('[OntoCode] Citation content preview:', formattedCitation.substring(0, 200));

                const response = await axios.post(
                    `${GATEWAY_URL}/api/citations/${projectId}/insert`,
                    {
                        citation: formattedCitation,
                        format: format,
                        metadata: citation,
                        lineNumber: lineNumber
                    },
                    {
                        headers: {
                            'Content-Type': 'application/json'
                        }
                    }
                );

                console.log('[OntoCode] Manual citation inserted into GraphDB successfully');
                console.log('[OntoCode] Backend response:', response.data);
                backendSuccess = true;
            } catch (backendError) {
                console.error('[OntoCode] Backend citation insertion failed:', backendError);
                if (axios.isAxiosError(backendError)) {
                    const errorMsg = backendError.response?.data?.error || backendError.message;
                    vscode.window.showErrorMessage(`Failed to insert citation into GraphDB: ${errorMsg}`);
                } else {
                    vscode.window.showErrorMessage(`Failed to insert citation into GraphDB: ${backendError}`);
                }
                throw backendError; // Prevent false success message
            }

            // Send to webview for display
            this.postMessage({
                type: 'citationFormatted',
                citation: formattedCitation,
                metadata: citation,
                projectId: projectId
            });

            // Update repository files
            await this.updateRepositoryCitations(citation);

            vscode.window.showInformationMessage(`✓ Citation inserted into GraphDB: ${citation.title}`);
        } catch (error) {
            console.error('[OntoCode] Failed to insert manual citation:', error);
            // Error message already shown in backend catch block
        }
    }

    /**
     * Handle direct citation insertion to GraphDB (for persistence across format changes)
     * This is called when user inserts citation at a specific line in the code view
     */
    private async handleInsertCitationToGraphDB(citation: string, format: string, projectId: string, metadata: any): Promise<void> {
        try {
            const url = `${GATEWAY_URL}/api/citations/${projectId}/insert`;
            console.log('[OntoCode] Inserting citation directly to GraphDB for persistence');
            console.log('[OntoCode] Gateway URL:', GATEWAY_URL);
            console.log('[OntoCode] Full URL:', url);
            console.log('[OntoCode] Project:', projectId);
            console.log('[OntoCode] Citation preview:', citation.substring(0, 300));

            const response = await axios.post(
                url,
                {
                    citation: citation,
                    format: format,
                    metadata: metadata,
                    lineNumber: 0 // Line number handled by file upload, this is just for triple storage
                },
                {
                    headers: {
                        'Content-Type': 'application/json'
                    }
                }
            );

            console.log('[OntoCode] Citation triples inserted into GraphDB successfully');
            console.log('[OntoCode] Backend response:', response.data);

        } catch (error) {
            console.error('[OntoCode] Failed to insert citation to GraphDB:', error);
            if (axios.isAxiosError(error)) {
                console.error('[OntoCode] HTTP Status:', error.response?.status);
                console.error('[OntoCode] Response:', error.response?.data);
                const errorMsg = error.response?.data?.error || error.message;
                console.error('[OntoCode] GraphDB insertion error:', errorMsg);

                // Show user-friendly error if backend is not reachable
                if (error.code === 'ECONNREFUSED' || error.response?.status === 404) {
                    vscode.window.showWarningMessage(
                        'Citation backend not available. Make sure all services are running (Gateway on port 80 or 8080, Editor on port 8083).',
                        'Check Logs'
                    );
                }
            }
        }
    }

    /**
     * Handle removing a citation from GraphDB
     * Called when user removes a citation from the code view
     */
    private async handleRemoveCitationFromGraphDB(citationUri: string, projectId: string): Promise<void> {
        try {
            console.log('[OntoCode] Removing citation from GraphDB');
            console.log('[OntoCode] Project:', projectId);
            console.log('[OntoCode] Citation URI:', citationUri);

            // The citationUri is already the full URI (urn:citation:xxx), URL-encode for path parameter
            const encodedCitationUri = encodeURIComponent(citationUri);

            const response = await axios.delete(
                `${GATEWAY_URL}/api/citations/${projectId}/${encodedCitationUri}`,
                {
                    headers: {
                        'Content-Type': 'application/json'
                    }
                }
            );

            console.log('[OntoCode] Citation removed from GraphDB successfully');
            console.log('[OntoCode] Backend response:', response.data);

        } catch (error) {
            console.error('[OntoCode] Failed to remove citation from GraphDB:', error);
            if (axios.isAxiosError(error)) {
                const errorMsg = error.response?.data?.error || error.message;
                console.error('[OntoCode] GraphDB removal error:', errorMsg);
                // Don't show error to user since file update may have succeeded
            }
        }
    }

    /**
     * Handle uploading modified ontology content back to the backend
     */
    private async handleUploadOntologyContent(content: string, format: string, projectId: string): Promise<void> {
        try {
            console.log('[OntoCode] Uploading modified ontology content for project:', projectId);
            console.log('[OntoCode] Content length:', content.length, 'bytes');
            console.log('[OntoCode] First 200 chars:', content.substring(0, 200));

            // Clean content - remove BOM and leading/trailing whitespace that could break XML parsing
            // let cleanedContent = content;

            // // Remove BOM (Byte Order Mark) if present
            // if (cleanedContent.charCodeAt(0) === 0xFEFF) {
            //     cleanedContent = cleanedContent.substring(1);
            //     console.log('[OntoCode] Removed BOM from content');
            // }

            // // For RDF/XML format, ensure no leading whitespace before XML declaration
            // if (format === 'rdfxml') {
            //     cleanedContent = cleanedContent.trimStart();
            //     console.log('[OntoCode] Trimmed leading whitespace for RDF/XML format');
            // }

            // console.log('[OntoCode] Cleaned content - First 200 chars:', cleanedContent.substring(0, 200));

            // Get the JWT token for authorization
            const token = await this.getValidJWTToken();
            if (!token) {
                vscode.window.showErrorMessage('Not authenticated. Please log in first.');
                this.postMessage({ type: 'uploadOntologyContentDone', success: false, projectId });
                return;
            }

            // Use Node.js fs to write content to a temporary file
            const path = require('path');
            const fs = require('fs');
            const os = require('os');

            // Create temp file
            const tmpDir = os.tmpdir();
            const fileExtension = format === 'turtle' ? 'ttl' : 'rdf';
            const tempFileName = `ontology_${projectId}_${Date.now()}.${fileExtension}`;
            const tempFilePath = path.join(tmpDir, tempFileName);

            // Write cleaned content to temp file (UTF-8 without BOM)
            // fs.writeFileSync(tempFilePath, cleanedContent, { encoding: 'utf8', flag: 'w' });
            fs.writeFileSync(tempFilePath, content, 'utf8');
            console.log('[OntoCode] Temp file created:', tempFilePath);

            // Read the file as stream for upload
            const fileStream = fs.createReadStream(tempFilePath);
            const fileSizeBytes = fs.statSync(tempFilePath).size;

            // Create FormData using form-data package
            const FormData = require('form-data');
            const formData = new FormData();
            formData.append('file', fileStream, tempFileName);

            // Upload via the ontology upload endpoint
            const uploadUrl = `${GATEWAY_URL}/api/ontology/upload/${projectId}`;
            console.log('[OntoCode] Uploading to:', uploadUrl, 'File size:', fileSizeBytes, 'bytes');

            const headers = {
                'Authorization': `Bearer ${token}`,
                ...formData.getHeaders()
            };

            const response = await axios.post(
                uploadUrl,
                formData,
                {
                    headers: headers,
                    timeout: 120000,
                    maxContentLength: Infinity,
                    maxBodyLength: Infinity
                }
            );

            console.log('[OntoCode] Ontology content uploaded successfully');
            console.log('[OntoCode] Backend response status:', response.status);
            console.log('[OntoCode] Backend response data:', JSON.stringify(response.data).substring(0, 200));

            vscode.window.showInformationMessage('✓ Citation marker saved at specified line');

            // Send completion message back to webview
            this.postMessage({
                type: 'uploadOntologyContentDone',
                success: true,
                projectId: projectId
            });

            // Clean up temp file
            try {
                fs.unlinkSync(tempFilePath);
                console.log('[OntoCode] Temp file cleaned up');
            } catch (cleanupError) {
                console.warn('[OntoCode] Failed to cleanup temp file:', cleanupError);
            }

        } catch (error) {
            console.error('[OntoCode] Failed to upload ontology content:', error);

            if (axios.isAxiosError(error)) {
                const errorMsg = error.response?.data?.error || error.response?.data?.message || error.message;
                console.error('[OntoCode] Axios error details:', {
                    status: error.response?.status,
                    message: errorMsg,
                    data: error.response?.data
                });
                vscode.window.showErrorMessage(`Failed to save citation marker: ${errorMsg}`);
            } else if (error instanceof Error) {
                console.error('[OntoCode] Error message:', error.message);
                vscode.window.showErrorMessage(`Failed to save citation marker: ${error.message}`);
            } else {
                vscode.window.showErrorMessage(`Failed to save citation marker: Unknown error`);
            }

            this.postMessage({
                type: 'uploadOntologyContentDone',
                success: false,
                projectId: projectId
            });
        }
    }

    /**
     * Wrap incomplete RDF/XML fragments with proper namespace declarations
     */
    private wrapRdfXml(rdfFragment: string): string {
        // Remove XML declaration if present
        let fragment = rdfFragment.replace(/<\?xml[^?]*\?>\s*/, '');

        // Check if there's already an rdf:RDF root element
        const rdfRootMatch = fragment.match(/<rdf:RDF([^>]*)>/i);

        if (rdfRootMatch) {
            // Extract existing namespaces from the root element
            const existingAttrs = rdfRootMatch[1];

            // Define all well-known namespaces (synced with OWLFormatConverter.java)
            const requiredNamespaces: Record<string, string> = {
                'xmlns:rdf': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
                'xmlns:rdfs': 'http://www.w3.org/2000/01/rdf-schema#',
                'xmlns:owl': 'http://www.w3.org/2002/07/owl#',
                'xmlns:xsd': 'http://www.w3.org/2001/XMLSchema#',
                'xmlns:dc': 'http://purl.org/dc/elements/1.1/',
                'xmlns:dcterms': 'http://purl.org/dc/terms/',
                'xmlns:terms': 'http://purl.org/dc/terms/',
                'xmlns:bibo': 'http://purl.org/ontology/bibo/',
                'xmlns:foaf': 'http://xmlns.com/foaf/0.1/',
                'xmlns:skos': 'http://www.w3.org/2004/02/skos/core#',
                'xmlns:prov': 'http://www.w3.org/ns/prov#',
                'xmlns:schema': 'http://schema.org/',
                'xmlns:vann': 'http://purl.org/vocab/vann/',
                'xmlns:cc': 'http://creativecommons.org/ns#',
                'xmlns:doap': 'http://usefulinc.com/ns/doap#',
                'xmlns:obo': 'http://purl.obolibrary.org/obo/',
                'xmlns:oboInOwl': 'http://www.geneontology.org/formats/oboInOwl#',
                'xmlns:swrl': 'http://www.w3.org/2003/11/swrl#',
                'xmlns:swrlb': 'http://www.w3.org/2003/11/swrlb#',
                'xmlns:sio': 'http://semanticscience.org/resource/',
                'xmlns:sh': 'http://www.w3.org/ns/shacl#',
                'xmlns:dcat': 'http://www.w3.org/ns/dcat#',
                'xmlns:void': 'http://rdfs.org/ns/void#',
            };

            // Build new attributes string with all required namespaces
            let newAttrs = '';
            for (const [prefix, uri] of Object.entries(requiredNamespaces)) {
                // Check if this namespace is already declared (case-insensitive)
                const prefixPattern = new RegExp(prefix.replace(':', '\\:'), 'i');
                if (!prefixPattern.test(existingAttrs)) {
                    newAttrs += `\n         ${prefix}="${uri}"`;
                }
            }

            // ── Dynamic resolution for custom/unknown prefixes ──
            const allDeclaredPrefixes = new Set<string>();
            // Collect already-declared prefixes from existing attrs + known list
            const declaredMatch = (existingAttrs + newAttrs).matchAll(/xmlns:([a-zA-Z][a-zA-Z0-9_-]*)\s*=/gi);
            for (const m of declaredMatch) allDeclaredPrefixes.add(m[1]);

            // Scan content for all used prefixes
            const usedPrefixRegex = /(?:<|\s)([a-zA-Z][a-zA-Z0-9_-]*):[a-zA-Z]/g;
            let usedMatch;
            const undeclaredPrefixes = new Set<string>();
            while ((usedMatch = usedPrefixRegex.exec(fragment)) !== null) {
                const p = usedMatch[1];
                if (p === 'xmlns' || p === 'xml') continue;
                if (!allDeclaredPrefixes.has(p)) undeclaredPrefixes.add(p);
            }

            if (undeclaredPrefixes.size > 0) {
                // Extract xml:base, ontology IRI, or default namespace for fallback
                const xmlBaseMatch = fragment.match(/xml:base\s*=\s*"([^"]+)"/);
                const ontologyMatch = fragment.match(/<owl:Ontology\s+rdf:about\s*=\s*"([^"]+)"/);
                const defaultNsMatch = fragment.match(/<rdf:RDF[^>]*\sxmlns\s*=\s*"([^"]+)"/);
                const xmlBase = xmlBaseMatch?.[1];
                const ontologyIri = ontologyMatch?.[1];
                const defaultNs = defaultNsMatch?.[1];

                for (const prefix of undeclaredPrefixes) {
                    let resolvedUri: string | null = null;

                    // Strategy A: Match prefix:LocalName against full URIs in rdf:about/resource
                    const localNameRegex = new RegExp(`(?:<|\\s)${prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}:([a-zA-Z][a-zA-Z0-9_.-]*)`, 'g');
                    const localNames: string[] = [];
                    let lnMatch;
                    while ((lnMatch = localNameRegex.exec(fragment)) !== null) {
                        if (!localNames.includes(lnMatch[1])) localNames.push(lnMatch[1]);
                    }
                    for (const localName of localNames) {
                        const escaped = localName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
                        const uriRegex = new RegExp(`(?:rdf:about|rdf:resource|rdf:datatype)\\s*=\\s*"([^"]+[#/])${escaped}"`, 'i');
                        const uriMatch = fragment.match(uriRegex);
                        if (uriMatch) {
                            resolvedUri = uriMatch[1];
                            break;
                        }
                    }

                    // Strategy B: Derive from xml:base / ontology IRI / default namespace
                    if (!resolvedUri) {
                        const base = xmlBase || ontologyIri || defaultNs;
                        if (base) {
                            resolvedUri = base.endsWith('#') || base.endsWith('/') ? base : base + '#';
                        }
                    }

                    if (resolvedUri) {
                        newAttrs += `\n         xmlns:${prefix}="${resolvedUri}"`;
                        console.log(`[wrapRdfXml] Dynamically resolved custom prefix '${prefix}' → '${resolvedUri}'`);
                    } else {
                        console.warn(`[wrapRdfXml] Cannot resolve custom prefix '${prefix}' — no matching URIs or document base found`);
                    }
                }
            }

            // Replace the opening tag with the enhanced version
            const enhancedRoot = `<rdf:RDF${existingAttrs}${newAttrs}>`;
            fragment = fragment.replace(/<rdf:RDF[^>]*>/i, enhancedRoot);

            // Add XML declaration if not present
            if (!/<\?xml/i.test(rdfFragment)) {
                fragment = `<?xml version="1.0"?>\n` + fragment;
            }

            return fragment;
        } else {
            // No rdf:RDF root found, wrap the entire fragment with all well-known namespaces
            const defaultNs: Record<string, string> = {
                'xmlns:rdf': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
                'xmlns:rdfs': 'http://www.w3.org/2000/01/rdf-schema#',
                'xmlns:owl': 'http://www.w3.org/2002/07/owl#',
                'xmlns:xsd': 'http://www.w3.org/2001/XMLSchema#',
                'xmlns:dc': 'http://purl.org/dc/elements/1.1/',
                'xmlns:dcterms': 'http://purl.org/dc/terms/',
                'xmlns:bibo': 'http://purl.org/ontology/bibo/',
                'xmlns:foaf': 'http://xmlns.com/foaf/0.1/',
                'xmlns:skos': 'http://www.w3.org/2004/02/skos/core#',
                'xmlns:prov': 'http://www.w3.org/ns/prov#',
                'xmlns:obo': 'http://purl.obolibrary.org/obo/',
                'xmlns:oboInOwl': 'http://www.geneontology.org/formats/oboInOwl#',
            };

            // ── Dynamic resolution for custom prefixes in bare fragments ──
            const declaredPrefixNames = new Set(Object.keys(defaultNs).map(k => k.replace('xmlns:', '')));
            const usedPrefixRegex = /(?:<|\s)([a-zA-Z][a-zA-Z0-9_-]*):[a-zA-Z]/g;
            let usedMatch;
            while ((usedMatch = usedPrefixRegex.exec(fragment)) !== null) {
                const p = usedMatch[1];
                if (p === 'xmlns' || p === 'xml' || declaredPrefixNames.has(p)) continue;

                // Already resolved this prefix
                if (defaultNs[`xmlns:${p}`]) continue;

                let resolvedUri: string | null = null;

                // Strategy A: Match against full URIs in the content
                const localNameRegex = new RegExp(`(?:<|\\s)${p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}:([a-zA-Z][a-zA-Z0-9_.-]*)`, 'g');
                const localNames: string[] = [];
                let lnMatch;
                while ((lnMatch = localNameRegex.exec(fragment)) !== null) {
                    if (!localNames.includes(lnMatch[1])) localNames.push(lnMatch[1]);
                }
                for (const localName of localNames) {
                    const escaped = localName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
                    const uriRegex = new RegExp(`(?:rdf:about|rdf:resource|rdf:datatype)\\s*=\\s*"([^"]+[#/])${escaped}"`, 'i');
                    const uriMatch = fragment.match(uriRegex);
                    if (uriMatch) {
                        resolvedUri = uriMatch[1];
                        break;
                    }
                }

                if (resolvedUri) {
                    defaultNs[`xmlns:${p}`] = resolvedUri;
                    console.log(`[wrapRdfXml] Dynamically resolved custom prefix '${p}' → '${resolvedUri}'`);
                } else {
                    console.warn(`[wrapRdfXml] Cannot resolve custom prefix '${p}' in bare fragment`);
                }
            }

            let wrappedRdf = `<?xml version="1.0"?>\n`;
            wrappedRdf += `<rdf:RDF`;
            for (const [prefix, uri] of Object.entries(defaultNs)) {
                wrappedRdf += `\n         ${prefix}="${uri}"`;
            }
            wrappedRdf += `>\n\n`;
            wrappedRdf += fragment.trim() + '\n';
            wrappedRdf += `</rdf:RDF>`;

            return wrappedRdf;
        }
    }

    /**
     * Update repository citation files (CITATION.cff, references.bib, CITATIONS.md)
     */
    private async updateRepositoryCitations(citation: any): Promise<void> {
        try {
            const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
            if (!workspaceFolder) {
                console.log('[OntoCode] No workspace folder for citation files');
                return;
            }

            // Update references.bib
            const bibPath = vscode.Uri.joinPath(workspaceFolder.uri, 'references.bib');
            const bibSnippet = sci2CodeService.convertToBibTeX(citation);

            let bibContent = '';
            try {
                const bibData = await vscode.workspace.fs.readFile(bibPath);
                bibContent = new TextDecoder('utf-8').decode(bibData);
            } catch (e) {
                // File doesn't exist, create new content
            }

            if (!bibContent.includes(citation.title)) {
                bibContent += '\n' + bibSnippet + '\n';
                await vscode.workspace.fs.writeFile(bibPath, new TextEncoder().encode(bibContent));
                console.log('[OntoCode] Updated references.bib');
            }

            // Update CITATION.cff
            const cffPath = vscode.Uri.joinPath(workspaceFolder.uri, 'CITATION.cff');
            const cffRef = sci2CodeService.convertToCFFReference(citation);

            let cffContent = '';
            try {
                const cffData = await vscode.workspace.fs.readFile(cffPath);
                cffContent = new TextDecoder('utf-8').decode(cffData);
            } catch (e) {
                // File doesn't exist
            }

            if (cffContent && !cffContent.includes(citation.title)) {
                if (!cffContent.includes('references:')) {
                    cffContent += '\nreferences:\n';
                }

                const refString = `  - type: ${cffRef.type}\n` +
                    `    title: "${cffRef.title}"\n` +
                    `    authors:\n` +
                    cffRef.authors.map((a: any) =>
                        `      - family-names: "${a['family-names']}"\n        given-names: "${a['given-names']}"\n`
                    ).join('') +
                    `    year: ${cffRef.year}\n` +
                    (cffRef.doi ? `    doi: "${cffRef.doi}"\n` : '') +
                    (cffRef.url ? `    url: "${cffRef.url}"\n` : '');

                cffContent += refString;
                await vscode.workspace.fs.writeFile(cffPath, new TextEncoder().encode(cffContent));
                console.log('[OntoCode] Updated CITATION.cff');
            }

        } catch (error) {
            console.error('[OntoCode] Error updating repository citations:', error);
            // Don't fail the whole operation
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
