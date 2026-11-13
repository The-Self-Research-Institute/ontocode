import React from "react";

interface RenameProjectModalProps {
  newName: string;
  onNameChange: (value: string) => void;
  onRename: () => void;
  onCancel: () => void;
}

const RenameProjectModal: React.FC<RenameProjectModalProps> = ({ newName, onNameChange, onRename, onCancel }) => (
  <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
    <div className="bg-white rounded-lg shadow-xl max-w-md w-full p-6">
      <h3 className="text-xl font-semibold mb-4">Rename Project</h3>
      <div className="mb-4">
        <label className="block text-sm font-medium text-gray-700 mb-2">New Project Name *</label>
        <input
          type="text"
          value={newName}
          onChange={(e) => onNameChange(e.target.value)}
          className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500"
          placeholder="Enter new name"
          autoFocus
          onKeyPress={(e) => {
            if (e.key === "Enter") {
              onRename();
            }
          }}
        />
      </div>
      <div className="flex gap-3">
        <button
          onClick={onCancel}
          className="flex-1 px-4 py-2 border border-gray-300 rounded-lg hover:bg-gray-50"
        >
          Cancel
        </button>
        <button
          onClick={onRename}
          className="flex-1 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700"
        >
          Rename
        </button>
      </div>
    </div>
  </div>
);

export default RenameProjectModal;
