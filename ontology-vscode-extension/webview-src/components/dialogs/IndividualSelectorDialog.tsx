import React, { useState, useEffect, useCallback } from 'react';
import { X, User, Search, PlusCircle, Trash2 } from 'lucide-react';
import type { Individual } from '../../types';
import apiClient from '../../services/apiClient';

interface IndividualSelectorDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (individuals: Individual[]) => void;
  individuals: Individual[];
  projectId?: string;
  title?: string;
  excludeIndividualIds?: string[]; // Individuals to exclude from selection
  minSelection?: number; // Minimum number of individuals required
  initialSelectedIds?: string[]; // Pre-selected individual IRIs for edit mode
  onAddIndividual?: (name: string) => Promise<void>;
  onDeleteIndividual?: (id: string) => Promise<void>;
  classIri?: string; // If provided, filter individuals by this class type
  classLabel?: string; // Label of the class for display
}

const IndividualSelectorDialog: React.FC<IndividualSelectorDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  individuals,
  projectId,
  title = "Select Individuals",
  excludeIndividualIds = [],
  minSelection = 1,
  initialSelectedIds = [],
  onAddIndividual,
  onDeleteIndividual,
  classIri,
  classLabel
}) => {
  const [selectedIndividuals, setSelectedIndividuals] = useState<Individual[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [hasInitialized, setHasInitialized] = useState(false);
  const [filteredIndividuals, setFilteredIndividuals] = useState<Individual[]>([]);
  
  // Inline individual creation state
  const [showInlineCreate, setShowInlineCreate] = useState(false);
  const [inlineIndividualName, setInlineIndividualName] = useState('');
  const [isCreating, setIsCreating] = useState(false);

  // Filter individuals - show all available individuals, not just those with the classIri type
  // The purpose of this dialog is to ADD class assertions, so we should show individuals
  // that don't have the type yet (they will get the type when we confirm)
  useEffect(() => {
    let filtered = individuals;
    
    // Note: We DON'T filter by classIri here because we want to show ALL individuals
    // so users can add the class assertion to them. The classIri is used only for
    // context/display purposes, not for filtering.
    
    // Exclude specified individuals (those already having this class type)
    if (excludeIndividualIds.length > 0) {
      filtered = filtered.filter(ind => !excludeIndividualIds.includes(ind.id));
    }
    
    // Filter by search query
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(ind => 
        ind.label.toLowerCase().includes(query) || 
        ind.id.toLowerCase().includes(query)
      );
    }
    
    setFilteredIndividuals(filtered);
  }, [individuals, classIri, excludeIndividualIds, searchQuery]);

  // Reset state when dialog opens and load initial selections
  useEffect(() => {
    if (isOpen && !hasInitialized) {
      setHasInitialized(true);
      setSearchQuery('');
      
      // Load initial selections if provided
      if (initialSelectedIds.length > 0) {
        const initialIndividuals = individuals.filter(ind => initialSelectedIds.includes(ind.id));
        setSelectedIndividuals(initialIndividuals);
      } else {
        setSelectedIndividuals([]);
      }
    }
  }, [isOpen, hasInitialized, initialSelectedIds, individuals]);

  // Reset hasInitialized when dialog closes
  useEffect(() => {
    if (!isOpen) {
      setHasInitialized(false);
    }
  }, [isOpen]);

  const handleIndividualSelect = (individual: Individual) => {
    // Don't allow selecting excluded individuals
    if (excludeIndividualIds.includes(individual.id)) return;
    
    // Toggle selection for multi-select
    if (selectedIndividuals.find(i => i.id === individual.id)) {
      setSelectedIndividuals(prev => prev.filter(i => i.id !== individual.id));
    } else {
      setSelectedIndividuals(prev => [...prev, individual]);
    }
  };

  // Inline individual creation handlers
  const handleInlineCreate = async () => {
    if (!inlineIndividualName.trim() || !onAddIndividual) return;
    
    console.log('[IndividualSelectorDialog] Creating individual:', inlineIndividualName);
    setIsCreating(true);
    try {
      await onAddIndividual(inlineIndividualName.trim());
      setShowInlineCreate(false);
      setInlineIndividualName('');
    } catch (error) {
      console.error('[IndividualSelectorDialog] Failed to create individual:', error);
    } finally {
      setIsCreating(false);
    }
  };

  const handleInlineCreateCancel = () => {
    setShowInlineCreate(false);
    setInlineIndividualName('');
  };

  const handleDeleteClick = async (e: React.MouseEvent, individual: Individual) => {
    e.stopPropagation();
    if (!onDeleteIndividual) return;
    
    if (confirm(`Are you sure you want to delete "${individual.label}"?`)) {
      try {
        await onDeleteIndividual(individual.id);
        // Remove from selection if selected
        setSelectedIndividuals(prev => prev.filter(i => i.id !== individual.id));
      } catch (error) {
        console.error('[IndividualSelectorDialog] Failed to delete individual:', error);
      }
    }
  };

  const handleConfirm = () => {
    if (selectedIndividuals.length < minSelection) {
      alert(`Please select at least ${minSelection} individual${minSelection > 1 ? 's' : ''}.`);
      return;
    }
    onConfirm(selectedIndividuals);
    setSelectedIndividuals([]);
    onClose();
  };

  // Early return AFTER all hooks to comply with React Rules of Hooks
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={onClose}>
      <div
        className="bg-white rounded-lg shadow-xl w-full mx-4 flex flex-col"
        style={{ maxWidth: 'min(860px, calc(100vw - 32px))', maxHeight: 'min(80vh, calc(100vh - 32px))', resize: 'both', overflow: 'auto' }}
        onClick={e => e.stopPropagation()}
      >
        <div className="p-4 border-b flex justify-between items-center bg-gray-50">
          <div>
            <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
            {classLabel && (
              <p className="text-xs text-gray-500 mt-0.5">
                Instances of <span className="font-semibold text-purple-600">{classLabel}</span>
              </p>
            )}
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>
        
        <div className="p-4 flex-1 overflow-hidden flex flex-col min-h-0">
          {/* Selected individuals display */}
          <div className="mb-3">
            <div className="text-xs font-medium text-gray-500 uppercase mb-1">Selected Individuals ({selectedIndividuals.length})</div>
            {selectedIndividuals.length > 0 ? (
              <div className="flex flex-wrap gap-1 max-h-20 overflow-y-auto">
                {selectedIndividuals.map(ind => (
                  <span 
                    key={ind.id}
                    className="inline-flex items-center gap-1.5 px-2 py-1 bg-purple-100 text-purple-800 rounded text-xs cursor-pointer hover:bg-purple-200"
                    onClick={() => handleIndividualSelect(ind)}
                  >
                    <div className="w-3 h-3 rotate-45 rounded-sm bg-purple-400 border border-purple-600 flex items-center justify-center flex-shrink-0">
                      <User size={7} className="text-white -rotate-45" />
                    </div>
                    {ind.label}
                    <X size={12} />
                  </span>
                ))}
              </div>
            ) : (
              <div className="text-sm text-gray-400 italic">Click individuals below to select them</div>
            )}
          </div>

          {/* Search and toolbar - matches EntityHierarchy style */}
          <div className="flex items-center gap-1 mb-3 bg-slate-100 p-1.5 rounded border border-slate-200">
            <div className="relative flex-1">
              <Search size={14} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                placeholder="Search individuals..."
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                className="w-full pl-7 pr-2 py-1 text-sm bg-white border border-slate-300 rounded focus:outline-none focus:ring-1 focus:ring-purple-500"
              />
            </div>
            {/* Toolbar icons like EntityHierarchy */}
            <div className="flex items-center gap-0.5">
              {onAddIndividual && !showInlineCreate && (
                <button
                  onClick={() => setShowInlineCreate(true)}
                  title="Add individual"
                  aria-label="Add individual"
                  className="p-0.5 rounded text-gray-600 hover:text-purple-600"
                >
                  <PlusCircle size={14} />
                </button>
              )}
              {onDeleteIndividual && (
                <button
                  onClick={() => {
                    if (selectedIndividuals.length > 0) {
                      const ind = selectedIndividuals[0];
                      if (confirm(`Are you sure you want to delete "${ind.label}"?`)) {
                        onDeleteIndividual(ind.id);
                        setSelectedIndividuals(prev => prev.filter(i => i.id !== ind.id));
                      }
                    }
                  }}
                  title="Delete selected individual"
                  aria-label="Delete selected individual"
                  disabled={selectedIndividuals.length === 0}
                  className="p-0.5 rounded text-gray-600 hover:text-red-600 disabled:text-gray-400 disabled:opacity-80"
                >
                  <Trash2 size={14} />
                </button>
              )}
            </div>
          </div>

          {/* Inline Individual Creation Form */}
          {showInlineCreate && (
            <div className="p-3 bg-purple-50 border border-purple-200 rounded mb-3">
              {!onAddIndividual && (
                <div className="mb-2 p-2 bg-red-50 border border-red-200 rounded">
                  <p className="text-xs text-red-600">Cannot create individual: handler not available</p>
                </div>
              )}
              <p className="text-xs text-purple-800 font-medium mb-2">
                New instance of {classLabel || 'class'}:
              </p>
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={inlineIndividualName}
                  onChange={(e) => setInlineIndividualName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && inlineIndividualName.trim()) {
                      handleInlineCreate();
                    } else if (e.key === 'Escape') {
                      handleInlineCreateCancel();
                    }
                  }}
                  placeholder="Enter individual name..."
                  className="flex-1 px-2 py-1 text-sm border border-purple-300 rounded focus:outline-none focus:ring-2 focus:ring-purple-500"
                  autoFocus
                />
                <button
                  onClick={handleInlineCreate}
                  disabled={!inlineIndividualName.trim() || isCreating}
                  className="px-3 py-1 text-xs font-semibold text-white bg-purple-600 rounded hover:bg-purple-700 disabled:bg-gray-300 disabled:cursor-not-allowed"
                >
                  {isCreating ? 'Creating...' : 'Create'}
                </button>
                <button
                  onClick={handleInlineCreateCancel}
                  className="px-3 py-1 text-xs font-semibold text-purple-800 bg-white border border-purple-300 rounded hover:bg-purple-100"
                >
                  Cancel
                </button>
              </div>
              <p className="mt-1 text-xs text-purple-700">Press Enter to create, Escape to cancel</p>
            </div>
          )}

          {/* Individuals list */}
          <div className="flex-1 overflow-y-auto border border-gray-200 rounded-md">
            {filteredIndividuals.length === 0 ? (
              <div className="p-4 text-center text-gray-400 text-sm">
                {searchQuery ? 'No individuals match your search' : 'No individuals available'}
              </div>
            ) : (
              <div className="divide-y divide-gray-100">
                {filteredIndividuals.map(individual => {
                  const isSelected = selectedIndividuals.some(i => i.id === individual.id);
                  return (
                    <div
                      key={individual.id}
                      onClick={() => handleIndividualSelect(individual)}
                      className={`group flex items-center justify-between px-2 py-1.5 cursor-pointer hover:bg-slate-100 transition-colors ${
                        isSelected ? 'bg-blue-200' : ''
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        {/* Diamond-shaped icon like in EntityHierarchy */}
                        <div className={`w-4 h-4 rotate-45 rounded-sm border flex items-center justify-center flex-shrink-0 ${
                          isSelected ? 'bg-purple-600 border-purple-600' : 'bg-purple-400 border-purple-600'
                        }`}>
                          <User size={10} className="text-white -rotate-45" />
                        </div>
                        <span className="text-sm text-gray-800">{individual.label}</span>
                      </div>
                      <div className="flex items-center gap-1">
                        {isSelected && (
                          <span className="text-xs text-purple-600 font-medium mr-2">✓</span>
                        )}
                        {onDeleteIndividual && (
                          <button
                            onClick={(e) => handleDeleteClick(e, individual)}
                            className="p-1 rounded hover:bg-red-100 text-gray-400 hover:text-red-600 opacity-0 group-hover:opacity-100 transition-all"
                            title="Delete individual"
                          >
                            <Trash2 size={14} />
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        <div className="p-4 border-t bg-gray-50 flex justify-between items-center">
          <div className="text-xs text-gray-500">
            {filteredIndividuals.length} individual{filteredIndividuals.length !== 1 ? 's' : ''} available
            {minSelection > 1 
              ? ` • Select at least ${minSelection}` 
              : ''}
          </div>
          <div className="flex gap-2">
            <button onClick={onClose} className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded hover:bg-gray-50">
              Cancel
            </button>
            <button 
              onClick={handleConfirm} 
              disabled={selectedIndividuals.length < minSelection}
              className="px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Add ({selectedIndividuals.length})
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default IndividualSelectorDialog;
