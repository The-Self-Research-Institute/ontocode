import React, { useState, useEffect } from 'react';
import { Check, X, Loader } from 'lucide-react';

interface ManchesterSyntaxEditorProps {
  initialValue?: string;
  onSave: (value: string) => Promise<void> | void;
  onCancel: () => void;
}

const ManchesterSyntaxEditor: React.FC<ManchesterSyntaxEditorProps> = ({ initialValue = '', onSave, onCancel }) => {
  const [value, setValue] = useState(initialValue);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    setValue(initialValue);
  }, [initialValue]);

  const handleSave = async () => {
    if (value.trim()) {
      setIsSaving(true);
      try {
        await onSave(value.trim());
      } finally {
        setIsSaving(false);
      }
    }
  };

  return (
    <div className="p-2 border border-purple-300 bg-purple-50 rounded-md my-1">
      <textarea
        className="w-full h-20 p-2 font-mono text-xs border border-gray-300 rounded-md bg-white text-black focus:ring-2 focus:ring-purple-500 focus:border-transparent"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder="Enter Manchester syntax... e.g., hasTopping some MozzarellaTopping"
        data-editing="axiom"
        autoFocus
        disabled={isSaving}
      />
      <div className="flex justify-end gap-2 mt-2">
        <button onClick={onCancel} disabled={isSaving} className="p-1.5 text-xs bg-gray-200 text-gray-700 rounded-md hover:bg-gray-300 disabled:opacity-50">
            <X size={14} />
        </button>
        <button onClick={handleSave} disabled={isSaving} className="p-1.5 text-xs bg-purple-600 text-white rounded-md hover:bg-purple-700 disabled:opacity-50">
            {isSaving ? <Loader size={14} className="animate-spin" /> : <Check size={14} />}
        </button>
      </div>
    </div>
  );
};

export default ManchesterSyntaxEditor;
