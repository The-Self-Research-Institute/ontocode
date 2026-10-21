import * as vscode from 'vscode';
import { ZoteroKeyStore } from './zoteroKeyStore';

const detached = new ZoteroKeyStore(
    { get: async () => undefined, store: async () => undefined, delete: async () => undefined },
    { read: () => '', clear: async () => undefined }
);

let active: ZoteroKeyStore = detached;

export async function initZoteroKeyStore(context: vscode.ExtensionContext): Promise<void> {
    const settings = () => vscode.workspace.getConfiguration('ontocode.zotero');
    active = new ZoteroKeyStore(context.secrets, {
        read: () => settings().get<string>('apiKey', ''),
        clear: () => settings().update('apiKey', undefined, vscode.ConfigurationTarget.Global)
    });
    await active.load();
}

export function zoteroKeyStore(): ZoteroKeyStore {
    return active;
}
