import * as vscode from 'vscode';
import axios from 'axios';

interface ZoteroConfig {
    apiKey: string;
    userId: string;
    libraryType: 'user' | 'group';
    groupId?: string;
}

export interface ZoteroItem {
    key: string;
    version: number;
    data: {
        key: string;
        version: number;
        itemType: string;
        title: string;
        creators: Array<{
            creatorType: string;
            firstName: string;
            lastName: string;
        }>;
        abstractNote?: string;
        date?: string;
        /** Zotero canonical name. Lowercase variants exist in older translators. */
        DOI?: string;
        /** Free-text field; users often stash "DOI: 10.x/y", "PMID: ..." etc. here. */
        extra?: string;
        url?: string;
        publicationTitle?: string;
        volume?: string;
        issue?: string;
        pages?: string;
        publisher?: string;
        tags?: Array<{ tag: string }>;
    };
}

class ZoteroApiService {
    private baseUrl = 'https://api.zotero.org';

    /**
     * Get Zotero configuration from VS Code settings
     */
    private getConfig(): ZoteroConfig | null {
        const config = vscode.workspace.getConfiguration('ontocode.zotero');
        const apiKey = config.get<string>('apiKey', '');
        const userId = config.get<string>('userId', '');
        const libraryType = config.get<'user' | 'group'>('libraryType', 'user');
        const groupId = config.get<string>('groupId', '');

        if (!apiKey || !userId) {
            console.log('[ZoteroAPI] API key or user ID not configured');
            return null;
        }

        return { apiKey, userId, libraryType, groupId };
    }

    /**
     * Check if Zotero is configured
     */
    isConfigured(): boolean {
        return this.getConfig() !== null;
    }

    /**
     * Fetch a single page of library items and return both the items and the total results.
     */
    async fetchLibraryPage(
        start: number = 0,
        pageSize: number = 100,
        opts?: { q?: string }
    ): Promise<{ items: ZoteroItem[]; totalResults: number }> {
        const config = this.getConfig();
        if (!config) {
            throw new Error('Zotero not configured');
        }

        const libraryPath = config.libraryType === 'group'
            ? `groups/${config.groupId}`
            : `users/${config.userId}`;

        const url = `${this.baseUrl}/${libraryPath}/items`;
        const qTrim = opts?.q?.trim();
        console.log('[ZoteroAPI] Fetching from:', url, 'start:', start, 'limit:', pageSize, qTrim ? `q="${qTrim}"` : '(full library)');

        const params: Record<string, string | number> = {
                limit: pageSize,
                start: start,
                format: 'json',
                include: 'data'
        };
        if (qTrim) {
            params.q = qTrim;
        }

        const response = await axios.get<ZoteroItem[]>(url, {
            params,
            headers: {
                'Zotero-API-Key': config.apiKey,
                'Zotero-API-Version': '3'
            },
            timeout: 20000
        });

        console.log(`[ZoteroAPI] Fetched ${response.data.length} items`);
        const totalResults = parseInt(response.headers['total-results'] || '0', 10);
        return { items: response.data, totalResults };
    }

    /**
     * Fetch items from Zotero library
     */
    async fetchLibrary(limit: number = 10000, start: number = 0, throwOnError: boolean = false): Promise<ZoteroItem[]> {
        const config = this.getConfig();
        
        if (!config) {
            console.log('[ZoteroAPI] Not configured, skipping fetch');
            return [];
        }

        const maxPageSize = 100;
        const fetchLimit = Math.min(limit, maxPageSize);

        const allItems: ZoteroItem[] = [];
        let currentStart = start;

        while (allItems.length < limit) {
            try {
                const { items, totalResults } = await this.fetchLibraryPage(currentStart, fetchLimit);
                if (!items || items.length === 0) {
                    break;
                }

                allItems.push(...items);
                currentStart += items.length;

                if (items.length < fetchLimit || allItems.length >= totalResults) {
                    break;
                }
            } catch (error) {
                if (throwOnError) throw error;
                return this.handleFetchError(error);
            }
        }

        return allItems;
    }

    private async handleFetchError(error: unknown): Promise<ZoteroItem[]> {
        if (axios.isAxiosError(error)) {
            if (error.response?.status === 403) {
                console.error('[ZoteroAPI] Invalid API key or permissions');
                const action = await vscode.window.showErrorMessage(
                    'Zotero API key lacks library access permissions. When creating your API key at zotero.org/settings/keys, ensure "Allow library access" is checked.',
                    'Get New Key',
                    'Reconfigure',
                    'Open Settings'
                );

                if (action === 'Get New Key') {
                    vscode.env.openExternal(vscode.Uri.parse('https://www.zotero.org/settings/keys'));
                } else if (action === 'Reconfigure') {
                    await this.promptForCredentials();
                } else if (action === 'Open Settings') {
                    await vscode.commands.executeCommand('workbench.action.openSettings', 'ontocode.zotero');
                }
            } else if (error.response?.status === 404) {
                console.error('[ZoteroAPI] User/Group not found');
                const action = await vscode.window.showErrorMessage(
                    'Zotero user/group not found. Please check your user ID or group ID.',
                    'Reconfigure',
                    'Open Settings'
                );

                if (action === 'Reconfigure') {
                    await this.promptForCredentials();
                } else if (action === 'Open Settings') {
                    await vscode.commands.executeCommand('workbench.action.openSettings', 'ontocode.zotero');
                }
            } else {
                console.error('[ZoteroAPI] Request failed:', error.message);
                vscode.window.showErrorMessage(`Failed to fetch Zotero library: ${error.message}`);
            }
        } else {
            console.error('[ZoteroAPI] Unexpected error:', error);
        }
        return [];
    }

    /**
     * Fetch a specific item by key
     */
    async fetchItem(itemKey: string): Promise<ZoteroItem | null> {
        const config = this.getConfig();
        
        if (!config) {
            return null;
        }

        try {
            const libraryPath = config.libraryType === 'group' 
                ? `groups/${config.groupId}`
                : `users/${config.userId}`;

            const url = `${this.baseUrl}/${libraryPath}/items/${itemKey}`;

            const response = await axios.get<ZoteroItem>(url, {
                params: {
                    format: 'json',
                    include: 'data'
                },
                headers: {
                    'Zotero-API-Key': config.apiKey,
                    'Zotero-API-Version': '3'
                },
                timeout: 5000
            });

            return response.data;
        } catch (error) {
            console.error('[ZoteroAPI] Failed to fetch item:', error);
            return null;
        }
    }

    /**
     * Show configuration instructions and prompt for credentials
     */
    async showConfigInstructions(): Promise<void> {
        const configure = await vscode.window.showInformationMessage(
            'Zotero is not configured. Would you like to configure it now?',
            'Enter Credentials', 'Open Settings', 'Learn More', 'Use Mock Data'
        );

        if (configure === 'Enter Credentials') {
            await this.promptForCredentials();
        } else if (configure === 'Open Settings') {
            await vscode.commands.executeCommand('workbench.action.openSettings', 'ontocode.zotero');
        } else if (configure === 'Learn More') {
            vscode.env.openExternal(vscode.Uri.parse('https://www.zotero.org/settings/keys'));
        }
    }

    /**
     * Resolve the numeric userID tied to an API key via GET /keys/{key}.
     * Throws on failure so the caller can distinguish an actually-invalid key
     * (HTTP 403/404) from a network/TLS/timeout failure reaching Zotero.
     */
    private async fetchUserIdFromApiKey(apiKey: string): Promise<string | null> {
        const response = await axios.get(`${this.baseUrl}/keys/${encodeURIComponent(apiKey)}`, {
            headers: { 'Zotero-API-Version': '3', 'Accept': 'application/json' },
            timeout: 10000
        });
        const data = response.data;
        if (!data || typeof data !== 'object') {
            // A non-JSON 200 (e.g. an HTML page) almost always means something between
            // this Node process and Zotero rewrote the response — a corporate proxy/VPN
            // or SSL-inspection appliance, not an invalid key.
            const preview = typeof data === 'string' ? data.slice(0, 200) : String(data);
            console.error(
                `[ZoteroAPI] Unexpected /keys response — status ${response.status}, ` +
                `content-type "${response.headers['content-type']}": ${preview}`
            );
            throw new Error(
                `got a non-JSON response (status ${response.status}, content-type ${response.headers['content-type']}) ` +
                `— likely a proxy/VPN intercepting the request`
            );
        }
        if (!data.userID) {
            console.error(
                `[ZoteroAPI] /keys response had no userID — status ${response.status}, body: ${JSON.stringify(data)}`
            );
            return null;
        }
        return String(data.userID);
    }

    /**
     * Prompt user to enter Zotero credentials
     */
    async promptForCredentials(): Promise<boolean> {
        // Step 1: Get API Key
        const apiKey = await vscode.window.showInputBox({
            prompt: 'Enter your Zotero API Key',
            placeHolder: 'Get it from https://www.zotero.org/settings/keys',
            password: true,
            ignoreFocusOut: true,
            validateInput: (value) => {
                if (!value || value.trim().length === 0) {
                    return 'API Key is required';
                }
                if (value.length < 20) {
                    return 'API Key seems too short. Please check and try again.';
                }
                return null;
            }
        });

        if (!apiKey) {
            vscode.window.showWarningMessage('Zotero configuration cancelled.');
            return false;
        }

        // Step 2: Auto-resolve User ID from the API key
        let userId: string | null;
        try {
            userId = await vscode.window.withProgress({
                location: vscode.ProgressLocation.Notification,
                title: 'Verifying Zotero API key...',
                cancellable: false
            }, () => this.fetchUserIdFromApiKey(apiKey.trim()));
        } catch (error) {
            console.error('[ZoteroAPI] Key verification failed:', error);
            if (axios.isAxiosError(error) && error.response) {
                vscode.window.showErrorMessage(
                    `Invalid API key — Zotero returned ${error.response.status} ${error.response.statusText}.`
                );
            } else {
                const detail = error instanceof Error ? error.message : String(error);
                vscode.window.showErrorMessage(
                    `Could not reach Zotero to verify the key (${detail}). This looks like a network/proxy issue rather than an invalid key — check your connection and try again.`
                );
            }
            return false;
        }

        if (!userId) {
            vscode.window.showErrorMessage('Invalid API key — could not retrieve your User ID from Zotero.');
            return false;
        }

        // Step 3: Save to settings
        try {
            const config = vscode.workspace.getConfiguration('ontocode.zotero');
            await config.update('apiKey', apiKey.trim(), vscode.ConfigurationTarget.Global);
            await config.update('userId', userId, vscode.ConfigurationTarget.Global);

            vscode.window.showInformationMessage(
                '✅ Zotero configured successfully! Your citations will now load from your library.',
                'Test Connection'
            ).then(selection => {
                if (selection === 'Test Connection') {
                    this.testConnection();
                }
            });

            return true;
        } catch (error) {
            vscode.window.showErrorMessage(`Failed to save Zotero configuration: ${error}`);
            return false;
        }
    }

    /**
     * Expose config for the webview to read (e.g. populate ZoteroSettingsDialog)
     */
    getPublicConfig(): ZoteroConfig | null {
        return this.getConfig();
    }

    /**
     * Save Zotero credentials to VS Code workspace settings (called from webview postMessage)
     */
    async saveConfig(cfg: ZoteroConfig): Promise<void> {
        const config = vscode.workspace.getConfiguration('ontocode.zotero');
        await config.update('apiKey', cfg.apiKey, vscode.ConfigurationTarget.Global);
        await config.update('userId', cfg.userId, vscode.ConfigurationTarget.Global);
        await config.update('libraryType', cfg.libraryType, vscode.ConfigurationTarget.Global);
        await config.update('groupId', cfg.groupId || '', vscode.ConfigurationTarget.Global);
    }

    /**
     * Clear Zotero credentials from VS Code workspace settings (called from webview postMessage)
     */
    async clearConfig(): Promise<void> {
        const config = vscode.workspace.getConfiguration('ontocode.zotero');
        await config.update('apiKey', undefined, vscode.ConfigurationTarget.Global);
        await config.update('userId', undefined, vscode.ConfigurationTarget.Global);
        await config.update('libraryType', undefined, vscode.ConfigurationTarget.Global);
        await config.update('groupId', undefined, vscode.ConfigurationTarget.Global);
    }

    /**
     * Test the Zotero connection
     */
    async testConnection(): Promise<void> {
        const config = this.getConfig();
        if (!config) {
            vscode.window.showWarningMessage('Zotero is not configured.');
            return;
        }

        vscode.window.withProgress({
            location: vscode.ProgressLocation.Notification,
            title: 'Testing Zotero connection...',
            cancellable: false
        }, async () => {
            try {
                const items = await this.fetchLibrary(1); // Fetch just 1 item to test
                if (items && items.length >= 0) {
                    vscode.window.showInformationMessage(
                        `✅ Connected to Zotero! Found ${items.length > 0 ? 'items in' : 'empty'} library.`
                    );
                } else {
                    vscode.window.showWarningMessage('Connected but no items found in your Zotero library.');
                }
            } catch (error) {
                vscode.window.showErrorMessage(
                    `❌ Failed to connect to Zotero: ${error instanceof Error ? error.message : 'Unknown error'}`
                );
            }
        });
    }
}

export const zoteroApiService = new ZoteroApiService();
