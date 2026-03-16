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
        DOI?: string;
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
     * Fetch items from Zotero library
     */
    async fetchLibrary(limit: number = 100): Promise<ZoteroItem[]> {
        const config = this.getConfig();
        
        if (!config) {
            console.log('[ZoteroAPI] Not configured, skipping fetch');
            return [];
        }

        try {
            const libraryPath = config.libraryType === 'group' 
                ? `groups/${config.groupId}`
                : `users/${config.userId}`;

            const url = `${this.baseUrl}/${libraryPath}/items`;
            
            console.log('[ZoteroAPI] Fetching from:', url);

            const response = await axios.get<ZoteroItem[]>(url, {
                params: {
                    limit,
                    format: 'json',
                    include: 'data'
                },
                headers: {
                    'Zotero-API-Key': config.apiKey,
                    'Zotero-API-Version': '3'
                },
                timeout: 10000
            });

            console.log(`[ZoteroAPI] Fetched ${response.data.length} items`);
            return response.data;
        } catch (error) {
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

        // Step 2: Get User ID
        const userId = await vscode.window.showInputBox({
            prompt: 'Enter your Zotero User ID',
            placeHolder: 'Find it on https://www.zotero.org/settings/keys (e.g., 123456)',
            ignoreFocusOut: true,
            validateInput: (value) => {
                if (!value || value.trim().length === 0) {
                    return 'User ID is required';
                }
                if (!/^\d+$/.test(value.trim())) {
                    return 'User ID should be a number';
                }
                return null;
            }
        });

        if (!userId) {
            vscode.window.showWarningMessage('Zotero configuration cancelled.');
            return false;
        }

        // Step 3: Save to settings
        try {
            const config = vscode.workspace.getConfiguration('ontocode.zotero');
            await config.update('apiKey', apiKey.trim(), vscode.ConfigurationTarget.Global);
            await config.update('userId', userId.trim(), vscode.ConfigurationTarget.Global);
            
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
