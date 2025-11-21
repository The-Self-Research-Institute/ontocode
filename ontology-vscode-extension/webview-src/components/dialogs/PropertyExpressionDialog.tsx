import React, { useState } from 'react';
import { X, Search, ChevronDown, ChevronRight } from 'lucide-react';
import type { TreeNode, Property } from '../../types';

interface PropertyExpressionDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (expression: string) => void;
  propertyHierarchy: TreeNode[];
  title?: string;
  propertyType: 'object' | 'data';
}

type TabType = 'hierarchy' | 'propertyExpression';

const PropertyExpressionDialog: React.FC<PropertyExpressionDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  propertyHierarchy,
  title = "Property Expression Editor",
  propertyType
}) => {
  const [activeTab, setActiveTab] = useState<TabType>('hierarchy');
  const [selectedProperty, setSelectedProperty] = useState<TreeNode | null>(null);
  const [expandedNodes, setExpandedNodes] = useState<string[]>([]);
  const [searchQuery, setSearchQuery] = useState('');

  // Property Expression state
  const [manchesterExpression, setManchesterExpression] = useState('');

  if (!isOpen) return null;

  const handleToggleNode = (nodeId: string) => {
    const isExpanded = expandedNodes.includes(nodeId);

    if (isExpanded) {
      setExpandedNodes(prev => prev.filter(id => id !== nodeId));
    } else {
      setExpandedNodes(prev => [...prev, nodeId]);
    }
  };

  const renderPropertyTree = (
    nodes: TreeNode[],
    level: number,
    onSelect: (node: TreeNode) => void,
    selected: TreeNode | null,
    searchQuery: string
  ): React.ReactNode => {
    return nodes
      .filter(node => !searchQuery || node.label.toLowerCase().includes(searchQuery.toLowerCase()))
      .map(node => (
        <div key={node.id}>
          <div
            className={`flex items-center gap-2 px-2 py-2 hover:bg-gray-50 cursor-pointer transition-colors ${
              selected?.id === node.id ? 'bg-blue-50 border-l-3 border-l-blue-600' : ''
            }`}
            style={{ paddingLeft: `${level * 16 + 8}px` }}
          >
            {node.hasChildren ? (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  handleToggleNode(node.id);
                }}
                className="p-0.5 hover:bg-gray-200 rounded"
              >
                {expandedNodes.includes(node.id) ? (
                  <ChevronDown size={14} className="text-gray-600" />
                ) : (
                  <ChevronRight size={14} className="text-gray-400" />
                )}
              </button>
            ) : (
              <span className="w-5" />
            )}
            <div
              onClick={() => onSelect(node)}
              className="flex items-center gap-2 flex-1"
            >
              <span className={`w-2.5 h-2.5 rounded-sm ${
                selected?.id === node.id
                  ? propertyType === 'object' ? 'bg-blue-700' : 'bg-green-700'
                  : propertyType === 'object' ? 'bg-blue-400' : 'bg-green-400'
              }`} />
              <span className={`text-sm font-mono ${selected?.id === node.id ? 'font-semibold text-gray-900' : 'text-gray-700'}`}>
                {node.label}
              </span>
            </div>
          </div>
          {expandedNodes.includes(node.id) && node.children && node.children.length > 0 && (
            renderPropertyTree(node.children, level + 1, onSelect, selected, searchQuery)
          )}
        </div>
      ));
  };

  const handleConfirm = () => {
    let expression = '';

    switch (activeTab) {
      case 'hierarchy':
        if (selectedProperty) expression = selectedProperty.id;
        break;
      case 'propertyExpression':
        expression = manchesterExpression.trim();
        break;
    }

    if (expression) {
      onConfirm(expression);
      handleClose();
    }
  };

  const handleClose = () => {
    setSelectedProperty(null);
    setManchesterExpression('');
    setSearchQuery('');
    onClose();
  };

  const propertyColor = propertyType === 'object' ? 'blue' : 'green';

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-5xl mx-4 flex flex-col h-[85vh]">
        {/* Header */}
        <div className={`px-6 py-4 border-b bg-${propertyColor}-700 flex justify-between items-center`}>
          <h3 className="text-lg font-semibold text-white">{title}</h3>
          <button
            onClick={handleClose}
            className="text-white hover:bg-opacity-20 hover:bg-white rounded-lg p-1.5 transition-colors"
          >
            <X size={20} />
          </button>
        </div>

        {/* Tabs */}
        <div className="flex border-b bg-gray-50">
          <button
            onClick={() => setActiveTab('hierarchy')}
            className={`px-5 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'hierarchy'
                ? `border-${propertyColor}-500 text-${propertyColor}-700 bg-white`
                : 'border-transparent text-gray-600 hover:text-gray-800 hover:bg-gray-100'
            }`}
          >
            Property Hierarchy
          </button>
          <button
            onClick={() => setActiveTab('propertyExpression')}
            className={`px-5 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === 'propertyExpression'
                ? `border-${propertyColor}-500 text-${propertyColor}-700 bg-white`
                : 'border-transparent text-gray-600 hover:text-gray-800 hover:bg-gray-100'
            }`}
          >
            Property Expression Editor
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-hidden">
          {/* Property Hierarchy Tab */}
          {activeTab === 'hierarchy' && (
            <div className="h-full flex flex-col">
              <div className="p-3 border-b bg-gray-50">
                <div className="flex items-center gap-2 mb-2">
                  <h4 className="text-sm font-semibold text-gray-700">
                    {propertyType === 'object' ? 'Object Properties' : 'Data Properties'}
                  </h4>
                  <select className="ml-auto px-2 py-1 text-xs border rounded bg-white">
                    <option>Asserted</option>
                  </select>
                </div>
                <div className="relative">
                  <Search size={16} className="absolute left-3 top-2.5 text-gray-400" />
                  <input
                    type="text"
                    placeholder="Search properties..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className={`w-full pl-9 pr-3 py-2 text-sm border border-${propertyColor}-200 rounded-md focus:outline-none focus:ring-2 focus:ring-${propertyColor}-500 bg-white`}
                  />
                </div>
              </div>
              <div className="flex-1 overflow-y-auto bg-white">
                {propertyHierarchy.length > 0 ? (
                  renderPropertyTree(propertyHierarchy, 0, setSelectedProperty, selectedProperty, searchQuery)
                ) : (
                  <div className="flex items-center justify-center h-full text-sm text-gray-400 italic">
                    No properties available
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Property Expression Editor Tab */}
          {activeTab === 'propertyExpression' && (
            <div className="h-full p-6 flex flex-col bg-white">
              <div className="flex-1 flex flex-col">
                <label className="text-sm font-bold text-gray-700 mb-2">Manchester OWL Syntax</label>
                <textarea
                  value={manchesterExpression}
                  onChange={(e) => setManchesterExpression(e.target.value)}
                  placeholder="Enter Manchester OWL Syntax expression for property"
                  className={`flex-1 p-4 border-2 border-${propertyColor}-200 rounded-lg font-mono text-sm focus:outline-none focus:ring-2 focus:ring-${propertyColor}-500 focus:border-transparent resize-none bg-white shadow-sm`}
                />
              </div>
              <div className={`mt-4 p-4 bg-white rounded-lg border border-${propertyColor}-200 shadow-sm`}>
                <p className="text-xs font-bold text-gray-700 mb-2">KEYWORDS</p>
                <div className="flex flex-wrap gap-2 mb-3">
                  {['inverse'].map(kw => (
                    <button
                      key={kw}
                      onClick={() => setManchesterExpression(prev => prev + (prev ? ' ' : '') + kw + ' ')}
                      className="px-3 py-1 bg-gray-100 hover:bg-gray-200 text-gray-800 text-xs font-mono rounded border transition-colors"
                    >
                      {kw}
                    </button>
                  ))}
                </div>
                <p className="text-xs font-bold text-gray-700 mb-1">EXAMPLES</p>
                <ul className="text-xs text-gray-600 space-y-1">
                  <li className="font-mono">• inverse hasParent</li>
                  <li className="font-mono">• hasLocation</li>
                </ul>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t bg-gradient-to-r from-gray-50 to-gray-100 flex justify-end gap-3">
          <button
            onClick={handleClose}
            className="px-6 py-2.5 text-sm font-semibold text-gray-700 bg-white border-2 border-gray-300 rounded-lg hover:bg-gray-50 hover:border-gray-400 transition-all shadow-sm"
          >
            Cancel
          </button>
          <button
            onClick={handleConfirm}
            disabled={
              (activeTab === 'hierarchy' && !selectedProperty) ||
              (activeTab === 'propertyExpression' && !manchesterExpression.trim())
            }
            className={`px-6 py-2.5 text-sm font-semibold text-white bg-gradient-to-r from-${propertyColor}-600 to-${propertyColor}-700 rounded-lg hover:from-${propertyColor}-700 hover:to-${propertyColor}-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all shadow-md hover:shadow-lg`}
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};

export default PropertyExpressionDialog;
