import * as vscode from 'vscode';

export const ONTOLOGY_FOCUS_CONTEXT = 'ontocode.ontologyFocus';

export interface UndoRoutingMessage {
    type: string;
    ontology?: boolean;
}

export function handleFocusContext(message: UndoRoutingMessage): boolean {
    if (message.type !== 'focusContext') {
        return false;
    }
    void vscode.commands.executeCommand('setContext', ONTOLOGY_FOCUS_CONTEXT, message.ontology === true);
    return true;
}

export function registerUndoRouting(send: (message: { type: string }) => void): vscode.Disposable[] {
    return [
        vscode.commands.registerCommand('ontocode.undo', () => send({ type: 'ontologyUndo' })),
        vscode.commands.registerCommand('ontocode.redo', () => send({ type: 'ontologyRedo' })),
    ];
}
