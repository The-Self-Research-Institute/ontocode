import React, { useState } from 'react';
import { X, Plus, Trash2, ChevronDown, ChevronRight } from 'lucide-react';
import type { Property } from '../../types';

interface PropertyChainDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (chain: string[]) => void;
  properties: Property[];
  title?: string;
}

const PropertyChainDialog: React.FC<PropertyChainDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  properties,
  title = "Create Property Chain"
}) => {
  const [chain, setChain] = useState<string[]>([]);
  const [searchTerm, setSearchTerm] = useState('');
  const [isHierarchyVisible, setIsHierarchyVisible] = useState(true);

  if (!isOpen) return null;

  const filteredProperties = properties.filter(p => 
    p.label.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const handleAddToChain = (propId: string) => {
    setChain(prev => [...prev, propId]);
  };

  const handleRemoveFromChain = (index: number) => {
    setChain(prev => prev.filter((_, i) => i !== index));
  };

  const handleConfirm = () => {
    if (chain.length >= 2) {
      onConfirm(chain);
      setChain([]);
      onClose();
    }
  };

  const handleClose = () => {
    setChain([]);
    setSearchTerm('');
    onClose();
  };

  const getPropertyLabel = (propId: string) => {
    const prop = properties.find(p => p.id === propId);
    return prop?.label || propId.split('#').pop() || propId;
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-4xl mx-4 flex flex-col max-h-[80vh]">
        {/* Header */}
        <div className="px-4 py-3 border-b flex justify-between items-center bg-gray-50">
          <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
          <button 
            onClick={handleClose}
            className="text-gray-400 hover:text-gray-600 transition-colors"
          >
            <X size={20} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-hidden flex">
          {/* Left Panel - Property Hierarchy */}
          <div className="w-1/2 border-r flex flex-col">
            <div className="p-3 border-b bg-gray-50">
              <div className="flex items-center justify-between mb-2">
                <h4 className="text-sm font-semibold text-gray-700">Property Hierarchy</h4>
                <button
                  onClick={() => setIsHierarchyVisible(!isHierarchyVisible)}
                  className="text-gray-500 hover:text-gray-700"
                >
                  {isHierarchyVisible ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                </button>
              </div>
              <input
                type="text"
                placeholder="Search properties..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full px-3 py-1.5 text-sm border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            
            {isHierarchyVisible && (
              <div className="flex-1 overflow-y-auto p-2">
                {filteredProperties.length > 0 ? (
                  <div className="space-y-1">
                    {filteredProperties.map(prop => (
                      <button
                        key={prop.id}
                        onClick={() => handleAddToChain(prop.id)}
                        className="w-full text-left px-3 py-2 text-sm rounded hover:bg-blue-50 transition-colors flex items-center justify-between group"
                      >
                        <span className="font-mono text-gray-700">{prop.label}</span>
                        <Plus size={14} className="text-gray-400 group-hover:text-blue-600" />
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="text-sm text-gray-400 italic text-center py-8">
                    No properties found
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Right Panel - Chain Builder */}
          <div className="w-1/2 flex flex-col">
            <div className="p-3 border-b bg-gray-50">
              <h4 className="text-sm font-semibold text-gray-700">Property Chain</h4>
              <p className="text-xs text-gray-500 mt-1">
                Build a chain by selecting properties from the left. Order matters!
              </p>
            </div>

            <div className="flex-1 overflow-y-auto p-4">
              {chain.length > 0 ? (
                <div className="space-y-2">
                  {chain.map((propId, index) => (
                    <div key={index} className="flex items-center gap-2">
                      <div className="flex-1 bg-blue-50 border border-blue-200 rounded px-3 py-2 flex items-center justify-between">
                        <span className="text-sm font-mono text-blue-900">
                          {getPropertyLabel(propId)}
                        </span>
                        <button
                          onClick={() => handleRemoveFromChain(index)}
                          className="text-blue-400 hover:text-red-600 transition-colors"
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                      {index < chain.length - 1 && (
                        <span className="text-gray-400 font-semibold">∘</span>
                      )}
                    </div>
                  ))}
                </div>
              ) : (
                <div className="flex items-center justify-center h-full text-sm text-gray-400 italic">
                  No properties added to chain yet
                </div>
              )}
            </div>

            {/* Chain Preview */}
            {chain.length > 0 && (
              <div className="p-3 border-t bg-gray-50">
                <p className="text-xs text-gray-500 mb-1">Chain Preview:</p>
                <div className="font-mono text-sm text-gray-700 bg-white border rounded px-3 py-2">
                  {chain.map(getPropertyLabel).join(' ∘ ')}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Footer */}
        <div className="px-4 py-3 border-t bg-gray-50 flex justify-between items-center">
          <div className="text-xs text-gray-500">
            {chain.length > 0 ? (
              <span>{chain.length} {chain.length === 1 ? 'property' : 'properties'} in chain</span>
            ) : (
              <span>Select at least 2 properties to create a chain</span>
            )}
          </div>
          <div className="flex gap-2">
            <button
              onClick={handleClose}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleConfirm}
              disabled={chain.length < 2}
              className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              OK
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default PropertyChainDialog;
