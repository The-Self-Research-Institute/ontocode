const pd = (require('pretty-data') as any).pd;
import { XMLParser, XMLBuilder } from 'fast-xml-parser';
// Fix: Removed triple-slash directive for node types as we are removing node-specific dependencies.

import * as vscode from 'vscode';
import * as path from 'path';
// Fix: Removed 'fs' import to use VSCode's API and web standards.
import FormData from 'form-data';
import axios, { AxiosError } from 'axios';
import { insertCitationCommand } from './features/citationInsertion';
import { CitationPickerPanel } from './webview/citationPicker';

const TOKEN_KEY = 'ontocode.authToken';
const GATEWAY_URL = 'http://localhost:8082'; // Gateway port
type FileReference = vscode.TextEditor | vscode.TextDocument;
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
    | { type: 'apiPut'; requestId: string; url: string; body?: unknown }
    | { type: 'apiDelete'; requestId: string; url: string; params?: Record<string, unknown> }
    | { type: 'webviewReady' }
    | { type: 'downloadOntology'; url: string; filename: string }
    | { type: 'downloadCurrentOntology' }
    | { type: 'fileLoaded'; projectId: string }
    | { type: 'triggerFileUpload'; content: string }
    | { type: 'downloadAndSaveToLocal'; projectId: string };


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
    private _currentProjectFileUri: vscode.Uri | null = null; // Track current file for saves

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
                    case "downloadOntology":
                        console.log("[OntoCode] Received download Ontology message.", message);
                        vscode.window.showInformationMessage("Download Initiated...");
                        this.downloadOntologyToSaveAs(message.url, message.filename);
                        break;
                    case 'triggerFileUpload':
                        console.log('[OntoCode] Received triggerFileUpload message from webview.');
                        await this.saveCurrentEditorToFile(message.content);
                        break;
                    case 'downloadAndSaveToLocal':
                        console.log('[OntoCode] Received downloadAndSaveToLocal message from webview.');
                        await this.downloadAndSaveToLocalFile(message.projectId);
                        break;
                    case "fileLoaded":
                        console.log("[OntoCode] Received fileLoaded message.", message);
                        this.postMessage({ type: 'fileReady', projectId: message.projectId });
                        break;
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
                    case 'apiPut':
                    case 'apiDelete':
                        this.handleApiRequest(message as Extract<ExtensionMessage, { type: 'apiGet' | 'apiPost' | 'apiPut' | 'apiDelete' }>);
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

    private async saveCurrentEditorToFile(updatedContent?: string) {
        try {
            // Step 1: Get target file
            const targetUri = await this.getTargetFileUri();
            if (!targetUri) return;

            // Step 2: Get content to save
            const content = await this.getContentToSave(updatedContent, targetUri);
            if (!content) return;

            // Step 3: Determine if JSON from webview needs merging
            const isJsonFromWebview = updatedContent?.trim().startsWith('{');
            
            if (isJsonFromWebview) {
                // Merge JSON into existing OWL
                await this.mergeJsonToOwl(content, targetUri);
            } else {
                // Direct save (RDF/XML or plain editor content)
                await this.saveDirectly(content, targetUri);
            }
        } catch (error: any) {
            console.error('[OntoCode] Save error:', error);
            vscode.window.showErrorMessage(`Save failed: ${error.message}`);
        }
    }

    private async getTargetFileUri(): Promise<vscode.Uri | null> {
        const editor = vscode.window.activeTextEditor;
        
        if (editor?.document.fileName.toLowerCase().endsWith('.owl')) {
            return editor.document.uri;
        }
        
        // Use stored current project file URI
        if (this._currentProjectFileUri) {
            console.log('[OntoCode] Using stored project file URI:', this._currentProjectFileUri.fsPath);
            return this._currentProjectFileUri;
        }
        
        if (this._pendingFileUri) {
            return this._pendingFileUri;
        }
        
        const picked = await vscode.window.showOpenDialog({
            canSelectMany: false,
            openLabel: 'Select OWL file',
            filters: { 'OWL Files': ['owl'] }
        });
        
        return picked?.[0] || null;
    }

    private async getContentToSave(webviewContent: string | undefined, targetUri: vscode.Uri): Promise<string | null> {
        if (webviewContent?.trim()) {
            console.log('[OntoCode] Using webview content');
            return webviewContent;
        }
        
        const editor = vscode.window.activeTextEditor;
        if (editor?.document.uri.toString() === targetUri.toString()) {
            console.log('[OntoCode] Using editor content');
            return editor.document.getText();
        }
        
        console.log('[OntoCode] Reading from disk');
        const bytes = await vscode.workspace.fs.readFile(targetUri);
        return new TextDecoder('utf-8').decode(bytes);
    }

    private async saveDirectly(content: string, uri: vscode.Uri) {
        await vscode.workspace.fs.writeFile(uri, new TextEncoder().encode(content));
        console.log('[OntoCode] File saved directly');
        vscode.window.showInformationMessage('File saved.');
    }

    private async mergeJsonToOwl(jsonContent: string, targetUri: vscode.Uri) {
        console.log('[OntoCode] Merging JSON into OWL file');
        
        const data = JSON.parse(jsonContent);
        
        // Read existing OWL file
        const owlBytes = await vscode.workspace.fs.readFile(targetUri);
        const owlXml = new TextDecoder('utf-8').decode(owlBytes);
        
        // Parse OWL XML
        const parser = new XMLParser({
            ignoreAttributes: false,
            attributeNamePrefix: '@_',
            parseTagValue: false,
            processEntities: true
        });
        
        const owlDoc = parser.parse(owlXml);
        const rdf = owlDoc['rdf:RDF'];
        
        if (!rdf) {
            throw new Error('Invalid OWL file - missing rdf:RDF');
        }
        
        // Get classes array
        let owlClasses = rdf['owl:Class'];
        if (!owlClasses) {
            owlClasses = [];
        } else if (!Array.isArray(owlClasses)) {
            owlClasses = [owlClasses];
            rdf['owl:Class'] = owlClasses;
        }
        
        console.log(`[OntoCode] Processing ${owlClasses.length} OWL classes`);
        
        // Extract classes from JSON (handle nested structure)
        const jsonClasses = this.extractClassesFromJson(data);
        console.log(`[OntoCode] Found ${jsonClasses.length} classes in JSON`);
        
        let updateCount = 0;
        
        for (const jsonClass of jsonClasses) {
            const iri = jsonClass.id;
            
            // Skip OWL built-ins
            if (!iri || iri.includes('owl#Thing') || iri.includes('rdf-syntax-ns#')) {
                continue;
            }
            
            // Find matching OWL class
            const owlClass = owlClasses.find((c: any) => c['@_rdf:about'] === iri);
            
            if (!owlClass) {
                console.log(`[OntoCode] Class not in OWL: ${iri}`);
                continue;
            }
            
            console.log(`[OntoCode] Updating: ${jsonClass.label || iri}`);
            
            // Update label
            if (jsonClass.label && owlClass['rdfs:label'] !== jsonClass.label) {
                owlClass['rdfs:label'] = jsonClass.label;
                updateCount++;
                console.log(`[OntoCode]   label → ${jsonClass.label}`);
            }
            
            // Update annotations
            if (jsonClass.annotations && typeof jsonClass.annotations === 'object') {
                for (const [key, value] of Object.entries(jsonClass.annotations)) {
                    if (!value || value === '') continue;
                    
                    const fullKey = key.includes(':') ? key : `untitled-ontology-55:${key}`;
                    const current = owlClass[fullKey];
                    
                    if (current !== value) {
                        owlClass[fullKey] = value;
                        updateCount++;
                        console.log(`[OntoCode]   ${key} → ${String(value).substring(0, 40)}`);
                    }
                }
            }
        }
        
        console.log(`[OntoCode] Applied changes`);
        
        // Build XML
        const builder = new XMLBuilder({
            ignoreAttributes: false,
            attributeNamePrefix: '@_',
            format: true,
            indentBy: '  '
        });
        
        const xml = builder.build(owlDoc);
        const prettyXml = pd.xml(xml);
        
        // Write file
        await vscode.workspace.fs.writeFile(targetUri, new TextEncoder().encode(prettyXml));
        vscode.window.showInformationMessage(`Saved with changes.`);
    }

    private extractClassesFromJson(data: any): any[] {
        const classes: any[] = [];
        
        // Handle nested "classes" property
        if (data.classes && Array.isArray(data.classes)) {
            for (const item of data.classes) {
                if (item.classes && Array.isArray(item.classes)) {
                    // Nested structure like classes[0].classes[0].children
                    for (const nestedClass of item.classes) {
                        classes.push(nestedClass);
                        if (nestedClass.children) {
                            classes.push(...this.flattenChildren(nestedClass.children));
                        }
                    }
                } else {
                    // Direct class item
                    classes.push(item);
                    if (item.children) {
                        classes.push(...this.flattenChildren(item.children));
                    }
                }
            }
        }
        
        return classes;
    }

    private flattenChildren(children: any[]): any[] {
        const result: any[] = [];
        for (const child of children) {
            result.push(child);
            if (child.children && Array.isArray(child.children)) {
                result.push(...this.flattenChildren(child.children));
            }
        }
        return result;
    }


    /**
     * Fix: New method to handle API requests from the webview, acting as a proxy.
     * This centralizes API calls, attaches auth tokens, and bypasses CORS issues.
     */
    private async handleApiRequest(message: Extract<ExtensionMessage, { type: 'apiGet' | 'apiPost' | 'apiPut' | 'apiDelete' }>) {
        const { requestId, type, url } = message;
        // Fix: Cast context to `any` to access the `secrets` property, bypassing outdated type definitions.
        const token = await (this._context as any).secrets.get(TOKEN_KEY);
        // Do not proceed if unauthenticated, unless it's a login/signup endpoint
        // For simplicity, we assume all proxied requests need a token.
        if (!token) {
            this.postMessage({ type: 'apiResponse', requestId, error: { message: 'User is not authenticated.', status: 401 } });
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
                case 'apiPut':
                    response = await axios.put(fullUrl, message.body, { headers });
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
        this._currentProjectFileUri = fileUri; // Store for later saves
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

        this._currentProjectFileUri = targetEditor.document.uri; // Store for later saves
        const fileContent = targetEditor.document.getText();
        // Fix: Replaced path.basename with string manipulation on the URI path.
        const fileName = targetEditor.document.uri.path.substring(targetEditor.document.uri.path.lastIndexOf('/') + 1);
        // Fix: Replaced Buffer.from with TextEncoder to produce a Uint8Array, avoiding Node.js globals.
        const fileBuffer = new TextEncoder().encode(fileContent);
        const projectId = fileName.endsWith('.owl') ? fileName.slice(0, -4) : fileName;

        // Delegate to the shared upload logic
        this._uploadOntology(projectId, fileName, fileBuffer);
    }



    public async downloadOntologyToSaveAs(url: string, suggestedName = "ontology.owl") {
        try {
            const saveUri = await vscode.window.showSaveDialog({
                saveLabel: "Save OWL",
                defaultUri: vscode.Uri.file(suggestedName),
                filters: { "OWL/RDF": ["owl", "rdf", "xml", "ttl"] },
            });
            if (!saveUri) return;

            const fullUrl = url.startsWith("http") ? url : `${GATEWAY_URL}${url}`;
            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {};

            await vscode.window.withProgress(
                { location: vscode.ProgressLocation.Notification, title: "Downloading ontology…" },
                async () => {
                    const res = await axios.get(fullUrl, { responseType: "arraybuffer", headers });
                    await vscode.workspace.fs.writeFile(saveUri, new Uint8Array(res.data));
                }
            );

            vscode.window.showInformationMessage(`file saved to ${saveUri.fsPath}`);
        } catch (err) {
            console.error("[OntoCode] Download failed:", err);
            vscode.window.showErrorMessage("Failed to download ontology");
        }
    }

    /**
     * Download the regenerated OWL file from the server and save it to the local file
     */
    private async downloadAndSaveToLocalFile(projectId: string) {
        try {
            console.log(`[OntoCode] Downloading regenerated file for project: ${projectId}`);
            
            // Download the file from the server first
            const token = await (this._context as any).secrets.get(TOKEN_KEY);
            if (!token) {
                console.error('[OntoCode] No authentication token found');
                return;
            }
            
            const downloadUrl = `${GATEWAY_URL}/api/ontology/download/${projectId}`;
            console.log(`[OntoCode] Downloading from: ${downloadUrl}`);
            
            const headers = { 'Authorization': `Bearer ${token}` };
            const response = await axios.get(downloadUrl, { 
                responseType: 'arraybuffer',
                headers 
            });
            
            console.log(`[OntoCode] Downloaded ${response.data.byteLength} bytes`);
            
            // Get the target file URI
            let targetUri = this._currentProjectFileUri;
            
            // If no stored URI, try to find an open .owl file
            if (!targetUri) {
                const editor = vscode.window.activeTextEditor;
                if (editor?.document.fileName.toLowerCase().endsWith('.owl')) {
                    targetUri = editor.document.uri;
                    console.log('[OntoCode] Using active editor file:', targetUri.fsPath);
                }
            }
            
            // If still no URI, prompt user
            if (!targetUri) {
                console.log('[OntoCode] No file URI found, prompting user');
                const picked = await vscode.window.showSaveDialog({
                    defaultUri: vscode.Uri.file(`${projectId}.owl`),
                    filters: { 'OWL Files': ['owl'] },
                    saveLabel: 'Save Ontology'
                });
                if (!picked) {
                    console.log('[OntoCode] User cancelled save dialog');
                    return;
                }
                targetUri = picked;
            }
            
            // Check if file exists and find next available version number
            const originalPath = targetUri.fsPath;
            const dir = path.dirname(originalPath);
            const ext = path.extname(originalPath);
            const basename = path.basename(originalPath, ext);
            
            let finalUri = targetUri;
            let version = 1;
            
            try {
                await vscode.workspace.fs.stat(targetUri);
                // File exists, need to find next version
                console.log(`[OntoCode] File exists: ${originalPath}, finding next version...`);
                
                while (true) {
                    const versionedPath = path.join(dir, `${basename} (${version})${ext}`);
                    const versionedUri = vscode.Uri.file(versionedPath);
                    
                    try {
                        await vscode.workspace.fs.stat(versionedUri);
                        // This version exists, try next
                        version++;
                    } catch {
                        // This version doesn't exist, use it
                        finalUri = versionedUri;
                        console.log(`[OntoCode] Will save as version ${version}: ${versionedPath}`);
                        break;
                    }
                }
            } catch {
                // File doesn't exist, use original path
                console.log(`[OntoCode] File doesn't exist yet, using original path`);
            }
            
            // Save to the target file
            await vscode.workspace.fs.writeFile(finalUri, new Uint8Array(response.data));
            console.log(`[OntoCode] File saved successfully to: ${finalUri.fsPath}`);
            
            // Show success message with file path
            vscode.window.showInformationMessage(`Ontology saved to: ${path.basename(finalUri.fsPath)}`);
            // vscode.window.showInformationMessage(`Saved with ${response.data.byteLength} bytes to ${targetUri.fsPath.split(/[\\/]/).pop()}`);
            
        } catch (error: any) {
            console.error('[OntoCode] Download and save error:', error);
            if (error.response) {
                console.error('[OntoCode] Response status:', error.response.status);
                console.error('[OntoCode] Response data:', error.response.data);
            }
            vscode.window.showErrorMessage(`Failed to save local file: ${error.message}`);
        }
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
                vscode.window.showInformationMessage(`Ontology "${fileName}" Opened successfully. Processing started...`);
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