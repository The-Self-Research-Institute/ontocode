import React, { useState, useEffect } from 'react';
import { X, AlertCircle, Check } from 'lucide-react';

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
  const [iri, setIRI] = useState(currentIRI);
  const [label, setLabel] = useState(currentLabel);
  const [error, setError] = useState<string>('');

  useEffect(() => {
    if (isOpen) {
      setIRI(currentIRI);
      setLabel(currentLabel);
      setError('');
    }
  }, [isOpen, currentIRI, currentLabel]);

  if (!isOpen) return null;

  const validateIRI = (value: string): boolean => {
    if (!value.trim()) {
      setError('IRI cannot be empty');
      return false;
    }

    // Basic IRI validation
    try {
      new URL(value);
      setError('');
      return true;
    } catch {
      // Check if it's a valid IRI format (namespace:localName)
      if (value.includes(':') && value.split(':')[1]) {
        setError('');
        return true;
      }
      setError('Invalid IRI format. Must be a valid URL or namespace:localName format');
      return false;
    }
  };

  const handleIRIChange = (value: string) => {
    setIRI(value);
    validateIRI(value);
  };

  const handleSave = () => {
    if (validateIRI(iri) && label.trim()) {
      onSave(iri.trim(), label.trim());
      handleClose();
    }
  };

  const handleClose = () => {
    setIRI(currentIRI);
    setLabel(currentLabel);
    setError('');
    onClose();
  };

  // Extract namespace and local name from IRI
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

    // Try colon separator for prefix notation
    const colonIndex = iriValue.indexOf(':');
    if (colonIndex > 0) {
      return {
        namespace: iriValue.substring(0, colonIndex + 1),
        localName: iriValue.substring(colonIndex + 1)
      };
    }

    return { namespace: '', localName: iriValue };
  };

  const { namespace, localName } = getIRIParts(iri);
  const hasChanged = iri !== currentIRI || label !== currentLabel;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50" onClick={handleClose}>
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="p-4 border-b border-gray-200 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h3 className="text-lg font-semibold text-black">Edit IRI and Label</h3>
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

        {/* Content */}
        <div className="p-4 space-y-4">
          {/* Label */}
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

          {/* IRI */}
          <div>
            <label className="text-sm font-semibold text-gray-800 block mb-2">
              Full IRI
            </label>
            <input
              type="text"
              value={iri}
              onChange={(e) => handleIRIChange(e.target.value)}
              className={`w-full px-3 py-2 border rounded-md focus:ring-purple-500 text-sm font-mono ${
                error ? 'border-red-500 focus:border-red-500' : 'border-gray-300 focus:border-purple-500'
              }`}
              placeholder="http://example.com/ontology#EntityName"
            />
            {error && (
              <div className="flex items-center gap-1 mt-2 text-xs text-red-600">
                <AlertCircle size={14} />
                <span>{error}</span>
              </div>
            )}
            {!error && namespace && (
              <div className="mt-2 p-2 bg-gray-50 rounded text-xs font-mono space-y-1">
                <div>
                  <span className="text-gray-500">Namespace:</span>{' '}
                  <span className="text-blue-600">{namespace}</span>
                </div>
                <div>
                  <span className="text-gray-500">Local Name:</span>{' '}
                  <span className="text-green-600">{localName}</span>
                </div>
              </div>
            )}
          </div>

          {/* Warning */}
          <div className="p-3 bg-amber-50 border border-amber-200 rounded-lg">
            <div className="flex items-start gap-2">
              <AlertCircle size={16} className="text-amber-600 flex-shrink-0 mt-0.5" />
              <div className="text-xs text-amber-800">
                <p className="font-semibold mb-1">⚠️ Warning: Changing IRI is a destructive operation</p>
                <ul className="list-disc list-inside space-y-0.5 text-[11px]">
                  <li>All references to this entity will need to be updated</li>
                  <li>This may break relationships with other entities</li>
                  <li>Consider creating a new entity instead if possible</li>
                </ul>
              </div>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-200 flex justify-between items-center">
          <div className="text-xs text-gray-600">
            {hasChanged && (
              <div className="flex items-center gap-1 text-amber-600">
                <AlertCircle size={12} />
                <span>Unsaved changes</span>
              </div>
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
              disabled={!!error || !label.trim() || !iri.trim() || !hasChanged}
              className={`px-4 py-2 text-sm rounded-md flex items-center gap-2 ${
                !!error || !label.trim() || !iri.trim() || !hasChanged
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
