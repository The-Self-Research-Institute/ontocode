import React, { useState, useEffect } from 'react';
import { Check, X } from 'lucide-react';

interface ManchesterSyntaxEditorProps {
  initialValue?: string;
  onSave: (value: string) => void;
  onCancel: () => void;
}

const ManchesterSyntaxEditor: React.FC<ManchesterSyntaxEditorProps> = ({ initialValue = '', onSave, onCancel }) => {
  const [value, setValue] = useState(initialValue);

  useEffect(() => {
    setValue(initialValue);
  }, [initialValue]);

  const handleSave = () => {
    if (value.trim()) {
      onSave(value.trim());
    }
  };

  return (
    <div className="p-2 border border-purple-300 bg-purple-50 rounded-md my-1">
      <textarea
        className="w-full h-20 p-2 font-mono text-xs border border-gray-300 rounded-md bg-white focus:ring-2 focus:ring-purple-500 focus:border-transparent"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder="Enter Manchester syntax... e.g., hasTopping some MozzarellaTopping"
        autoFocus
      />
      <div className="flex justify-end gap-2 mt-2">
        <button onClick={onCancel} className="p-1.5 text-xs bg-gray-200 text-gray-700 rounded-md hover:bg-gray-300">
            <X size={14} />
        </button>
        <button onClick={handleSave} className="p-1.5 text-xs bg-purple-600 text-white rounded-md hover:bg-purple-700">
            <Check size={14} />
        </button>
      </div>
    </div>
  );
};

export default ManchesterSyntaxEditor;
