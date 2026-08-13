import React, { useState, useEffect } from 'react';
import { X, Check } from 'lucide-react';

interface IRIEditorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  currentIRI: string;
  currentLabel: string;
  entityType: 'Class' | 'ObjectProperty' | 'DataProperty' | 'Individual' | 'Datatype' | 'AnnotationProperty';
  onSave: (newIRI: string, newLabel: string) => void;
}

const IRIEditorDialog: React.FC<IRIEditorDialogProps> = ({
  isOpen,
  onClose,
  currentIRI,
  currentLabel,
  entityType,
  onSave
}) => {
  const [label, setLabel] = useState(currentLabel);

  useEffect(() => {
    if (isOpen) {
      setLabel(currentLabel);
    }
  }, [isOpen, currentLabel]);

  if (!isOpen) return null;

  const handleSave = () => {
    if (label.trim()) {
      onSave(currentIRI, label.trim());
      onClose();
    }
  };

  const handleClose = () => {
    setLabel(currentLabel);
    onClose();
  };

  const getIRIParts = (iriValue: string) => {
    const hashIndex = iriValue.lastIndexOf('#');
    const slashIndex = iriValue.lastIndexOf('/');
    const separatorIndex = Math.max(hashIndex, slashIndex);

    if (separatorIndex > 0) {
      return {
        namespace: iriValue.substring(0, separatorIndex + 1),
        localName: iriValue.substring(separatorIndex + 1)
      };
    }

    const colonIndex = iriValue.indexOf(':');
    if (colonIndex > 0) {
      return {
        namespace: iriValue.substring(0, colonIndex + 1),
        localName: iriValue.substring(colonIndex + 1)
      };
    }

    return { namespace: '', localName: iriValue };
  };

  const { namespace, localName } = getIRIParts(currentIRI);
  const hasChanged = label !== currentLabel;

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) handleClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col" onClick={e => e.stopPropagation()}>
        {}
        <div className="p-4 border-b border-gray-200 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h3 className="text-lg font-semibold text-black">Edit Label</h3>
            <span className="text-xs px-2 py-1 bg-purple-100 text-purple-700 rounded font-semibold">
              {entityType}
            </span>
          </div>
          <button
            onClick={handleClose}
            className="p-1 rounded hover:bg-gray-100 text-gray-500"
            title="Close"
          >
            <X size={20} />
          </button>
        </div>

        {}
        <div className="p-4 space-y-4">
          {}
          <div>
            <label className="text-sm font-semibold text-gray-800 block mb-2">
              Display Label (rdfs:label)
            </label>
            <input
              type="text"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm"
              placeholder="Enter display label"
            />
            <p className="text-xs text-gray-500 mt-1">
              This is the human-readable name shown in the interface
            </p>
          </div>

          {}
          <div>
            <label className="text-sm font-semibold text-gray-800 block mb-2">
              Full IRI <span className="text-xs font-normal text-gray-400">(read-only)</span>
            </label>
            <div className="w-full px-3 py-2 border border-gray-200 rounded-md bg-gray-50 text-sm font-mono text-gray-600 break-all select-all">
              {currentIRI}
            </div>
            {namespace && (
              <div className="mt-2 p-2 bg-gray-50 rounded text-xs font-mono space-y-1">
                <div><span className="text-gray-500">Namespace:</span>{' '}<span className="text-blue-600">{namespace}</span></div>
                <div><span className="text-gray-500">Local Name:</span>{' '}<span className="text-green-600">{localName}</span></div>
              </div>
            )}
            <p className="text-xs text-gray-400 mt-1">IRI renaming is not yet supported. Only the label can be changed.</p>
          </div>
        </div>

        {}
        <div className="p-4 border-t border-gray-200 flex justify-between items-center">
          <div className="text-xs text-gray-600">
            {hasChanged && (
              <span className="text-amber-600">Unsaved changes</span>
            )}
          </div>
          <div className="flex gap-3">
            <button
              onClick={handleClose}
              className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
            >
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={!label.trim() || !hasChanged}
              className={`px-4 py-2 text-sm rounded-md flex items-center gap-2 ${
                !label.trim() || !hasChanged
                  ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
                  : 'bg-purple-600 text-white hover:bg-purple-700'
              }`}
            >
              <Check size={16} />
              Save Changes
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default IRIEditorDialog;
