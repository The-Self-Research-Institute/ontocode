

export interface OntologyFileData {
    fileName: string;
    fileContent: string; // raw text
    fileSize: number;    // bytes
    isBase64?: boolean;  // true for binary package uploads such as .zip
    filePath?: string;
    focusOnly?: boolean;
}

export async function openOntologyFile(): Promise<OntologyFileData | null> {

    if ((window as any).electronAPI?.openFile) {
        return (window as any).electronAPI.openFile();
    }

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

        document.body.appendChild(input);
        input.click();
    });
}

export function fileContentToBase64(content: string): string {

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
