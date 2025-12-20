import React, { useState, useEffect } from 'react';
import { Code, X, Info } from 'lucide-react';

interface GCIEditorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (subClassExpression: string, superClassExpression: string) => Promise<void>;
  initialSubClass?: string;
  initialSuperClass?: string;
  editMode?: boolean;
}

const GCIEditorDialog: React.FC<GCIEditorDialogProps> = ({ 
  isOpen, 
  onClose, 
  onSave, 
  initialSubClass = '', 
  initialSuperClass = '',
  editMode = false 
}) => {
  const [subClass, setSubClass] = useState(initialSubClass);
  const [superClass, setSuperClass] = useState(initialSuperClass);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setSubClass(initialSubClass);
      setSuperClass(initialSuperClass);
    }
  }, [isOpen, initialSubClass, initialSuperClass]);

  if (!isOpen) return null;

  const handleSave = async () => {
    if (!subClass.trim() || !superClass.trim()) return;

    setIsSubmitting(true);
    try {
      await onSave(subClass.trim(), superClass.trim());
      onClose();
    } catch (error) {
      console.error('Failed to save GCI:', error);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[110]" onClick={onClose}>
      <div className="bg-[#F0F0F0] rounded-lg shadow-2xl w-[600px] flex flex-col overflow-hidden border border-gray-400" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="bg-white px-4 py-2 flex justify-between items-center border-b border-gray-300">
          <div className="flex items-center gap-2">
            <div className="w-5 h-5 bg-orange-600 rounded flex items-center justify-center">
              <Code size={12} className="text-white" />
            </div>
            <span className="text-sm font-medium text-gray-800">{editMode ? 'Edit General Class Axiom' : 'Create General Class Axiom'}</span>
          </div>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">
            <X size={18} />
          </button>
        </div>

        <div className="p-4 bg-white flex-1 space-y-4">
          <div className="bg-blue-50 border border-blue-200 p-3 rounded flex gap-3">
            <Info size={16} className="text-blue-500 flex-shrink-0 mt-0.5" />
            <p className="text-[11px] text-blue-700">
              A General Class Axiom (GCI) allows you to define a SubClassOf relationship between two complex class expressions. 
              Use Manchester Syntax for the expressions.
            </p>
          </div>

          <div className="space-y-4">
            <div>
              <label className="block text-xs font-bold text-gray-700 mb-1">SubClass Expression</label>
              <textarea
                value={subClass}
                onChange={e => setSubClass(e.target.value)}
                placeholder="e.g., Pizza and hasTopping some MeatTopping"
                className="w-full px-3 py-2 border border-gray-300 rounded text-sm font-mono focus:outline-none focus:ring-1 focus:ring-blue-500 min-h-[80px]"
              />
            </div>

            <div className="flex justify-center">
              <div className="text-xs font-bold text-gray-400 italic">SubClassOf</div>
            </div>

            <div>
              <label className="block text-xs font-bold text-gray-700 mb-1">SuperClass Expression</label>
              <textarea
                value={superClass}
                onChange={e => setSuperClass(e.target.value)}
                placeholder="e.g., NonVegetarianPizza"
                className="w-full px-3 py-2 border border-gray-300 rounded text-sm font-mono focus:outline-none focus:ring-1 focus:ring-blue-500 min-h-[80px]"
              />
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="bg-[#F0F0F0] p-4 flex justify-end gap-2 border-t border-gray-300">
          <button
            onClick={handleSave}
            disabled={isSubmitting || !subClass.trim() || !superClass.trim()}
            className={`px-6 py-1.5 bg-white border border-gray-400 rounded text-sm hover:bg-gray-50 text-black min-w-[80px] flex items-center justify-center ${isSubmitting || !subClass.trim() || !superClass.trim() ? 'opacity-50 cursor-not-allowed' : ''}`}
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

export default GCIEditorDialog;
