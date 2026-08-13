import React, { useState, useEffect } from 'react';
import { Link, X } from 'lucide-react';

interface EditOntologyIRIDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (ontologyIri: string, versionIri: string) => Promise<void>;
  initialOntologyIri: string;
  initialVersionIri: string;
}

const EditOntologyIRIDialog: React.FC<EditOntologyIRIDialogProps> = ({
  isOpen,
  onClose,
  onSave,
  initialOntologyIri,
  initialVersionIri
}) => {
  const [ontologyIri, setOntologyIri] = useState(initialOntologyIri);
  const [versionIri, setVersionIri] = useState(initialVersionIri);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setOntologyIri(initialOntologyIri);
      setVersionIri(initialVersionIri);
    }
  }, [isOpen, initialOntologyIri, initialVersionIri]);

  if (!isOpen) return null;

  const handleSave = async () => {
    if (!ontologyIri.trim()) return;

    setIsSubmitting(true);
    try {
      await onSave(ontologyIri.trim(), versionIri.trim());
      onClose();
    } catch (error) {
      console.error('Failed to save Ontology IRIs:', error);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[110]"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div className="bg-[#F0F0F0] rounded-lg shadow-2xl w-[500px] flex flex-col overflow-hidden border border-gray-400" onClick={e => e.stopPropagation()}>
        {}
        <div className="bg-white px-4 py-2 flex justify-between items-center border-b border-gray-300">
          <div className="flex items-center gap-2">
            <div className="w-5 h-5 bg-green-600 rounded flex items-center justify-center">
              <Link size={12} className="text-white" />
            </div>
            <span className="text-sm font-medium text-gray-800">Edit Ontology IRIs</span>
          </div>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            <X size={18} />
          </button>
        </div>

        <div className="p-4 bg-white flex-1 space-y-4">
          <div>
            <label className="block text-xs font-bold text-gray-700 mb-1">Ontology IRI</label>
            <input
              type="text"
              value={ontologyIri}
              onChange={e => setOntologyIri(e.target.value)}
              placeholder="http://www.semanticweb.org/ontology"
              className="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
              autoFocus
            />
            <p className="text-[10px] text-gray-500 mt-1">The unique identifier for this ontology.</p>
          </div>

          <div>
            <label className="block text-xs font-bold text-gray-700 mb-1">Ontology Version IRI (Optional)</label>
            <input
              type="text"
              value={versionIri}
              onChange={e => setVersionIri(e.target.value)}
              placeholder="http://www.semanticweb.org/ontology/1.0.0"
              className="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <p className="text-[10px] text-gray-500 mt-1">The identifier for this specific version of the ontology.</p>
          </div>
        </div>

        {}
        <div className="bg-[#F0F0F0] p-4 flex justify-end gap-2 border-t border-gray-300">
          <button
            onClick={handleSave}
            disabled={isSubmitting || !ontologyIri.trim()}
            className={`px-6 py-1.5 bg-white border border-gray-400 rounded text-sm hover:bg-gray-50 text-black min-w-[80px] flex items-center justify-center ${isSubmitting || !ontologyIri.trim() ? 'opacity-50 cursor-not-allowed' : ''}`}
          >
            {isSubmitting ? 'Saving...' : 'OK'}
          </button>
          <button
            onClick={onClose}
            className="px-6 py-1.5 bg-white border border-gray-400 rounded text-sm hover:bg-gray-50 text-black min-w-[80px]"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
};

export default EditOntologyIRIDialog;
