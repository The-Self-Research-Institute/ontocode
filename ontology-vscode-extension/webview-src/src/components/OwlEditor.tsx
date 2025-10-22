import React, { useState, useEffect } from 'react';
import { useFile } from '../custom-hook/useFile';

const OWLEditor = () => {
    const { currentFile, saveFile } = useFile();
    const [content, setContent] = useState('');
    const [isDirty, setIsDirty] = useState(false);

    useEffect(() => {
        if (currentFile) {
            setContent(currentFile.content);
            setIsDirty(false);
        }
    }, [currentFile]);

    const handleContentChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
        setContent(e.target.value);
        setIsDirty(true);
    };

    const handleSave = () => {
        if (currentFile) {
            saveFile(currentFile.fileName, content);
            setIsDirty(false);
        }
    };

    if (!currentFile) {
        return <div>No file loaded</div>;
    }

    return (
        <div className="owl-editor">
            <div className="editor-header">
                <h3>{currentFile.fileName}</h3>
                <button 
                    onClick={handleSave} 
                    disabled={!isDirty}
                    className="save-btn"
                >
                    {isDirty ? 'Save Changes' : 'Saved'}
                </button>
            </div>
            <textarea
                value={content}
                onChange={handleContentChange}
                className="editor-textarea"
                placeholder="OWL content will appear here..."
            />
        </div>
    );
};

export default OWLEditor;