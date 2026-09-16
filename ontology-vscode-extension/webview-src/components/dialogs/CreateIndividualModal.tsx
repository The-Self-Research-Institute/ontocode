import React, { useState } from 'react';
import { computeEntityIriPreview } from '../../utils/entityIri';
import { IriPreviewField } from './IriPreviewField';
import { CreateButton } from './CreateButton';

interface CreateIndividualModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreate: (name: string) => void;
  /** Ontology base IRI, used to compute the live IRI preview (matches backend's {ontologyIri}#{name} convention). */
  ontologyIri?: string;
  /** Every existing individual IRI, used to block duplicate creation before it reaches the backend. */
  existingIris?: string[];
}

const CreateIndividualModal: React.FC<CreateIndividualModalProps> = ({
  isOpen,
  onClose,
  onCreate,
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

  const handleClose = () => {
    setName('');
    onClose();
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) handleClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl p-6 max-w-md w-full mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-lg font-semibold text-black mb-4">Create a new Named Individual</h3>
        <div className="space-y-4 text-sm">
          <div>
            <label className="font-medium text-black block mb-2">Name</label>
            <input
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleCreate()}
              placeholder="Short name or full IRI"
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 text-black"
              autoFocus
            />
          </div>
          <IriPreviewField
            computedIri={computedIri}
            isDuplicate={isDuplicate}
            placeholder="(auto-generated from ontology IRI + name, or paste a full IRI above)"
          />
          <p className="text-[11px] text-gray-500">
            Tip: You can add types, property assertions, and same/different-individual relationships after creation in the entity editor.
          </p>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button onClick={handleClose} className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300">
            Cancel
          </button>
          <CreateButton onClick={handleCreate} disabled={!canCreate} />
        </div>
      </div>
    </div>
  );
};

export default CreateIndividualModal;
