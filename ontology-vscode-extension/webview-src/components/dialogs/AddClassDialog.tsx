import React, { useState } from 'react';

interface AddClassDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onCreate: (name: string) => void;
  type: 'subclass' | 'sibling';
  parentLabel: string;
  syncMode?: 'private' | 'public';
}

const AddClassDialog: React.FC<AddClassDialogProps> = ({
  isOpen,
  onClose,
  onCreate,
  type,
  parentLabel,
  syncMode = 'private',
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

  const getTitle = () => {
    return type === 'subclass' ? 'Create New Subclass' : 'Create New Sibling Class';
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-black mb-4">
          {getTitle()}
        </h3>
        <div className="space-y-4 text-sm">
          <div className="bg-amber-50 border border-amber-100 rounded-lg p-3">
            <p className="text-xs uppercase tracking-wide text-amber-700 font-semibold mb-1">
              {type === 'subclass' ? 'Parent class' : 'Sibling of'}
            </p>
            <p className="text-sm font-mono text-amber-900">{parentLabel}</p>
            <p className="text-[11px] text-amber-700 mt-1">
              New classes inherit placement from their parent in the hierarchy.
            </p>
          </div>
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
            <label className="font-medium text-black block mb-2">IRI Preview</label>
            <input
              type="text"
              disabled
              value="(auto-generated from ontology IRI + class name)"
              className="w-full px-3 py-2 border border-gray-200 bg-gray-50 rounded-md text-gray-500 text-xs"
            />
          </div>
          <p className="text-[11px] text-gray-500">
            Tip: Keep names short and descriptive. You can edit descriptions and relationships after creation.
          </p>
          <div className={`flex items-center gap-2 rounded-md px-3 py-2 text-xs font-medium border ${
            syncMode === 'public'
              ? 'bg-green-50 border-green-200 text-green-800'
              : 'bg-gray-50 border-gray-200 text-gray-600'
          }`}>
            <span className={`inline-block h-2 w-2 rounded-full flex-shrink-0 ${syncMode === 'public' ? 'bg-green-500' : 'bg-gray-400'}`} />
            {syncMode === 'public'
              ? 'Public Live — class will be applied to the shared ontology immediately.'
              : 'Private Draft — class will be saved to your draft until you publish.'}
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
