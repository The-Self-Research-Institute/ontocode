declare global {
    interface Window {
        vscode?: {
            postMessage: (message: any) => void;
        };
    }
}

export {};