import React, { useState } from 'react';

interface CreateIndividualModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreate: (name: string) => void;
}

const CreateIndividualModal: React.FC<CreateIndividualModalProps> = ({ isOpen, onClose, onCreate }) => {
  const [name, setName] = useState('');
  
  if (!isOpen) return null;

  const handleCreate = () => {
    if (name.trim()) {
      onCreate(name.trim());
      setName('');
      onClose();
    }
  };

  const handleClose = () => {
    setName('');
    onClose();
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) handleClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Create a new Named Individual</h3>
        <div className="space-y-4 text-sm">
          <div>
            <label className="font-medium text-gray-700">Name</label>
            <input 
              type="text" 
              value={name} 
              onChange={e => setName(e.target.value)} 
              onKeyDown={e => e.key === 'Enter' && handleCreate()}
              placeholder="Short name or full IRI" 
              className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500" 
              autoFocus
            />
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button onClick={handleClose} className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300">
            Cancel
          </button>
          <button onClick={handleCreate} className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700">
            Create
          </button>
        </div>
      </div>
    </div>
  );
};

export default CreateIndividualModal;
