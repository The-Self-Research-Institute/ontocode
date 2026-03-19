/**
 * OIDC Authentication Service for VS Code Extension
 * 
 * This service handles OAuth2/OIDC authentication flow in the VS Code extension.
 * It displays the OIDC provider's login page in an embedded webview within VS Code
 * and captures the authentication token when the login completes.
 */

import * as vscode from 'vscode';
import axios from 'axios';
import { AuthWebviewPanel, OPEN_IN_BROWSER } from '../webview/authWebview';

export interface OidcProvider {
    id: string;
    displayName: string;
    authUrl: string;
}

export interface OidcProvidersResponse {
    enabled: boolean;
    providers: OidcProvider[];
}

export interface OidcLoginResult {
    token: string;
    username: string;
    email: string;
    name?: string;
    roles: string[];
    oidcProvider: string;
}

class OidcAuthService {
    private gatewayUrl: string;
    private pendingLoginResolve: ((result: OidcLoginResult | null) => void) | null = null;
    private pendingLoginReject: ((error: any) => void) | null = null;
    private pendingLoginTimeout: ReturnType<typeof setTimeout> | null = null;

    constructor(gatewayUrl: string) {
        this.gatewayUrl = gatewayUrl;
    }

    /**
     * Update gateway URL (e.g., when switching between self-hosted and cloud)
     */
    public setGatewayUrl(gatewayUrl: string): void {
        this.gatewayUrl = gatewayUrl;
    }

    /**
     * Handle OAuth2 callback from URI handler
     */
    public handleOAuthCallback(token?: string | null, error?: string | null, code?: string | null): void {
        if (this.pendingLoginTimeout) {
            clearTimeout(this.pendingLoginTimeout);
            this.pendingLoginTimeout = null;
        }

        if (!this.pendingLoginResolve) {
            console.warn('[OIDC] No pending login to resolve');
            return;
        }

        if (error) {
            console.error('[OIDC] OAuth error:', error);
            if (this.pendingLoginReject) {
                this.pendingLoginReject(new Error(error));
            }
            this.pendingLoginResolve = null;
            this.pendingLoginReject = null;
            return;
        }

        if (token) {
            // Validate and decode the token
            const tokenData = this.parseJwtToken(token);
            if (!tokenData) {
                if (this.pendingLoginReject) {
                    this.pendingLoginReject(new Error('Invalid token format'));
                }
                this.pendingLoginResolve = null;
                this.pendingLoginReject = null;
                return;
            }

            // Resolve with login result
            this.pendingLoginResolve({
                token: token,
                username: tokenData.username || tokenData.sub || 'unknown',
                email: tokenData.email || '',
                name: tokenData.name,
                roles: tokenData.roles || ['ROLE_USER'],
                oidcProvider: tokenData.oidcProvider || 'unknown'
            });
            this.pendingLoginResolve = null;
            this.pendingLoginReject = null;
        } else if (code) {
            // For authorization code flow, we would exchange it here
            // For now, show error as we expect direct token
            if (this.pendingLoginReject) {
                this.pendingLoginReject(new Error('Authorization code flow not yet implemented'));
            }
            this.pendingLoginResolve = null;
            this.pendingLoginReject = null;
        }
    }

    /**
     * Get list of enabled OIDC providers from the auth service
     */
    public async getEnabledProviders(): Promise<OidcProvidersResponse> {
        try {
            const response = await axios.get(`${this.gatewayUrl}/api/auth/oidc/providers`);
            return response.data;
        } catch (error) {
            console.error('[OIDC] Error fetching OIDC providers:', error);
            return { enabled: false, providers: [] };
        }
    }

    /**
     * Check if OIDC is enabled and has any configured providers
     */
    public async isOidcAvailable(): Promise<boolean> {
        try {
            const providersResponse = await this.getEnabledProviders();
            return providersResponse.enabled && providersResponse.providers.length > 0;
        } catch (error) {
            console.error('[OIDC] Error checking OIDC availability:', error);
            return false;
        }
    }

    /**
     * Show quick pick for user to select OIDC provider
     */
    public async selectProvider(): Promise<OidcProvider | undefined> {
        try {
            const providersResponse = await this.getEnabledProviders();
            
            if (!providersResponse.enabled || providersResponse.providers.length === 0) {
                vscode.window.showWarningMessage('OIDC authentication is not enabled or no providers are configured.');
                return undefined;
            }

            if (providersResponse.providers.length === 1) {
                // If only one provider, use it directly
                return providersResponse.providers[0];
            }

            // Show quick pick for multiple providers
            const items = providersResponse.providers.map(provider => ({
                label: provider.displayName,
                description: `Login with ${provider.displayName}`,
                provider: provider
            }));

            const selected = await vscode.window.showQuickPick(items, {
                placeHolder: 'Select login provider',
                title: 'OIDC Login'
            });

            return selected?.provider;
        } catch (error) {
            console.error('[OIDC] Error selecting provider:', error);
            vscode.window.showErrorMessage('Failed to load OIDC providers.');
            return undefined;
        }
    }

    /**
     * Direct Keycloak login (bypasses backend gateway)
     * Uses the public ontocode-vscode client
     */
    public async loginDirectToKeycloak(extensionUri: vscode.Uri): Promise<OidcLoginResult | null> {
        try {
            console.log('[OIDC] Starting direct Keycloak login (no backend required)');

            const keycloakUrl = 'http://localhost:9080';
            const realm = 'ontocode';
            
            // Show Keycloak account page directly in embedded webview
            const accountUrl = `${keycloakUrl}/realms/${realm}/account`;
            
            console.log('[OIDC] Opening Keycloak account page:', accountUrl);
            
            // For now, show instructions since we can't extract token from account page
            const selection = await vscode.window.showInformationMessage(
                '🔐 Direct Keycloak Authentication\\n\\n' +
                'Backend services are not running. To enable full OIDC flow:\\n\\n' +
                '1. Start services in terminal:\\n' +
                '   docker-compose -f docker-compose.keycloak.yml up -d\\n\\n' +
                '2. Wait 2-3 minutes for initialization\\n\\n' +
                '3. Click "Sign in with Keycloak" again\\n\\n' +
                'Services are currently downloading Docker images...\\n\\n' +
                'Or create a test user in Keycloak admin console:',
                'Open Keycloak Admin',
                'Open Account Page',
                'Check Services Status'
            );

            if (selection === 'Open Keycloak Admin') {
                await vscode.env.openExternal(vscode.Uri.parse(`${keycloakUrl}/admin`));
                vscode.window.showInformationMessage(
                    'Login with admin/admin, select "ontocode" realm, go to Users to create test user'
                );
            } else if (selection === 'Open Account Page') {
                await vscode.env.openExternal(vscode.Uri.parse(accountUrl));
            } else if (selection === 'Check Services Status') {
                const terminal = vscode.window.createTerminal('OntoCode Services');
                terminal.sendText('docker ps --format "table {{.Names}}\\t{{.Status}}\\t{{.Ports}}"');
                terminal.show();
            }

            return null;
        } catch (error: any) {
            console.error('[OIDC] Direct Keycloak login error:', error);
            vscode.window.showErrorMessage(`Keycloak login failed: ${error.message}`);
            return null;
        }
    }

    /**
     * Initiate OIDC login flow.
     * Shows the Keycloak login page in an embedded webview (iframe) inside VS Code.
     *
     * The webview CSP is configured with frame-src so the browser allows the iframe to
     * load cross-origin content (gateway → auth-service → Keycloak).  After a successful
     * login the backend's embedded-success page calls window.parent.postMessage with
     * {type:'oidc-token', token:'...'}, which the webview relays back to the extension.
     */
    public async login(extensionUri: vscode.Uri, provider?: OidcProvider): Promise<OidcLoginResult | null> {
        try {
            // If no provider specified, let user select one
            if (!provider) {
                provider = await this.selectProvider();
                if (!provider) {
                    const selection = await vscode.window.showWarningMessage(
                        'Could not connect to auth service. Try direct Keycloak login?',
                        'Yes', 'No'
                    );
                    if (selection === 'Yes') {
                        return await this.loginDirectToKeycloak(extensionUri);
                    }
                    return null;
                }
            }

            console.log('[OIDC] Starting embedded (iframe) login flow for provider:', provider.displayName);

            // Construct the full auth URL via the gateway
            const authUrl = `${this.gatewayUrl}${provider.authUrl}`;
            console.log('[OIDC] Loading login page in embedded webview:', authUrl);

            const tokenOrSentinel = await AuthWebviewPanel.show(extensionUri, authUrl, provider.displayName);

            if (tokenOrSentinel === null) {
                console.log('[OIDC] Login cancelled or failed - no token received');
                return null;
            }

            // Iframe was blocked; fall back to external browser + VS Code URI handler
            if (tokenOrSentinel === OPEN_IN_BROWSER) {
                console.log('[OIDC] Falling back to external browser login');
                return await this.loginViaExternalBrowser(provider);
            }

            const token = tokenOrSentinel;

            const tokenData = this.parseJwtToken(token);
            if (!tokenData) {
                throw new Error('Invalid token format received from authentication');
            }

            const result: OidcLoginResult = {
                token,
                username: tokenData.username || tokenData.sub || 'unknown',
                email: tokenData.email || '',
                name: tokenData.name,
                roles: tokenData.roles || ['ROLE_USER'],
                oidcProvider: tokenData.oidcProvider || provider.id
            };

            console.log('[OIDC] Login completed successfully for user:', result.username);
            return result;

        } catch (error: any) {
            console.error('[OIDC] Login error:', error);
            vscode.window.showErrorMessage(`OIDC login failed: ${error.message}`);
            return null;
        }
    }

    /**
     * Fallback: open the auth URL in the system browser and wait for the token
     * to arrive via the VS Code URI handler (vscode://self.ontocode-extension/oidc-callback).
     * Used automatically when the iframe is blocked by server X-Frame-Options / CSP.
     */
    private async loginViaExternalBrowser(provider: OidcProvider): Promise<OidcLoginResult | null> {
        const callbackUri = 'vscode://self.ontocode-extension/oidc-callback';
        const separator = provider.authUrl.includes('?') ? '&' : '?';
        const authUrl = `${this.gatewayUrl}${provider.authUrl}${separator}redirect_uri=${encodeURIComponent(callbackUri)}`;

        console.log('[OIDC] Opening external browser for login:', authUrl);

        return new Promise<OidcLoginResult | null>((resolve, reject) => {
            if (this.pendingLoginTimeout) {
                clearTimeout(this.pendingLoginTimeout);
                this.pendingLoginTimeout = null;
            }
            this.pendingLoginResolve = resolve;
            this.pendingLoginReject = reject;

            this.pendingLoginTimeout = setTimeout(() => {
                if (this.pendingLoginResolve) {
                    this.pendingLoginResolve = null;
                    this.pendingLoginReject = null;
                    this.pendingLoginTimeout = null;
                    resolve(null);
                    vscode.window.showWarningMessage('OIDC login timed out. Please try again.');
                }
            }, 5 * 60 * 1000);

            vscode.env.openExternal(vscode.Uri.parse(authUrl)).then((opened) => {
                if (!opened) {
                    if (this.pendingLoginTimeout) { clearTimeout(this.pendingLoginTimeout); this.pendingLoginTimeout = null; }
                    this.pendingLoginResolve = null;
                    this.pendingLoginReject = null;
                    reject(new Error('Failed to open browser for authentication'));
                    return;
                }
                vscode.window.showInformationMessage(
                    `Sign in with ${provider.displayName} opened in your browser. Return to VS Code after signing in.`,
                    'Cancel'
                ).then((action) => {
                    if (action === 'Cancel' && this.pendingLoginResolve) {
                        if (this.pendingLoginTimeout) { clearTimeout(this.pendingLoginTimeout); this.pendingLoginTimeout = null; }
                        this.pendingLoginResolve = null;
                        this.pendingLoginReject = null;
                        resolve(null);
                    }
                });
            });
        });
    }

    /**
     * Alternative OIDC login using UriHandler (more integrated approach)
     * This requires registering a URI handler for the OAuth2 callback
     */
    public async loginWithUriHandler(provider?: OidcProvider): Promise<OidcLoginResult | null> {
        try {
            if (!provider) {
                provider = await this.selectProvider();
                if (!provider) {
                    return null;
                }
            }

            // This would require implementing a UriHandler to receive the OAuth2 callback
            // See: https://code.visualstudio.com/api/references/vscode-api#window.registerUriHandler
            
            vscode.window.showInformationMessage(
                'Advanced OIDC integration with URI handler is not yet implemented. Please use the standard login method.',
                'OK'
            );
            
            return null;
        } catch (error: any) {
            console.error('[OIDC] URI handler login error:', error);
            return null;
        }
    }

    /**
     * Parse JWT token to extract user information
     */
    private parseJwtToken(token: string): any {
        try {
            const parts = token.split('.');
            if (parts.length !== 3) {
                return null;
            }

            const payload = parts[1];
            const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
            const jsonPayload = Buffer.from(base64, 'base64').toString('utf-8');
            return JSON.parse(jsonPayload);
        } catch (error) {
            console.error('[OIDC] Error parsing JWT token:', error);
            return null;
        }
    }
}

// Export singleton instance — gatewayUrl will be updated via setGatewayUrl() once
// the extension reads the stored deployment type during activation.
export const oidcAuthService = new OidcAuthService('');
