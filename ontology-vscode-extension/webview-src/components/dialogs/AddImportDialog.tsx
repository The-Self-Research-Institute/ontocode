import React, { useState } from 'react';
import { Globe, FileCode, Link as LinkIcon, X } from 'lucide-react';

interface AddImportDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onAdd: (importIri: string) => Promise<void>;
}

const AddImportDialog: React.FC<AddImportDialogProps> = ({ isOpen, onClose, onAdd }) => {
  const [importIri, setImportIri] = useState('');
  const [importType, setImportType] = useState<'url' | 'local'>('url');
  const [isSubmitting, setIsSubmitting] = useState(false);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!importIri.trim()) return;

    setIsSubmitting(true);
    try {
      await onAdd(importIri.trim());
      setImportIri('');
      onClose();
    } catch (error) {
      console.error('Failed to add import:', error);
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
        {/* Header */}
        <div className="bg-white px-4 py-2 flex justify-between items-center border-b border-gray-300">
          <div className="flex items-center gap-2">
            <div className="w-5 h-5 bg-blue-600 rounded flex items-center justify-center">
              <Globe size={12} className="text-white" />
            </div>
            <span className="text-sm font-medium text-gray-800">Add Ontology Import</span>
          </div>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            <X size={18} />
          </button>
        </div>

        <div className="p-4 bg-white flex-1">
          <div className="mb-4">
            <p className="text-xs text-gray-600 mb-4">
              Select how you would like to import an ontology. You can import from a web URL or a local file IRI.
            </p>
            
            <div className="space-y-3">
              <label className={`flex items-center gap-3 p-3 border rounded-md cursor-pointer transition-colors ${importType === 'url' ? 'border-blue-500 bg-blue-50' : 'border-gray-200 hover:bg-gray-50'}`}>
                <input 
                  type="radio" 
                  name="importType" 
                  checked={importType === 'url'} 
                  onChange={() => setImportType('url')}
                  className="text-blue-600"
                />
                <div className="flex-1">
                  <div className="flex items-center gap-2 text-sm font-medium text-gray-800">
                    <LinkIcon size={14} />
                    Import from a URL
                  </div>
                  <div className="text-[11px] text-gray-500">Download and import an ontology from a web address</div>
                </div>
              </label>

              <label className={`flex items-center gap-3 p-3 border rounded-md cursor-pointer transition-colors ${importType === 'local' ? 'border-blue-500 bg-blue-50' : 'border-gray-200 hover:bg-gray-50'}`}>
                <input 
                  type="radio" 
                  name="importType" 
                  checked={importType === 'local'} 
                  onChange={() => setImportType('local')}
                  className="text-blue-600"
                />
                <div className="flex-1">
                  <div className="flex items-center gap-2 text-sm font-medium text-gray-800">
                    <FileCode size={14} />
                    Import from a local file
                  </div>
                  <div className="text-[11px] text-gray-500">Import an ontology from a local file path or file IRI</div>
                </div>
              </label>
            </div>
          </div>

          <div className="mt-6">
            <label className="block text-xs font-bold text-gray-700 mb-1">
              {importType === 'url' ? 'Ontology URL (IRI)' : 'Local File IRI'}
            </label>
            <input
              type="text"
              value={importIri}
              onChange={e => setImportIri(e.target.value)}
              placeholder={importType === 'url' ? 'http://example.com/ontology.owl' : 'file:///C:/ontologies/my-ontology.owl'}
              className="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
              autoFocus
            />
          </div>
        </div>

        {/* Footer */}
        <div className="bg-[#F0F0F0] p-4 flex justify-end gap-2 border-t border-gray-300">
          <button
            onClick={handleSubmit}
            disabled={isSubmitting || !importIri.trim()}
            className={`px-6 py-1.5 bg-white border border-gray-400 rounded text-sm hover:bg-gray-50 text-black min-w-[80px] flex items-center justify-center ${isSubmitting || !importIri.trim() ? 'opacity-50 cursor-not-allowed' : ''}`}
          >
            {isSubmitting ? 'Adding...' : 'OK'}
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

export default AddImportDialog;
