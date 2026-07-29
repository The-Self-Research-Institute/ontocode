import React, { useState, useEffect, useMemo } from 'react';
import { Code, X, Info } from 'lucide-react';
import apiClient from '../../services/apiClient';

interface GCIEditorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (subClassExpression: string, superClassExpression: string) => Promise<void>;
  initialSubClass?: string;
  initialSuperClass?: string;
  editMode?: boolean;
  availableClasses?: Array<{ id: string; label: string }>;
  projectId?: string;
}

const GCIEditorDialog: React.FC<GCIEditorDialogProps> = ({ 
  isOpen, 
  onClose, 
  onSave, 
  initialSubClass = '', 
  initialSuperClass = '',
  editMode = false,
  availableClasses,
  projectId,
}) => {
  const [subClass, setSubClass] = useState(initialSubClass);
  const [superClass, setSuperClass] = useState(initialSuperClass);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [parseError, setParseError] = useState<string | null>(null);
  const [showSubClassSuggestions, setShowSubClassSuggestions] = useState(false);
  const [showSuperClassSuggestions, setShowSuperClassSuggestions] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setSubClass(initialSubClass);
      setSuperClass(initialSuperClass);
      setShowSubClassSuggestions(false);
      setShowSuperClassSuggestions(false);
    }
  }, [isOpen, initialSubClass, initialSuperClass]);

  // Derive suggestions via useMemo instead of useEffect+setState to avoid
  // infinite re-render loops when callers pass a new array reference each render.
  const subClassSuggestions = useMemo(() => {
    if (subClass && availableClasses && availableClasses.length > 0) {
      return availableClasses.filter(cls =>
        cls.label.toLowerCase().includes(subClass.toLowerCase()) ||
        cls.id.toLowerCase().includes(subClass.toLowerCase())
      ).slice(0, 10);
    }
    return [];
  }, [subClass, availableClasses]);

  const superClassSuggestions = useMemo(() => {
    if (superClass && availableClasses && availableClasses.length > 0) {
      return availableClasses.filter(cls =>
        cls.label.toLowerCase().includes(superClass.toLowerCase()) ||
        cls.id.toLowerCase().includes(superClass.toLowerCase())
      ).slice(0, 10);
    }
    return [];
  }, [superClass, availableClasses]);

  if (!isOpen) return null;

  const validateExpression = async (expression: string): Promise<string | null> => {
    if (!projectId || !expression.trim()) return null;
    try {
      const res = await apiClient.post<any>(`/api/ontology/${encodeURIComponent(projectId)}/expression/parse`, {
        expression: expression.trim(),
      });
      if (res?.success === false || res?.data?.success === false) {
        return res?.error || res?.data?.error || 'Invalid Manchester expression';
      }
      return null;
    } catch (error: any) {
      return error?.response?.data?.error || error?.message || 'Expression validation failed';
    }
  };

  const handleSave = async () => {
    if (!subClass.trim() || !superClass.trim()) return;

    setIsSubmitting(true);
    setParseError(null);
    try {
      await onSave(subClass.trim(), superClass.trim());
      onClose();
    } catch (error) {
      console.error('Failed to save GCI:', error);
      setParseError(error instanceof Error ? error.message : 'Failed to save GCI');
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
            <div className="relative">
              <label className="block text-xs font-bold text-gray-700 mb-1">SubClass Expression</label>
              <textarea
                value={subClass}
                onChange={e => {
                  setSubClass(e.target.value);
                  setShowSubClassSuggestions(true);
                }}
                onFocus={() => setShowSubClassSuggestions(true)}
                onBlur={() => setTimeout(() => setShowSubClassSuggestions(false), 200)}
                placeholder="e.g., Pizza or hasTopping some MeatTopping"
                className="w-full px-3 py-2 border border-gray-300 rounded text-sm font-mono focus:outline-none focus:ring-1 focus:ring-blue-500 min-h-[80px]"
              />
              {showSubClassSuggestions && subClassSuggestions.length > 0 && (
                <div className="absolute z-10 w-full mt-1 bg-white border border-gray-300 rounded shadow-lg max-h-[150px] overflow-y-auto">
                  {subClassSuggestions.map((cls, idx) => (
                    <button
                      key={idx}
                      type="button"
                      onMouseDown={() => {
                        setSubClass(cls.label);
                        setShowSubClassSuggestions(false);
                      }}
                      className="w-full px-3 py-2 text-left text-xs hover:bg-blue-50 flex flex-col"
                    >
                      <span className="font-semibold text-gray-800">{cls.label}</span>
                      <span className="text-[10px] text-gray-500 font-mono truncate">{cls.id}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>

            <div className="flex justify-center">
              <div className="text-xs font-bold text-gray-400 italic">SubClassOf</div>
            </div>

            <div className="relative">
              <label className="block text-xs font-bold text-gray-700 mb-1">SuperClass Expression</label>
              <textarea
                value={superClass}
                onChange={e => {
                  setSuperClass(e.target.value);
                  setShowSuperClassSuggestions(true);
                }}
                onFocus={() => setShowSuperClassSuggestions(true)}
                onBlur={() => setTimeout(() => setShowSuperClassSuggestions(false), 200)}
                placeholder="e.g., NonVegetarianPizza"
                className="w-full px-3 py-2 border border-gray-300 rounded text-sm font-mono focus:outline-none focus:ring-1 focus:ring-blue-500 min-h-[80px]"
              />
              {showSuperClassSuggestions && superClassSuggestions.length > 0 && (
                <div className="absolute z-10 w-full mt-1 bg-white border border-gray-300 rounded shadow-lg max-h-[150px] overflow-y-auto">
                  {superClassSuggestions.map((cls, idx) => (
                    <button
                      key={idx}
                      type="button"
                      onMouseDown={() => {
                        setSuperClass(cls.label);
                        setShowSuperClassSuggestions(false);
                      }}
                      className="w-full px-3 py-2 text-left text-xs hover:bg-blue-50 flex flex-col"
                    >
                      <span className="font-semibold text-gray-800">{cls.label}</span>
                      <span className="text-[10px] text-gray-500 font-mono truncate">{cls.id}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>

          {parseError && (
            <div className="bg-red-50 border border-red-200 text-red-700 text-xs p-2 rounded">
              {parseError}
            </div>
          )}
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
