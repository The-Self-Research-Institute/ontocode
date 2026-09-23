import React, { useState } from 'react';
import { computeEntityIriPreview } from '../../utils/entityIri';
import { IriPreviewField } from './IriPreviewField';
import { CreateButton } from './CreateButton';

interface AddObjectPropertyDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onCreate: (name: string) => void;
  type: 'subproperty' | 'sibling' | 'root';
  parentLabel: string;
  propertyType?: 'object' | 'data' | 'annotation';
  /** Ontology base IRI, used to compute the live IRI preview (matches backend's {ontologyIri}#{name} convention). */
  ontologyIri?: string;
  /** Every existing IRI for this property type, used to block duplicate creation before it reaches the backend. */
  existingIris?: string[];
}

const AddObjectPropertyDialog: React.FC<AddObjectPropertyDialogProps> = ({
  isOpen,
  onClose,
  onCreate,
  type,
  parentLabel,
  propertyType = 'object',
  ontologyIri,
  existingIris = [],
}) => {
  const [name, setName] = useState('');

  if (!isOpen) return null;

  const { trimmedName, computedIri, isDuplicate, canCreate } = computeEntityIriPreview(
    ontologyIri,
    name,
    existingIris,
  );

  const handleCreate = () => {
    if (canCreate) {
      onCreate(trimmedName);
      setName('');
      onClose();
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleCreate();
    }
  };

  const handleClose = () => {
    setName('');
    onClose();
  };

  const getTitle = () => {
    const propertyTypeLabel = propertyType === 'object' ? 'Object Property' :
                             propertyType === 'data' ? 'Data Property' : 'Annotation Property';
    if (type === 'root') return `Create New ${propertyTypeLabel}`;
    if (type === 'subproperty') return `Create New Subproperty`;
    return `Create New Sibling Property`;
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) handleClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-black mb-4">
          {getTitle()}
        </h3>
        <div className="space-y-4 text-sm">
          <div className="bg-blue-50 border border-blue-100 rounded-lg p-3">
            <p className="text-xs uppercase tracking-wide text-blue-700 font-semibold mb-1">Parent property</p>
            <p className="text-sm font-mono text-blue-900">{parentLabel}</p>
            <p className="text-[11px] text-blue-700 mt-1">
              New properties inherit placement from their parent in the hierarchy.
            </p>
          </div>
          <div>
            <label className="font-medium text-black block mb-2">Property Name</label>
            <input
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              onKeyPress={handleKeyPress}
              placeholder="Enter property name"
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
              autoFocus
            />
          </div>
          <IriPreviewField
            computedIri={computedIri}
            isDuplicate={isDuplicate}
            placeholder="(auto-generated from ontology IRI + property name)"
          />
          <p className="text-[11px] text-gray-500">
            Tip: Keep names short and descriptive. You can edit domain/range and characteristics after creation.
          </p>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button
            onClick={handleClose}
            className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
          >
            Cancel
          </button>
          <CreateButton onClick={handleCreate} disabled={!canCreate} />
        </div>
      </div>
    </div>
  );
};

export default AddObjectPropertyDialog;
