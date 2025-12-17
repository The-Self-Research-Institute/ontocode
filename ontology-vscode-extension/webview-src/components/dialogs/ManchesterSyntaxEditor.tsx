import React, { useState, useEffect } from 'react';
import { X, Check, HelpCircle, AlertCircle } from 'lucide-react';
import apiClient from '../../services/apiClient';

interface ManchesterSyntaxEditorProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string) => void;
  title?: string;
  initialValue?: string;
  projectId: string;
}

const ManchesterSyntaxEditor: React.FC<ManchesterSyntaxEditorProps> = ({
  isOpen,
  onClose,
  onConfirm,
  title = "Class Expression Editor",
  initialValue = "",
  projectId
}) => {
  const [expression, setExpression] = useState(initialValue);
  const [isValid, setIsValid] = useState(true);
  const [errorMsg, setErrorMsg] = useState("");
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [cursorPos, setCursorPos] = useState(0);

  useEffect(() => {
    if (isOpen) {
      setExpression(initialValue);
      setIsValid(true);
      setErrorMsg("");
    }
  }, [isOpen, initialValue]);

  // Simple validation (placeholder for real parser)
  const validateExpression = (expr: string) => {
    if (!expr.trim()) {
      setIsValid(false);
      setErrorMsg("Expression cannot be empty");
      return false;
    }
    
    // Basic syntax check - balanced parentheses
    let balance = 0;
    for (const char of expr) {
      if (char === '(') balance++;
      if (char === ')') balance--;
      if (balance < 0) {
        setIsValid(false);
        setErrorMsg("Unbalanced parentheses");
        return false;
      }
    }
    if (balance !== 0) {
      setIsValid(false);
      setErrorMsg("Unbalanced parentheses");
      return false;
    }

    setIsValid(true);
    setErrorMsg("");
    return true;
  };

  const handleConfirm = () => {
    if (validateExpression(expression)) {
      onConfirm(expression);
      onClose();
    }
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const val = e.target.value;
    setExpression(val);
    setCursorPos(e.target.selectionStart);
    validateExpression(val);
    
    // Simple autocomplete logic could go here
    // For now, just clear suggestions
    setSuggestions([]);
  };

  const insertKeyword = (keyword: string) => {
    const before = expression.substring(0, cursorPos);
    const after = expression.substring(cursorPos);
    const newExpr = `${before} ${keyword} ${after}`;
    setExpression(newExpr);
    // Move cursor after inserted keyword
    const newPos = cursorPos + keyword.length + 2;
    // We'd need a ref to the textarea to set selection range properly
  };

  if (!isOpen) return null;

  const keywords = ["and", "or", "not", "some", "only", "min", "max", "exactly", "value"];

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="rounded-lg shadow-xl w-full max-w-2xl mx-4 flex flex-col max-h-[80vh]" style={{ backgroundColor: 'var(--surface-1)' }}>
        <div className="flex justify-between items-center p-4 border-b" style={{ borderColor: 'var(--border)' }}>
          <h3 className="text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</h3>
          <button onClick={onClose} className="hover:opacity-70" style={{ color: 'var(--text-secondary)' }}>
            <X size={20} />
          </button>
        </div>

        <div className="p-4 flex-1 overflow-y-auto">
          <div className="mb-4">
            <label className="block text-sm font-medium mb-1" style={{ color: 'var(--text-primary)' }}>
              Manchester OWL Syntax
            </label>
            <div className="relative">
              <textarea
                value={expression}
                onChange={handleInputChange}
                className={`w-full h-32 p-3 border rounded-md font-mono text-sm focus:ring-2 focus:outline-none ${
                  isValid ? 'focus:ring-purple-500' : 'border-red-300 focus:ring-red-500'
                }`}
                style={{ 
                  color: 'var(--text-primary)', 
                  backgroundColor: 'var(--surface-2)', 
                  borderColor: isValid ? 'var(--border)' : '#fca5a5' 
                }}
                placeholder="e.g. Cell and hasPart some Nucleus"
                autoFocus
              />
              {!isValid && (
                <div className="absolute bottom-2 right-2 text-red-500 flex items-center text-xs bg-white px-1 rounded">
                  <AlertCircle size={12} className="mr-1" />
                  {errorMsg}
                </div>
              )}
            </div>
          </div>

          <div className="mb-4">
            <label className="block text-xs font-bold uppercase tracking-wider mb-2" style={{ color: 'var(--text-secondary)' }}>
              Keywords
            </label>
            <div className="flex flex-wrap gap-2">
              {keywords.map(kw => (
                <button
                  key={kw}
                  onClick={() => insertKeyword(kw)}
                  className="px-2 py-1 text-xs rounded font-mono border"
                  style={{ 
                    backgroundColor: 'var(--surface-2)', 
                    color: 'var(--text-primary)', 
                    borderColor: 'var(--border)' 
                  }}
                  onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--hover-overlay)'}
                  onMouseLeave={e => e.currentTarget.style.backgroundColor = 'var(--surface-2)'}
                >
                  {kw}
                </button>
              ))}
            </div>
          </div>

          <div className="bg-blue-50 p-3 rounded-md border border-blue-100 text-xs text-blue-800">
            <div className="flex items-start gap-2">
              <HelpCircle size={14} className="mt-0.5 flex-shrink-0" />
              <div>
                <p className="font-semibold mb-1">Examples:</p>
                <ul className="list-disc pl-4 space-y-1">
                  <li><code>Cell and hasPart some Nucleus</code> (Intersection & Existential)</li>
                  <li><code>locatedIn only PlantCell</code> (Universal)</li>
                  <li><code>hasPart max 1 Nucleus</code> (Cardinality)</li>
                  <li><code>ProkaryoticCell or EukaryoticCell</code> (Union)</li>
                </ul>
              </div>
            </div>
          </div>
        </div>

        <div className="p-4 border-t flex justify-end gap-2 rounded-b-lg" style={{ borderColor: 'var(--border)', backgroundColor: 'var(--surface-2)' }}>
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium border rounded-md"
            style={{ 
              color: 'var(--text-primary)', 
              backgroundColor: 'var(--surface-1)', 
              borderColor: 'var(--border)' 
            }}
            onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--hover-overlay)'}
            onMouseLeave={e => e.currentTarget.style.backgroundColor = 'var(--surface-1)'}
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!isValid || !expression.trim()}
            className="px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded-md hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
          >
            <Check size={16} />
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default ManchesterSyntaxEditor;
