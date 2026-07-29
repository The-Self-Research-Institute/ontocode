import React, { useState } from 'react';
import { Settings, X } from 'lucide-react';

interface EntityPreferences {
  autoGenerateIRI: boolean;
  iriGenerationPattern: 'namespace_entity' | 'namespace_hash_entity' | 'namespace_slash_entity' | 'custom';
  customPattern: string;
  enableAnnotations: boolean;
  defaultAnnotations: {
    label: boolean;
    comment: boolean;
    seeAlso: boolean;
    isDefinedBy: boolean;
  };
  confirmBeforeDelete: boolean;
  expandNewEntities: boolean;
  selectAfterCreation: boolean;
  showParentInDialog: boolean;
  enableDragDrop: boolean;
}

interface EntityPreferencesDialogProps {
  isOpen: boolean;
  onClose: () => void;
  preferences: EntityPreferences;
  onSave: (preferences: EntityPreferences) => void;
}

const DEFAULT_PREFERENCES: EntityPreferences = {
  autoGenerateIRI: true,
  iriGenerationPattern: 'namespace_hash_entity',
  customPattern: '{namespace}#{entity}',
  enableAnnotations: true,
  defaultAnnotations: {
    label: true,
    comment: false,
    seeAlso: false,
    isDefinedBy: false
  },
  confirmBeforeDelete: true,
  expandNewEntities: true,
  selectAfterCreation: true,
  showParentInDialog: true,
  enableDragDrop: true
};

const EntityPreferencesDialog: React.FC<EntityPreferencesDialogProps> = ({
  isOpen,
  onClose,
  preferences: initialPreferences,
  onSave
}) => {
  const [preferences, setPreferences] = useState<EntityPreferences>(initialPreferences || DEFAULT_PREFERENCES);

  if (!isOpen) return null;

  const handleSave = () => {
    onSave(preferences);
    onClose();
  };

  const handleReset = () => {
    setPreferences(DEFAULT_PREFERENCES);
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 flex flex-col max-h-[85vh]" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="p-4 border-b border-gray-200 flex items-center justify-between flex-shrink-0">
          <div className="flex items-center gap-2">
            <Settings size={20} className="text-purple-600" />
            <h3 className="text-lg font-semibold text-black">Entity Creation Preferences</h3>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded hover:bg-gray-100 text-gray-500"
            title="Close"
          >
            <X size={20} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-4 space-y-6">
          {/* IRI Generation Section */}
          <section className="space-y-3">
            <h4 className="text-sm font-semibold text-gray-800 border-b pb-2">IRI Generation</h4>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={preferences.autoGenerateIRI}
                onChange={(e) => setPreferences({ ...preferences, autoGenerateIRI: e.target.checked })}
                className="w-4 h-4 text-purple-600"
              />
              <span>Auto-generate IRIs for new entities</span>
            </label>

            {preferences.autoGenerateIRI && (
              <div className="ml-6 space-y-2">
                <p className="text-xs text-gray-600 mb-2">IRI Pattern:</p>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="radio"
                    checked={preferences.iriGenerationPattern === 'namespace_hash_entity'}
                    onChange={() => setPreferences({ ...preferences, iriGenerationPattern: 'namespace_hash_entity' })}
                    className="w-4 h-4 text-purple-600"
                  />
                  <code className="text-xs bg-gray-100 px-2 py-1 rounded">http://example.com/ontology#EntityName</code>
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="radio"
                    checked={preferences.iriGenerationPattern === 'namespace_slash_entity'}
                    onChange={() => setPreferences({ ...preferences, iriGenerationPattern: 'namespace_slash_entity' })}
                    className="w-4 h-4 text-purple-600"
                  />
                  <code className="text-xs bg-gray-100 px-2 py-1 rounded">http://example.com/ontology/EntityName</code>
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="radio"
                    checked={preferences.iriGenerationPattern === 'namespace_entity'}
                    onChange={() => setPreferences({ ...preferences, iriGenerationPattern: 'namespace_entity' })}
                    className="w-4 h-4 text-purple-600"
                  />
                  <code className="text-xs bg-gray-100 px-2 py-1 rounded">http://example.com/ontologyEntityName</code>
                </label>
                <label className="flex items-start gap-2 text-sm">
                  <input
                    type="radio"
                    checked={preferences.iriGenerationPattern === 'custom'}
                    onChange={() => setPreferences({ ...preferences, iriGenerationPattern: 'custom' })}
                    className="w-4 h-4 text-purple-600 mt-1"
                  />
                  <div className="flex-1">
                    <span>Custom pattern:</span>
                    <input
                      type="text"
                      value={preferences.customPattern}
                      onChange={(e) => setPreferences({ ...preferences, customPattern: e.target.value })}
                      disabled={preferences.iriGenerationPattern !== 'custom'}
                      placeholder="{namespace}#{entity}"
                      className="w-full mt-1 px-2 py-1 text-xs font-mono border border-gray-300 rounded disabled:bg-gray-100"
                    />
                    <p className="text-[10px] text-gray-500 mt-1">
                      Use &#123;namespace&#125; and &#123;entity&#125; as placeholders
                    </p>
                  </div>
                </label>
              </div>
            )}
          </section>

          {/* Default Annotations Section */}
          <section className="space-y-3">
            <h4 className="text-sm font-semibold text-gray-800 border-b pb-2">Default Annotations</h4>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={preferences.enableAnnotations}
                onChange={(e) => setPreferences({ ...preferences, enableAnnotations: e.target.checked })}
                className="w-4 h-4 text-purple-600"
              />
              <span>Enable default annotations for new entities</span>
            </label>

            {preferences.enableAnnotations && (
              <div className="ml-6 space-y-2">
                <p className="text-xs text-gray-600 mb-2">Include these annotations:</p>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={preferences.defaultAnnotations.label}
                    onChange={(e) => setPreferences({
                      ...preferences,
                      defaultAnnotations: { ...preferences.defaultAnnotations, label: e.target.checked }
                    })}
                    className="w-4 h-4 text-purple-600"
                  />
                  <span>rdfs:label</span>
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={preferences.defaultAnnotations.comment}
                    onChange={(e) => setPreferences({
                      ...preferences,
                      defaultAnnotations: { ...preferences.defaultAnnotations, comment: e.target.checked }
                    })}
                    className="w-4 h-4 text-purple-600"
                  />
                  <span>rdfs:comment</span>
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={preferences.defaultAnnotations.seeAlso}
                    onChange={(e) => setPreferences({
                      ...preferences,
                      defaultAnnotations: { ...preferences.defaultAnnotations, seeAlso: e.target.checked }
                    })}
                    className="w-4 h-4 text-purple-600"
                  />
                  <span>rdfs:seeAlso</span>
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={preferences.defaultAnnotations.isDefinedBy}
                    onChange={(e) => setPreferences({
                      ...preferences,
                      defaultAnnotations: { ...preferences.defaultAnnotations, isDefinedBy: e.target.checked }
                    })}
                    className="w-4 h-4 text-purple-600"
                  />
                  <span>rdfs:isDefinedBy</span>
                </label>
              </div>
            )}
          </section>

          {/* Behavior Section */}
          <section className="space-y-3">
            <h4 className="text-sm font-semibold text-gray-800 border-b pb-2">Entity Behavior</h4>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={preferences.expandNewEntities}
                onChange={(e) => setPreferences({ ...preferences, expandNewEntities: e.target.checked })}
                className="w-4 h-4 text-purple-600"
              />
              <span>Automatically expand parent node after creating subclass</span>
            </label>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={preferences.selectAfterCreation}
                onChange={(e) => setPreferences({ ...preferences, selectAfterCreation: e.target.checked })}
                className="w-4 h-4 text-purple-600"
              />
              <span>Select newly created entity automatically</span>
            </label>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={preferences.showParentInDialog}
                onChange={(e) => setPreferences({ ...preferences, showParentInDialog: e.target.checked })}
                className="w-4 h-4 text-purple-600"
              />
              <span>Show parent class information in creation dialog</span>
            </label>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={preferences.confirmBeforeDelete}
                onChange={(e) => setPreferences({ ...preferences, confirmBeforeDelete: e.target.checked })}
                className="w-4 h-4 text-purple-600"
              />
              <span>Confirm before deleting entities</span>
            </label>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={preferences.enableDragDrop}
                onChange={(e) => setPreferences({ ...preferences, enableDragDrop: e.target.checked })}
                className="w-4 h-4 text-purple-600"
              />
              <span>Enable drag-and-drop to reorganize class hierarchy</span>
            </label>
          </section>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-200 flex justify-between items-center flex-shrink-0">
          <button
            onClick={handleReset}
            className="px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 rounded-md"
          >
            Reset to Defaults
          </button>
          <div className="flex gap-3">
            <button
              onClick={onClose}
              className="px-4 py-2 text-sm bg-gray-200 text-black rounded-md hover:bg-gray-300"
            >
              Cancel
            </button>
            <button
              onClick={handleSave}
              className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700"
            >
              Save Preferences
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default EntityPreferencesDialog;
export { DEFAULT_PREFERENCES };
export type { EntityPreferences };
