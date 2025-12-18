import React, { useEffect, useMemo, useState } from 'react';
import { X, Check } from 'lucide-react';

type Suggestion = { label: string; value: string };

interface PropertyAssertionDialogProps {
  isOpen: boolean;
  title: string;
  propertyLabel: string;
  targetLabel: string;
  isObjectProperty: boolean;
  propertySuggestions?: Suggestion[];
  targetSuggestions?: Suggestion[];
  onChange: (next: { propertyLabel: string; targetLabel: string; isObjectProperty: boolean }) => void;
  onCancel: () => void;
  onConfirm: () => void;
}

const PropertyAssertionDialog: React.FC<PropertyAssertionDialogProps> = ({
  isOpen,
  title,
  propertyLabel,
  targetLabel,
  isObjectProperty,
  propertySuggestions = [],
  targetSuggestions = [],
  onChange,
  onCancel,
  onConfirm
}) => {
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    if (isOpen) setTouched(false);
  }, [isOpen]);

  const canConfirm = propertyLabel.trim().length > 0 && targetLabel.trim().length > 0;

  const propertyListId = useMemo(() => `prop-suggestions-${Math.random().toString(16).slice(2)}`, []);
  const targetListId = useMemo(() => `target-suggestions-${Math.random().toString(16).slice(2)}`, []);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={onCancel}>
      <div
        className="w-full mx-4 rounded-lg shadow-xl flex flex-col"
        style={{
          backgroundColor: 'var(--vscode-editor-background)',
          color: 'var(--vscode-foreground)',
          maxWidth: 'min(860px, calc(100vw - 32px))',
          maxHeight: 'min(520px, calc(100vh - 32px))',
          resize: 'both',
          overflow: 'auto'
        }}
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-3 border-b" style={{ borderColor: 'var(--vscode-panel-border)' }}>
          <div className="font-semibold">{title}</div>
          <button
            onClick={onCancel}
            className="p-1 rounded"
            style={{ color: 'var(--vscode-descriptionForeground)' }}
            title="Close"
          >
            <X size={18} />
          </button>
        </div>

        <div className="p-4 flex-1 min-h-0">
          <div className="flex items-center gap-3 mb-3">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="radio"
                name="propType"
                checked={isObjectProperty}
                onChange={() => onChange({ propertyLabel, targetLabel, isObjectProperty: true })}
              />
              Object property
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="radio"
                name="propType"
                checked={!isObjectProperty}
                onChange={() => onChange({ propertyLabel, targetLabel, isObjectProperty: false })}
              />
              Data property
            </label>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <input
                type="text"
                placeholder={isObjectProperty ? 'Enter object property name' : 'Enter data property name'}
                value={propertyLabel}
                list={propertySuggestions.length ? propertyListId : undefined}
                onChange={e => onChange({ propertyLabel: e.target.value, targetLabel, isObjectProperty })}
                onBlur={() => setTouched(true)}
                className="w-full px-3 py-2 rounded border text-sm"
              />
              {propertySuggestions.length > 0 && (
                <datalist id={propertyListId}>
                  {propertySuggestions.map(s => (
                    <option key={s.value} value={s.label} />
                  ))}
                </datalist>
              )}
              {!propertyLabel.trim() && touched && (
                <div className="text-xs mt-1" style={{ color: 'var(--vscode-inputValidation-errorForeground)' }}>
                  Property is required
                </div>
              )}
            </div>

            <div>
              <input
                type="text"
                placeholder={isObjectProperty ? 'Enter individual name' : 'Enter literal value'}
                value={targetLabel}
                list={isObjectProperty && targetSuggestions.length ? targetListId : undefined}
                onChange={e => onChange({ propertyLabel, targetLabel: e.target.value, isObjectProperty })}
                onBlur={() => setTouched(true)}
                className="w-full px-3 py-2 rounded border text-sm"
              />
              {isObjectProperty && targetSuggestions.length > 0 && (
                <datalist id={targetListId}>
                  {targetSuggestions.map(s => (
                    <option key={s.value} value={s.label} />
                  ))}
                </datalist>
              )}
              {!targetLabel.trim() && touched && (
                <div className="text-xs mt-1" style={{ color: 'var(--vscode-inputValidation-errorForeground)' }}>
                  {isObjectProperty ? 'Individual is required' : 'Value is required'}
                </div>
              )}
            </div>
          </div>

          <div className="text-xs mt-3 text-center" style={{ color: 'var(--vscode-descriptionForeground)' }}>
            (Tip: Use <strong>CTRL+Space</strong> to auto-complete names)
          </div>
        </div>

        <div
          className="px-4 py-3 border-t flex justify-center gap-2"
          style={{ borderColor: 'var(--vscode-panel-border)', backgroundColor: 'var(--vscode-editorWidget-background)' }}
        >
          <button
            onClick={onConfirm}
            disabled={!canConfirm}
            className="px-6 py-1.5 rounded border text-sm font-medium disabled:opacity-50"
            style={{
              borderColor: 'var(--vscode-button-border, transparent)',
              backgroundColor: 'var(--vscode-button-background)',
              color: 'var(--vscode-button-foreground)'
            }}
          >
            <span className="inline-flex items-center gap-2">
              <Check size={16} />
              OK
            </span>
          </button>
          <button
            onClick={onCancel}
            className="px-6 py-1.5 rounded border text-sm font-medium"
            style={{
              borderColor: 'var(--vscode-button-secondaryBorder, var(--vscode-input-border))',
              backgroundColor: 'var(--vscode-button-secondaryBackground, var(--vscode-input-background))',
              color: 'var(--vscode-button-secondaryForeground, var(--vscode-foreground))'
            }}
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
};

export default PropertyAssertionDialog;

