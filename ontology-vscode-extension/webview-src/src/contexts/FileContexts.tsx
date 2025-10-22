import React, { createContext, useState, useEffect } from 'react';

interface FileContent {
    fileName: string;
    content: string;
}

interface FileContextType {
    currentFile: FileContent | null;
    setCurrentFile: (file: FileContent | null) => void;
    saveFile: (fileName: string, content: string) => void;
    requestFile: () => void;
}

const FileContext = createContext<FileContextType | undefined>(undefined);

export const FileProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const [currentFile, setCurrentFile] = useState<FileContent | null>(null);

    useEffect(() => {
        // Listen for messages from VS Code extension
        const handleMessage = (event: MessageEvent) => {
            const message = event.data;
            switch (message.type) {
                case 'fileContent':
                    setCurrentFile({
                        fileName: message.fileName,
                        content: message.content
                    });
                    break;
            }
        };

        window.addEventListener('message', handleMessage);
        return () => window.removeEventListener('message', handleMessage);
    }, []);

    const saveFile = (fileName: string, content: string) => {
        if (window.vscode) {
            window.vscode.postMessage({
                type: 'saveFile',
                fileName,
                content
            });
        }
    };

    const requestFile = () => {
        if (window.vscode) {
            window.vscode.postMessage({
                type: 'requestFile'
            });
        }
    };

    return (
        <FileContext.Provider value={{ currentFile, setCurrentFile, saveFile, requestFile }}>
            {children}
        </FileContext.Provider>
    );
};
