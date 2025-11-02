// Helper to generate unique IDs for requests
const generateRequestId = () => `req-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;

// A map to store pending requests with their promise resolve/reject functions
const pendingRequests = new Map<string, { resolve: (value: any) => void; reject: (reason?: any) => void }>();

// A single, global message listener to handle responses from the VS Code extension
window.addEventListener('message', (event) => {
    const message = event.data;
    // We only care about messages that are API responses
    if (message.type === 'apiResponse') {
        const { requestId, response, error } = message;
        const promise = pendingRequests.get(requestId);

        if (promise) {
            if (error) {
                console.error(`[API Client] Error for request ${requestId}:`, error);
                // Create an error object that resembles an Axios error for better compatibility
                // with existing catch blocks in the application.
                const proxyError = new Error(error.message || 'API request failed via proxy');
                (proxyError as any).isAxiosError = true;
                (proxyError as any).response = { data: error.data, status: error.status };
                promise.reject(proxyError);
            } else {
                // The response from the proxy is expected to have the same shape as an Axios response
                promise.resolve(response);
            }
            // Clean up the request from the map once it's handled
            pendingRequests.delete(requestId);
        }
    }
});

/**
 * Sends a request to the VS Code extension via postMessage and returns a promise
 * that resolves or rejects when the extension sends a response back.
 * @param payload The request details to send to the extension.
 * @returns A promise that resolves with the API response.
 */
function postRequestToVscode(payload: { type: string, [key: string]: any }): Promise<{ data: any }> {
    return new Promise((resolve, reject) => {
        // Ensure the vscode API is available
        if (!window.vscode) {
            const error = new Error("Not in a VSCode webview environment. Cannot make API calls.");
            console.error(error.message);
            // This is a hard failure as the proxy mechanism is unavailable.
            return reject(error);
        }

        const requestId = generateRequestId();
        pendingRequests.set(requestId, { resolve, reject });
        
        // Send the message to the extension, including the unique request ID
        window.vscode.postMessage({ ...payload, requestId });

        // Set a timeout to prevent memory leaks for requests that never get a response
        setTimeout(() => {
            if (pendingRequests.has(requestId)) {
                pendingRequests.get(requestId)?.reject(new Error(`Request ${requestId} timed out after 60 seconds.`));
                pendingRequests.delete(requestId);
            }
        }, 60000); // 60-second timeout
    });
}

/**
 * apiClient provides a proxied interface for all API calls. It maintains the same
 * function signatures as the previous Axios-based implementation, so no components
 * need to be changed.
 */
const apiClient = {
    /**
     * Performs a GET request by proxying it through the VS Code extension.
     */
    get: async <T>(url: string, config?: { params?: Record<string, unknown> }): Promise<{ data: T }> => {
        console.log(`[API] GET via Proxy: ${url}`);
        return postRequestToVscode({ type: 'apiGet', url, params: config?.params });
    },

    /**
     * Performs a POST request by proxying it through the VS Code extension.
     */
    post: async <T>(url:string, body?: unknown): Promise<{ data: T }> => {
        console.log(`[API] POST via Proxy: ${url}`, body);
        return postRequestToVscode({ type: 'apiPost', url, body });
    },

    /**
     * Performs a DELETE request by proxying it through the VS Code extension.
     */
    delete: async <T>(url: string, config?: { params?: Record<string, unknown> }): Promise<{ data: T }> => {
        console.log(`[API] DELETE via Proxy: ${url}`);
        return postRequestToVscode({ type: 'apiDelete', url, params: config?.params });
    },
};

export default apiClient;
