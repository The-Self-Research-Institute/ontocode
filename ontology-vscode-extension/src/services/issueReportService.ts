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

    constructor() {
        // Get or create output channel for logging
        this.outputChannel = vscode.window.createOutputChannel('OntoCode');
    }

    /**
     * Log an error to the output channel and buffer
     */
    logError(message: string, error?: Error): void {
        const timestamp = new Date().toISOString();
        const logEntry = `[${timestamp}] ERROR: ${message}${error ? '\n' + error.stack : ''}`;
        
        // Add to buffer
        this.errorLogBuffer.push(logEntry);
        if (this.errorLogBuffer.length > this.MAX_LOG_BUFFER_SIZE) {
            this.errorLogBuffer.shift(); // Remove oldest entry
        }

        // Log to output channel
        if (this.outputChannel) {
            this.outputChannel.appendLine(logEntry);
        }

        console.error(message, error);
    }

    /**
     * Log info to the output channel
     */
    logInfo(message: string): void {
        const timestamp = new Date().toISOString();
        const logEntry = `[${timestamp}] INFO: ${message}`;
        
        if (this.outputChannel) {
            this.outputChannel.appendLine(logEntry);
        }

        console.log(message);
    }

    /**
     * Get recent error logs (last 50 entries or 5000 characters)
     */
    getRecentErrorLogs(): string {
        const recentLogs = this.errorLogBuffer.slice(-50);
        let logsText = recentLogs.join('\n');
        
        // Limit to 5000 characters
        if (logsText.length > 5000) {
            logsText = '...(truncated)\n' + logsText.substring(logsText.length - 5000);
        }
        
        return logsText || 'No recent errors logged';
    }

    /**
     * Collect system information
     */
    getSystemInfo(extensionVersion: string): IssueReportData['systemInfo'] {
        return {
            osName: process.platform,
            osVersion: process.version,
            vsCodeVersion: vscode.version,
            extensionVersion: extensionVersion
        };
    }

    /**
     * Show the output channel
     */
    showOutputChannel(): void {
        if (this.outputChannel) {
            this.outputChannel.show();
        }
    }

    /**
     * Validate Jira configuration (check if backend is reachable)
     */
    async validateJiraConnection(): Promise<{ success: boolean; message: string }> {
        try {
            const response = await fetch('http://localhost:8083/api/v1/issues/jira/validate', {
                method: 'GET',
                credentials: 'include'
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

    /**
     * Get current project context
     */
    getCurrentProjectContext(): { projectName?: string; ontologyFilePath?: string } {
        const activeEditor = vscode.window.activeTextEditor;
        
        if (!activeEditor) {
            return {};
        }

        const filePath = activeEditor.document.uri.fsPath;
        const fileName = activeEditor.document.fileName;

        // Extract project name from workspace folder
        const workspaceFolder = vscode.workspace.getWorkspaceFolder(activeEditor.document.uri);
        const projectName = workspaceFolder?.name;

        // Only include ontology file path if it's an OWL/RDF file
        const isOntologyFile = /\.(owl|rdf|ttl|n3|nt)$/i.test(fileName);
        const ontologyFilePath = isOntologyFile ? filePath : undefined;

        return { projectName, ontologyFilePath };
    }

    /**
     * Clear error log buffer
     */
    clearErrorLogs(): void {
        this.errorLogBuffer = [];
        this.logInfo('Error log buffer cleared');
    }

    /**
     * Dispose resources
     */
    dispose(): void {
        if (this.outputChannel) {
            this.outputChannel.dispose();
            this.outputChannel = null;
        }
    }
}

// Export singleton instance
export const issueReportService = new IssueReportService();
