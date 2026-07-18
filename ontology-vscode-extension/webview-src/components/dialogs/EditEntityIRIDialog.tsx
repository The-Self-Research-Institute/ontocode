import React, { useEffect, useState } from 'react';
import { X } from 'lucide-react';

interface EditEntityIRIDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (newIri: string) => Promise<void>;
  currentIri: string;
  entityLabel?: string;
}

const EditEntityIRIDialog: React.FC<EditEntityIRIDialogProps> = ({
  isOpen,
  onClose,
  onSave,
  currentIri,
  entityLabel,
}) => {
  const [newIri, setNewIri] = useState(currentIri);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      setNewIri(currentIri);
      setError(null);
    }
  }, [isOpen, currentIri]);

  if (!isOpen) return null;

  const handleSave = async () => {
    const trimmed = newIri.trim();
    if (!trimmed) {
      setError('IRI is required');
      return;
    }
    if (trimmed === currentIri) {
      onClose();
      return;
    }
    setIsSubmitting(true);
    setError(null);
    try {
      await onSave(trimmed);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to change IRI');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[120]" onClick={onClose}>
      <div
        className="bg-white rounded-lg shadow-xl w-full max-w-lg mx-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-4 py-3 border-b flex justify-between items-center">
          <h3 className="text-sm font-semibold text-gray-900">
            Change IRI{entityLabel ? `: ${entityLabel}` : ''}
          </h3>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            <X size={18} />
          </button>
        </div>
        <div className="p-4 space-y-3">
          <p className="text-xs text-gray-600">
            rename: updates this entity&apos;s IRI in every axiom that references it.
          </p>
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">Current IRI</label>
            <input
              type="text"
              value={currentIri}
              readOnly
              className="w-full px-3 py-2 border border-gray-200 rounded text-xs font-mono bg-gray-50 text-gray-600"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-700 mb-1">New IRI</label>
            <input
              type="text"
              value={newIri}
              onChange={(e) => setNewIri(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded text-sm font-mono focus:outline-none focus:ring-1 focus:ring-blue-500"
              placeholder="http://example.org/ontology#MyClass"
            />
          </div>
          {error && <div className="text-xs text-red-600 bg-red-50 border border-red-200 rounded p-2">{error}</div>}
        </div>
        <div className="px-4 py-3 border-t flex justify-end gap-2">
          <button onClick={onClose} className="px-4 py-1.5 text-sm border border-gray-300 rounded hover:bg-gray-50">
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={isSubmitting}
            className="px-4 py-1.5 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
          >
            {isSubmitting ? 'Saving...' : 'Change IRI'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default EditEntityIRIDialog;
