import React, { useState, useEffect, useRef } from 'react';
import { X, Check, AlertTriangle } from 'lucide-react';

interface IRIEditorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  currentIRI: string;
  currentLabel: string;
  entityType: 'Class' | 'ObjectProperty' | 'DataProperty' | 'Individual' | 'Datatype' | 'AnnotationProperty';
  onSave: (newIRI: string, newLabel: string) => void;
}

const getIRIParts = (iriValue: string) => {
  const hashIndex = iriValue.lastIndexOf('#');
  const slashIndex = iriValue.lastIndexOf('/');
  const separatorIndex = Math.max(hashIndex, slashIndex);

  if (separatorIndex > 0) {
    return {
      namespace: iriValue.substring(0, separatorIndex + 1),
      localName: iriValue.substring(separatorIndex + 1),
    };
  }
  const colonIndex = iriValue.indexOf(':');
  if (colonIndex > 0) {
    return {
      namespace: iriValue.substring(0, colonIndex + 1),
      localName: iriValue.substring(colonIndex + 1),
    };
  }
  return { namespace: '', localName: iriValue };
};

// Valid local-name characters for an IRI fragment — letters, digits, _, -, .
const isValidLocalName = (name: string) => /^[A-Za-z0-9_.-]+$/.test(name);

const IRIEditorDialog: React.FC<IRIEditorDialogProps> = ({
  isOpen,
  onClose,
  currentIRI,
  currentLabel,
  entityType,
  onSave,
}) => {
  const [label, setLabel] = useState(currentLabel);
  const [localName, setLocalName] = useState('');
  const namespace = getIRIParts(currentIRI).namespace;

  // useEffect(() => {
  //   if (isOpen) {
  //     setLabel(currentLabel);
  //     setLocalName(getIRIParts(currentIRI).localName);
  //   }
  // }, [isOpen, currentLabel, currentIRI]);
const wasOpenRef = useRef(false);
useEffect(() => {
  if (isOpen && !wasOpenRef.current) {
    setLabel(currentLabel);
    setLocalName(getIRIParts(currentIRI).localName);
  }
  wasOpenRef.current = isOpen;
}, [isOpen, currentLabel, currentIRI]);
  if (!isOpen) return null;

  const newIRI = `${namespace}${localName}`;
  const iriChanged = newIRI !== currentIRI;
  const labelChanged = label !== currentLabel;
  const hasChanged = iriChanged || labelChanged;
  const localNameValid = localName.trim().length > 0 && isValidLocalName(localName.trim());

  const handleSave = () => {
    if (!label.trim() || !localNameValid) return;
    onSave(newIRI, label.trim());
    onClose();
  };

  const handleClose = () => {
    setLabel(currentLabel);
    setLocalName(getIRIParts(currentIRI).localName);
    onClose();
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) handleClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="p-4 border-b border-gray-200 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h3 className="text-lg font-semibold text-black">Rename Entity</h3>
            <span className="text-xs px-2 py-1 bg-purple-100 text-purple-700 rounded font-semibold">
              {entityType}
            </span>
          </div>
          <button onClick={handleClose} className="p-1 rounded hover:bg-gray-100 text-gray-500" title="Close">
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
          </div>

          {/* IRI — now editable via local name */}
          <div>
            <label className="text-sm font-semibold text-gray-800 block mb-2">
              Entity IRI
            </label>
            <div className="flex items-center gap-0 border border-gray-300 rounded-md overflow-hidden focus-within:ring-1 focus-within:ring-purple-500 focus-within:border-purple-500">
              {/* <span className="px-3 py-2 bg-gray-50 text-sm font-mono text-gray-500 whitespace-nowrap border-r border-gray-200">
                {namespace}
              </span> */}
              <input
                type="text"
                value={localName}
                onChange={(e) => setLocalName(e.target.value)}
                className="flex-1 px-3 py-2 text-sm font-mono outline-none min-w-0"
                placeholder="localName"
              />
            </div>
            {!localNameValid && localName.trim().length > 0 && (
              <p className="text-xs text-red-600 mt-1">
                Local name can only contain letters, numbers, underscores, hyphens, and periods.
              </p>
            )}
            <p className="text-xs text-gray-500 mt-1 font-mono break-all">
              Preview: {newIRI}
            </p>
          </div>

          {/* Rename warning — only shown when the IRI is actually changing */}
          {/* {iriChanged && localNameValid && (
            <div className="flex gap-2 p-3 bg-amber-50 border border-amber-200 rounded-md">
              <AlertTriangle size={16} className="text-amber-600 flex-shrink-0 mt-0.5" />
              <p className="text-xs text-amber-800">
                Renaming this {entityType.toLowerCase()} will update every axiom in the ontology that
                references it — including subclass/domain/range restrictions, general class axioms,
                property assertions, and annotations. This cannot be undone automatically.
              </p>
            </div>
          )} */}
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-200 flex justify-between items-center">
          <div className="text-xs text-gray-600">
            {hasChanged && <span className="text-amber-600">Unsaved changes</span>}
          </div>
          <div className="flex gap-3">
            <button onClick={handleClose} className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300">
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={!label.trim() || !localNameValid || !hasChanged}
              className={`px-4 py-2 text-sm rounded-md flex items-center gap-2 ${
                !label.trim() || !localNameValid || !hasChanged
                  ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
                  : 'bg-purple-600 text-white hover:bg-purple-700'
              }`}
            >
              <Check size={16} />
              {iriChanged ? 'Rename Entity' : 'Save Changes'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default IRIEditorDialog;