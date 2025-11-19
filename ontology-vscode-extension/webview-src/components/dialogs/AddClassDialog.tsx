import React, { useState } from 'react';

interface AddClassDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onCreate: (name: string) => void;
  type: 'subclass' | 'sibling';
}

const AddClassDialog: React.FC<AddClassDialogProps> = ({ 
  isOpen, 
  onClose, 
  onCreate,
  type 
}) => {
  const [name, setName] = useState('');
  
  if (!isOpen) return null;

  const handleCreate = () => {
    if (name.trim()) {
      onCreate(name.trim());
      setName('');
      onClose();
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleCreate();
    }
  };

  const handleClose = () => {
    setName('');
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-black mb-4">
          Create New {type === 'subclass' ? 'Subclass' : 'Sibling Class'}
        </h3>
        <div className="space-y-4 text-sm">
          <div>
            <label className="font-medium text-black block mb-2">Class Name</label>
            <input 
              type="text" 
              value={name} 
              onChange={e => setName(e.target.value)}
              onKeyPress={handleKeyPress}
              placeholder="Enter class name" 
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
              autoFocus
            />
          </div>
          <div>
            <label className="font-medium text-black block mb-2">IRI</label>
            <input 
              type="text" 
              disabled 
              value="(auto-generated from ontology IRI + class name)" 
              className="w-full px-3 py-2 border border-gray-200 bg-gray-50 rounded-md text-gray-500 text-xs" 
            />
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button 
            onClick={handleClose} 
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Cancel
          </button>
          <button 
            onClick={handleCreate} 
            className="px-4 py-2 text-sm bg-blue-600 text-white rounded-md hover:bg-blue-700"
          >
            Create
          </button>
        </div>
      </div>
    </div>
  );
};

export default AddClassDialog;
