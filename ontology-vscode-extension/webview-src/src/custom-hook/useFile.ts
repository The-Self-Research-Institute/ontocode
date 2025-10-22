import  { createContext, useContext } from 'react';

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

export const useFile = () => {
    const context = useContext(FileContext);
    if (!context) {
        throw new Error('useFile must be used within a FileProvider');
    }
    return context;
};