/**
 * fileAccess.ts
 * Cross-environment file access utility.
 *
 * Works in three contexts:
 *  - VS Code Desktop extension  → handled by the extension; this module is not used.
 *  - Browser / VS Code Web      → File System Access API (Chrome/Edge) or <input> fallback.
 *  - Electron                   → File System Access API (Chromium) or IPC via preload.
 */

export interface OntologyFileData {
    fileName: string;
    fileContent: string; // raw text
    fileSize: number;    // bytes
    isBase64?: boolean;  // true for binary package uploads such as .zip
}

/**
 * Opens a native file picker and returns the selected ontology file.
 * Returns null if the user cancels.
 */
export async function openOntologyFile(): Promise<OntologyFileData | null> {
    // Electron: use native dialog exposed by preload (best UX)
    if ((window as any).electronAPI?.openFile) {
        return (window as any).electronAPI.openFile();
    }

    // Prefer File System Access API when available (Chrome 86+, Edge 86+, Electron)
    if ('showOpenFilePicker' in window) {
        try {
            const [handle] = await (window as any).showOpenFilePicker({
                types: [
                    {
                        description: 'Ontology Files',
                        accept: {
                            'application/octet-stream': ['.owl', '.rdf', '.ttl', '.n3', '.nt', '.jsonld', '.zip'],
                            'application/zip': ['.zip'],
                        },
                    },
                ],
                multiple: false,
            });
            const file: File = await handle.getFile();
            if (file.name.toLowerCase().endsWith('.zip')) {
                const fileContent = arrayBufferToBase64(await file.arrayBuffer());
                return { fileName: file.name, fileContent, fileSize: file.size, isBase64: true };
            }
            const fileContent = await file.text();
            return { fileName: file.name, fileContent, fileSize: file.size };
        } catch (err: any) {
            if (err?.name === 'AbortError') return null; // user cancelled
            throw err;
        }
    }

    // Fallback: hidden <input type="file"> (Firefox, Safari, older browsers)
    return new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.owl,.rdf,.ttl,.n3,.nt,.jsonld,.zip';
        input.style.display = 'none';

        input.onchange = async () => {
            const file = input.files?.[0];
            document.body.removeChild(input);
            if (!file) { resolve(null); return; }
            if (file.name.toLowerCase().endsWith('.zip')) {
                const fileContent = arrayBufferToBase64(await file.arrayBuffer());
                resolve({ fileName: file.name, fileContent, fileSize: file.size, isBase64: true });
                return;
            }
            const fileContent = await file.text();
            resolve({ fileName: file.name, fileContent, fileSize: file.size });
        };

        // 'cancel' event is not universally supported; rely on onchange firing with empty files
        document.body.appendChild(input);
        input.click();
    });
}

/**
 * Converts a plain-text (string) file content to a base64-encoded string.
 * Needed for the /api/projects/:id/files upload endpoint.
 */
export function fileContentToBase64(content: string): string {
    // btoa only handles ASCII; use encodeURIComponent for Unicode safety
    return btoa(unescape(encodeURIComponent(content)));
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    const chunkSize = 0x8000;
    let binary = '';
    for (let i = 0; i < bytes.length; i += chunkSize) {
        const chunk = bytes.subarray(i, i + chunkSize);
        binary += String.fromCharCode(...chunk);
    }
    return btoa(binary);
}
