import * as vscode from 'vscode';

/**
 * Sentinel value returned by AuthWebviewPanel.show() when the user chose
 * to open the login page in the external browser instead (e.g. because the
 * iframe was blocked by X-Frame-Options on the Keycloak server).
 */
export const OPEN_IN_BROWSER = '__open_in_browser__';

/**
 * Webview panel for embedded OIDC authentication
 * Displays the Keycloak login page inside VS Code instead of external browser
 */
export class AuthWebviewPanel {
  public static currentPanel: AuthWebviewPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private readonly _extensionUri: vscode.Uri;
  private _disposables: vscode.Disposable[] = [];
  private _resolveLogin: ((token: string | null) => void) | null = null;

  /**
   * Show the authentication webview with the given auth URL
   * Returns a promise that resolves with the JWT token or null if cancelled
   */
  public static async show(
    extensionUri: vscode.Uri,
    authUrl: string,
    providerName: string
  ): Promise<string | null> {
    const column = vscode.ViewColumn.One;

    // If panel already exists, dispose it first
    if (AuthWebviewPanel.currentPanel) {
      AuthWebviewPanel.currentPanel._panel.dispose();
      AuthWebviewPanel.currentPanel = undefined;
    }

    // Create new panel
    const panel = vscode.window.createWebviewPanel(
      'ontocodeAuth',
      `Login with ${providerName}`,
      column,
      {
        enableScripts: true,
        retainContextWhenHidden: false,
        // Allow embedded content from the auth server
        localResourceRoots: [],
        // Security: limit external access to auth domain only
        portMapping: []
      }
    );

    const authPanel = new AuthWebviewPanel(panel, extensionUri, authUrl, providerName);
    
    // Return a promise that will be resolved when authentication completes
    return new Promise<string | null>((resolve) => {
      authPanel._resolveLogin = resolve;
    });
  }

  private constructor(
    panel: vscode.WebviewPanel,
    extensionUri: vscode.Uri,
    authUrl: string,
    providerName: string
  ) {
    this._panel = panel;
    this._extensionUri = extensionUri;

    // Set HTML content with iframe to Keycloak
    this._panel.webview.html = this._getHtmlContent(authUrl, providerName);

    // Handle messages from webview
    this._panel.webview.onDidReceiveMessage(
      async (message) => {
        switch (message.type) {
          case 'authSuccess':
            console.log('[AuthWebview] Authentication successful, token received');
            this.completeAuth(message.token);
            break;

          case 'authError':
            console.error('[AuthWebview] Authentication failed:', message.error);
            vscode.window.showErrorMessage(`Login failed: ${message.error}`);
            this.completeAuth(null);
            break;

          case 'urlChanged':
            console.log('[AuthWebview] URL changed:', message.url);
            // Check if this is a callback URL with token
            this.checkForToken(message.url);
            break;

          case 'cancel':
            console.log('[AuthWebview] Authentication cancelled by user');
            this.completeAuth(null);
            break;

          case 'openInBrowser':
            // Iframe was blocked (X-Frame-Options / CSP); fall back to external browser
            console.log('[AuthWebview] Iframe blocked - falling back to external browser');
            this.completeAuth(OPEN_IN_BROWSER);
            break;
        }
      },
      null,
      this._disposables
    );

    // Handle panel disposal
    this._panel.onDidDispose(() => this.dispose(), null, this._disposables);

    AuthWebviewPanel.currentPanel = this;
  }

  /**
   * Check URL for token parameter
   */
  private checkForToken(url: string): void {
    try {
      const urlObj = new URL(url);
      const token = urlObj.searchParams.get('token');
      
      if (token) {
        console.log('[AuthWebview] Token found in URL');
        this.completeAuth(token);
      }
    } catch (error) {
      console.error('[AuthWebview] Error parsing URL:', error);
    }
  }

  /**
   * Complete authentication and resolve promise
   */
  private completeAuth(token: string | null): void {
    if (this._resolveLogin) {
      this._resolveLogin(token);
      this._resolveLogin = null;
    }
    
    // Close the panel
    this._panel.dispose();
  }

  /**
   * Generate HTML content for the webview.
   *
   * VS Code webviews run from a https://vscode-webview.net virtual origin.
   * Electron blocks http:// iframes from an HTTPS origin as mixed content, so
   * we cannot embed the Keycloak login page directly.
   *
   * Instead this panel acts as a VS Code-native "auth popup":
   *   1. It fires `openInBrowser` immediately, which triggers loginViaExternalBrowser().
   *   2. The extension opens the system browser with redirect_uri=vscode://...
   *   3. After login, the vscode:// URI handler delivers the token.
   *   4. The panel is then dismissed automatically.
   *
   * The user can also click "Open in Browser" manually, or "Cancel" to abort.
   */
  private _getHtmlContent(authUrl: string, providerName: string): string {
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline';">
    <title>Sign in with ${providerName}</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background: #1e1e1e;
            color: #cccccc;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            display: flex;
            flex-direction: column;
            height: 100vh;
        }
        .header {
            background: #2d2d30;
            padding: 10px 16px;
            border-bottom: 1px solid #3e3e42;
            display: flex;
            justify-content: space-between;
            align-items: center;
            flex-shrink: 0;
        }
        .header h1 { font-size: 13px; font-weight: 600; color: #cccccc; }
        .cancel-btn {
            background: #0e639c;
            color: #fff;
            border: none;
            padding: 4px 14px;
            border-radius: 2px;
            cursor: pointer;
            font-size: 12px;
        }
        .cancel-btn:hover { background: #1177bb; }

        .body {
            flex: 1;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            gap: 24px;
            padding: 40px 24px;
            text-align: center;
        }
        .icon { font-size: 52px; }
        .spinner {
            border: 3px solid #3e3e42;
            border-top: 3px solid #0e639c;
            border-radius: 50%;
            width: 32px; height: 32px;
            animation: spin 0.9s linear infinite;
        }
        @keyframes spin { to { transform: rotate(360deg); } }
        h2 { font-size: 15px; font-weight: 600; color: #cccccc; }
        p  { font-size: 12px; color: #9e9e9e; max-width: 360px; line-height: 1.7; }
        .steps {
            display: flex;
            flex-direction: column;
            gap: 10px;
            width: 100%;
            max-width: 340px;
        }
        .step {
            display: flex;
            align-items: flex-start;
            gap: 10px;
            text-align: left;
            font-size: 12px;
            color: #888;
        }
        .step-num {
            background: #0e639c;
            color: #fff;
            border-radius: 50%;
            width: 18px; height: 18px;
            display: flex; align-items: center; justify-content: center;
            font-size: 11px; font-weight: 600;
            flex-shrink: 0;
        }
        .open-btn {
            background: transparent;
            color: #4fc1ff;
            border: 1px solid #3e3e42;
            padding: 6px 18px;
            border-radius: 2px;
            cursor: pointer;
            font-size: 12px;
            margin-top: 4px;
        }
        .open-btn:hover { background: #2a2d2e; }
    </style>
</head>
<body>
    <div class="header">
        <h1>🔐 Sign in with ${providerName}</h1>
        <button class="cancel-btn" onclick="cancel()">Cancel</button>
    </div>

    <div class="body">
        <div class="spinner"></div>
        <h2>Opening ${providerName} in your browser…</h2>
        <p>Sign in to ${providerName} in the browser window that just opened.<br>
           VS Code will resume automatically when login is complete.</p>

        <div class="steps">
            <div class="step">
                <div class="step-num">1</div>
                <span>Complete the login in the browser window.</span>
            </div>
            <div class="step">
                <div class="step-num">2</div>
                <span>The browser will ask to open VS Code — click <strong>Open</strong>.</span>
            </div>
            <div class="step">
                <div class="step-num">3</div>
                <span>This panel closes automatically once you're signed in.</span>
            </div>
        </div>

        <button class="open-btn" onclick="openBrowser()">Didn't open? Click here</button>
    </div>

    <script>
        const vscode = acquireVsCodeApi();

        // Trigger the external-browser login flow immediately.
        // loginViaExternalBrowser() opens the system browser with
        // redirect_uri=vscode://self.ontocode-extension/oidc-callback
        // and waits for the VS Code URI handler to deliver the token.
        vscode.postMessage({ type: 'openInBrowser' });

        function openBrowser() {
            vscode.postMessage({ type: 'openInBrowser' });
        }

        function cancel() {
            vscode.postMessage({ type: 'cancel' });
        }
    </script>
</body>
</html>`;
  }

  public dispose() {
    AuthWebviewPanel.currentPanel = undefined;

    // Clean up resources
    this._panel.dispose();

    while (this._disposables.length) {
      const disposable = this._disposables.pop();
      if (disposable) {
        disposable.dispose();
      }
    }
  }
}
