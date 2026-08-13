import * as vscode from 'vscode';

export interface IssueReportData {
    title: string;
    description: string;
    stepsToReproduce?: string;
    userEmail?: string;
    projectId?: string;
    projectName?: string;
    ontologyFilePath?: string;
    errorLogs?: string;
    systemInfo?: {
        osName: string;
        osVersion: string;
        vsCodeVersion: string;
        extensionVersion: string;
    };
}

class IssueReportService {
    private outputChannel: vscode.OutputChannel | null = null;
    private errorLogBuffer: string[] = [];
    private readonly MAX_LOG_BUFFER_SIZE = 100;
    private editorUrl: string = 'http://localhost:8083'; // Default to self-hosted

    constructor() {

        this.outputChannel = vscode.window.createOutputChannel('OntoCode');
    }

    setEditorUrl(url: string): void {
        this.editorUrl = url;
        console.log('[IssueReportService] Editor URL updated to:', url);
    }

    logError(message: string, error?: Error): void {
        const timestamp = new Date().toISOString();
        const logEntry = `[${timestamp}] ERROR: ${message}${error ? '\n' + error.stack : ''}`;

        this.errorLogBuffer.push(logEntry);
        if (this.errorLogBuffer.length > this.MAX_LOG_BUFFER_SIZE) {
            this.errorLogBuffer.shift(); // Remove oldest entry
        }

        if (this.outputChannel) {
            this.outputChannel.appendLine(logEntry);
        }

        console.error(message, error);
    }

    logInfo(message: string): void {
        const timestamp = new Date().toISOString();
        const logEntry = `[${timestamp}] INFO: ${message}`;

        if (this.outputChannel) {
            this.outputChannel.appendLine(logEntry);
        }

        console.log(message);
    }

    getRecentErrorLogs(): string {
        const recentLogs = this.errorLogBuffer.slice(-50);
        let logsText = recentLogs.join('\n');

        if (logsText.length > 5000) {
            logsText = '...(truncated)\n' + logsText.substring(logsText.length - 5000);
        }

        return logsText || 'No recent errors logged';
    }

    getSystemInfo(extensionVersion: string): IssueReportData['systemInfo'] {
        return {
            osName: process.platform,
            osVersion: process.version,
            vsCodeVersion: vscode.version,
            extensionVersion: extensionVersion
        };
    }

    showOutputChannel(): void {
        if (this.outputChannel) {
            this.outputChannel.show();
        }
    }

    async validateJiraConnection(): Promise<{ success: boolean; message: string }> {
        try {
            const url = `${this.editorUrl}/api/v1/issues/jira/validate`;
            console.log('[IssueReportService] Validating Jira connection at:', url);

            const response = await fetch(url, {
                method: 'GET'
                // credentials: 'include' // Removed - not needed for JWT auth
            });

            const result = await response.json();
            return {
                success: result.success || false,
                message: result.message || 'Unknown status'
            };
        } catch (error) {
            return {
                success: false,
                message: `Failed to connect to backend: ${error instanceof Error ? error.message : 'Unknown error'}`
            };
        }
    }

    getCurrentProjectContext(): { projectName?: string; ontologyFilePath?: string } {
        const activeEditor = vscode.window.activeTextEditor;

        if (!activeEditor) {
            return {};
        }

        const filePath = activeEditor.document.uri.fsPath;
        const fileName = activeEditor.document.fileName;

        const workspaceFolder = vscode.workspace.getWorkspaceFolder(activeEditor.document.uri);
        const projectName = workspaceFolder?.name;

        const isOntologyFile = /\.(owl|rdf|ttl|n3|nt)$/i.test(fileName);
        const ontologyFilePath = isOntologyFile ? filePath : undefined;

        return { projectName, ontologyFilePath };
    }

    clearErrorLogs(): void {
        this.errorLogBuffer = [];
        this.logInfo('Error log buffer cleared');
    }

    dispose(): void {
        if (this.outputChannel) {
            this.outputChannel.dispose();
            this.outputChannel = null;
        }
    }
}

export const issueReportService = new IssueReportService();
