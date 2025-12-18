import React, { useState, useEffect } from 'react';
import { X, Check } from 'lucide-react';
import EntityHierarchy from '../EntityHierarchy';
import type { TreeNode } from '../../types';

interface PropertyAssertionDialogProps {
  isOpen: boolean;
  title: string;
  isObjectProperty: boolean;
  
  // Hierarchy Data
  objectPropertiesTree?: TreeNode[];
  dataPropertiesTree?: TreeNode[];
  
  // Legacy/Simple mode props
  propertySuggestions?: { label: string; value: string }[];
  targetSuggestions?: { label: string; value: string }[];
  
  // Initial values
  initialPropertyLabel?: string;
  initialTargetLabel?: string;
  
  // Callbacks
  onConfirm: (data: { 
    propertyLabel: string; 
    targetLabel: string; 
    isObjectProperty: boolean;
    language?: string;
    datatype?: string;
  }) => void;
  onCancel: () => void;
  
  // Legacy props
  propertyLabel?: string;
  targetLabel?: string;
  showTypeSelector?: boolean;
  onChange?: (next: { propertyLabel: string; targetLabel: string; isObjectProperty: boolean }) => void;
}

const DATATYPES = [
  'xsd:string',
  'xsd:integer',
  'xsd:decimal',
  'xsd:double',
  'xsd:float',
  'xsd:boolean',
  'xsd:dateTime',
  'xsd:date',
  'xsd:time',
  'rdf:PlainLiteral',
  'rdfs:Literal',
  'xsd:anyURI'
];

const PropertyAssertionDialog: React.FC<PropertyAssertionDialogProps> = ({
  isOpen,
  title,
  isObjectProperty,
  objectPropertiesTree = [],
  dataPropertiesTree = [],
  propertySuggestions = [],
  targetSuggestions = [],
  initialPropertyLabel = '',
  initialTargetLabel = '',
  onConfirm,
  onCancel,
  // Legacy props handling
  propertyLabel: legacyPropertyLabel,
  targetLabel: legacyTargetLabel,
  onChange
}) => {
  // Initialize state with either new props or legacy props
  const [propertyLabel, setPropertyLabel] = useState(initialPropertyLabel || legacyPropertyLabel || '');
  const [targetLabel, setTargetLabel] = useState(initialTargetLabel || legacyTargetLabel || '');
  const [language, setLanguage] = useState('');
  const [datatype, setDatatype] = useState('xsd:string');
  
  // For hierarchy selection
  const [selectedNode, setSelectedNode] = useState<TreeNode | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');

  useEffect(() => {
    if (isOpen) {
      setPropertyLabel(initialPropertyLabel || legacyPropertyLabel || '');
      setTargetLabel(initialTargetLabel || legacyTargetLabel || '');
      setLanguage('');
      setDatatype('xsd:string');
      setSelectedNode(null);
      setSearchQuery('');
    }
  }, [isOpen, initialPropertyLabel, initialTargetLabel, legacyPropertyLabel, legacyTargetLabel]);

  // Update property label when node selected
  useEffect(() => {
    if (selectedNode) {
      setPropertyLabel(selectedNode.label || selectedNode.id);
      // If legacy onChange is present, call it
      if (onChange) {
        onChange({ propertyLabel: selectedNode.label || selectedNode.id, targetLabel, isObjectProperty });
      }
    }
  }, [selectedNode]);

  // Handle manual input changes for legacy support
  const handlePropertyChange = (val: string) => {
    setPropertyLabel(val);
    if (onChange) onChange({ propertyLabel: val, targetLabel, isObjectProperty });
  };

  const handleTargetChange = (val: string) => {
    setTargetLabel(val);
    if (onChange) onChange({ propertyLabel, targetLabel: val, isObjectProperty });
  };

  const handleConfirm = () => {
    onConfirm({
      propertyLabel,
      targetLabel,
      isObjectProperty,
      language: !isObjectProperty ? language : undefined,
      datatype: !isObjectProperty ? datatype : undefined
    });
  };

  const activeTree = isObjectProperty ? objectPropertiesTree : dataPropertiesTree;
  const showHierarchy = activeTree && activeTree.length > 0;

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={onCancel}>
      <div
        className="w-full mx-4 rounded-lg shadow-xl flex flex-col"
        style={{
          backgroundColor: 'var(--vscode-editor-background)',
          color: 'var(--vscode-foreground)',
          maxWidth: '900px',
          height: '600px',
          resize: 'both',
          overflow: 'hidden'
        }}
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b" style={{ borderColor: 'var(--vscode-panel-border)' }}>
          <div className="font-semibold">{title}</div>
          <button onClick={onCancel} className="p-1 rounded hover:bg-gray-700/20">
            <X size={18} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 flex min-h-0">
          {/* Left: Property Hierarchy */}
          {showHierarchy && (
            <div className="w-1/3 border-r flex flex-col" style={{ borderColor: 'var(--vscode-panel-border)' }}>
              <div className="p-2 border-b bg-gray-50/5 dark:bg-gray-900/20" style={{ borderColor: 'var(--vscode-panel-border)' }}>
                <span className="text-xs font-bold uppercase opacity-70">
                  {isObjectProperty ? 'Object Properties' : 'Data Properties'}
                </span>
              </div>
              <div className="flex-1 overflow-auto">
                <EntityHierarchy
                  entitiesTab={isObjectProperty ? 'ObjectProperties' : 'DataProperties'}
                  filteredData={activeTree}
                  selectedItem={selectedNode}
                  expandedNodes={expandedNodes}
                  searchQuery={searchQuery}
                  onSearchQueryChange={setSearchQuery}
                  onSelectItem={(item) => setSelectedNode(item as TreeNode)}
                  onToggleNode={(id) => setExpandedNodes(prev => prev.includes(id) ? prev.filter(n => n !== id) : [...prev, id])}
                  onAddItem={() => {}}
                  onDeleteItem={() => {}}
                  hideToolbarActions={true}
                />
              </div>
            </div>
          )}

          {/* Right: Form */}
          <div className={`flex-1 p-4 flex flex-col gap-4 overflow-auto ${!showHierarchy ? 'w-full' : ''}`}>
            
            {/* Property Name (Read-only if selected from tree, or editable) */}
            <div>
              <label className="block text-xs font-medium mb-1 opacity-70">
                {isObjectProperty ? 'Object Property' : 'Data Property'}
              </label>
              <input
                type="text"
                value={propertyLabel}
                onChange={e => handlePropertyChange(e.target.value)}
                list={propertySuggestions.length ? "prop-suggestions" : undefined}
                className="w-full px-3 py-2 rounded border text-sm bg-transparent"
                style={{ borderColor: 'var(--vscode-input-border)', backgroundColor: 'var(--vscode-input-background)' }}
                placeholder="Select a property or type name..."
              />
              {propertySuggestions.length > 0 && (
                <datalist id="prop-suggestions">
                  {propertySuggestions.map(s => <option key={s.value} value={s.label} />)}
                </datalist>
              )}
            </div>

            {/* Target / Value */}
            <div className="flex-1 flex flex-col">
              <label className="block text-xs font-medium mb-1 opacity-70">
                {isObjectProperty ? 'Individual' : 'Value'}
              </label>
              {isObjectProperty ? (
                <div className="relative">
                   <input
                    type="text"
                    value={targetLabel}
                    onChange={e => handleTargetChange(e.target.value)}
                    list="target-suggestions"
                    className="w-full px-3 py-2 rounded border text-sm bg-transparent"
                    style={{ borderColor: 'var(--vscode-input-border)', backgroundColor: 'var(--vscode-input-background)' }}
                    placeholder="Enter individual name"
                  />
                  <datalist id="target-suggestions">
                    {targetSuggestions.map(s => <option key={s.value} value={s.label} />)}
                  </datalist>
                </div>
              ) : (
                <textarea
                  value={targetLabel}
                  onChange={e => handleTargetChange(e.target.value)}
                  className="w-full flex-1 px-3 py-2 rounded border text-sm bg-transparent resize-none font-mono"
                  style={{ borderColor: 'var(--vscode-input-border)', backgroundColor: 'var(--vscode-input-background)' }}
                  placeholder="Enter literal value..."
                />
              )}
            </div>

            {/* Data Property Extras */}
            {!isObjectProperty && (
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-medium mb-1 opacity-70">Language Tag</label>
                  <input
                    type="text"
                    value={language}
                    onChange={e => setLanguage(e.target.value)}
                    className="w-full px-3 py-2 rounded border text-sm bg-transparent"
                    style={{ borderColor: 'var(--vscode-input-border)', backgroundColor: 'var(--vscode-input-background)' }}
                    placeholder="e.g. en, fr"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium mb-1 opacity-70">Datatype</label>
                  <select
                    value={datatype}
                    onChange={e => setDatatype(e.target.value)}
                    className="w-full px-3 py-2 rounded border text-sm bg-transparent"
                    style={{ borderColor: 'var(--vscode-input-border)', backgroundColor: 'var(--vscode-input-background)' }}
                  >
                    {DATATYPES.map(dt => (
                      <option key={dt} value={dt}>{dt}</option>
                    ))}
                  </select>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t flex justify-end gap-2" style={{ borderColor: 'var(--vscode-panel-border)', backgroundColor: 'var(--vscode-editorWidget-background)' }}>
          <button
            onClick={onCancel}
            className="px-4 py-2 rounded text-sm hover:bg-gray-700/20"
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={!propertyLabel || !targetLabel}
            className="px-4 py-2 rounded text-sm font-medium flex items-center gap-2 disabled:opacity-50"
            style={{ backgroundColor: 'var(--vscode-button-background)', color: 'var(--vscode-button-foreground)' }}
          >
            <Check size={16} />
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default PropertyAssertionDialog;
