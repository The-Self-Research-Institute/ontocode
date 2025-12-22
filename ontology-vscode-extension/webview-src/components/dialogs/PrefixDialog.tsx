import React, { useState, useEffect } from 'react';
import { X, Save } from 'lucide-react';

interface PrefixDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (prefix: string, iri: string) => void;
  initialPrefix?: string;
  initialIRI?: string;
}

const PrefixDialog: React.FC<PrefixDialogProps> = ({
  isOpen,
  onClose,
  onSave,
  initialPrefix = '',
  initialIRI = ''
}) => {
  const [prefix, setPrefix] = useState(initialPrefix);
  const [iri, setIri] = useState(initialIRI);

  useEffect(() => {
    if (isOpen) {
      setPrefix(initialPrefix);
      setIri(initialIRI);
    }
  }, [isOpen, initialPrefix, initialIRI]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="bg-white rounded-lg shadow-2xl w-full max-w-md overflow-hidden border border-gray-200">
        <div className="flex items-center justify-between px-4 py-3 border-b bg-gray-50">
          <h3 className="text-sm font-bold text-gray-800">
            {initialPrefix ? 'Edit Prefix' : 'Add Prefix'}
          </h3>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 transition-colors">
            <X size={18} />
          </button>
        </div>

        <div className="p-4 space-y-4">
          <div className="space-y-1.5">
            <label className="text-[11px] font-bold text-gray-500 uppercase tracking-wider">Prefix Name</label>
            <input
              type="text"
              value={prefix}
              onChange={(e) => setPrefix(e.target.value)}
              placeholder="e.g. owl, rdfs, myont"
              className="w-full px-3 py-2 text-xs border border-gray-300 rounded focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all font-mono"
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-[11px] font-bold text-gray-500 uppercase tracking-wider">Namespace IRI</label>
            <input
              type="text"
              value={iri}
              onChange={(e) => setIri(e.target.value)}
              placeholder="e.g. http://www.w3.org/2002/07/owl#"
              className="w-full px-3 py-2 text-xs border border-gray-300 rounded focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all font-mono"
            />
          </div>
        </div>

        <div className="flex justify-end gap-2 px-4 py-3 bg-gray-50 border-t">
          <button
            onClick={onClose}
            className="px-3 py-1.5 text-xs font-medium text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded transition-all"
          >
            Cancel
          </button>
          <button
            onClick={() => {
              if (prefix && iri) {
                onSave(prefix, iri);
                onClose();
              }
            }}
            disabled={!prefix || !iri}
            className="flex items-center gap-1.5 px-4 py-1.5 text-xs font-bold text-white bg-blue-600 hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed rounded shadow-sm transition-all"
          >
            <Save size={14} />
            SAVE PREFIX
          </button>
        </div>
      </div>
    </div>
  );
};

export default PrefixDialog;
