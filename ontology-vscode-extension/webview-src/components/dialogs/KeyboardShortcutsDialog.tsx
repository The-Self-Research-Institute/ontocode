import React, { useState } from 'react';
import { Search, X, Keyboard } from 'lucide-react';
import { DEFAULT_SHORTCUTS, getShortcutDisplay } from '../../hooks/useKeyboardShortcuts';

interface KeyboardShortcutsDialogProps {
  isOpen: boolean;
  onClose: () => void;
}

const KeyboardShortcutsDialog: React.FC<KeyboardShortcutsDialogProps> = ({
  isOpen,
  onClose
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPod|iPad/i.test(navigator.platform);
  const isVSCode = typeof window !== 'undefined' && !!(window as any).vscode;

  if (!isOpen) return null;

  const shortcuts = Object.values(DEFAULT_SHORTCUTS);
  const filteredShortcuts = shortcuts.filter(s =>
    s.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    s.description.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const categories = {
    'Entity Creation': ['add-subclass', 'add-sibling', 'add-individual'],
    'Entity Editing': ['rename-entity', 'delete-entity', 'edit-iri'],
    'Copy/Paste': ['copy-entity', 'paste-entity', 'duplicate-entity'],
    'Navigation': ['focus-search', 'expand-all', 'collapse-all'],
    'View Modes': ['toggle-asserted', 'toggle-inferred'],
    'Tabs': ['next-tab', 'prev-tab'],
    'Save/Undo': ['save', 'undo', 'redo']
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && e.button === 0) onClose();
      }}
    >
      <div className="bg-white rounded-lg shadow-xl max-w-3xl w-full mx-4 flex flex-col max-h-[85vh]" onClick={e => e.stopPropagation()}>
        {}
        <div className="p-4 border-b border-gray-200 flex items-center justify-between flex-shrink-0">
          <div className="flex items-center gap-2">
            <Keyboard size={20} className="text-purple-600" />
            <h3 className="text-lg font-semibold text-black">Keyboard Shortcuts</h3>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded hover:bg-gray-100 text-gray-500"
            title="Close"
          >
            <X size={20} />
          </button>
        </div>

        {}
        {isVSCode && (
          <div className="p-3 bg-blue-50 border-b border-blue-200 text-xs text-blue-800 flex-shrink-0">
            <p className="font-semibold mb-1">🔵 Running in VSCode Extension</p>
            <p>Some shortcuts are disabled to avoid conflicts with VSCode. Use VSCode's keybinding settings for those actions.</p>
          </div>
        )}

        {}
        <div className="p-3 border-b border-gray-200 flex-shrink-0">
          <div className="relative">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              placeholder="Search shortcuts..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-9 pr-3 py-2 text-sm border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500"
              autoFocus
            />
          </div>
        </div>

        {}
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {Object.entries(categories).map(([category, ids]) => {
            const categoryShortcuts = filteredShortcuts.filter(s => ids.includes(s.id));
            if (categoryShortcuts.length === 0) return null;

            return (
              <div key={category} className="space-y-2">
                <h4 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">
                  {category}
                </h4>
                <div className="space-y-1">
                  {categoryShortcuts.map(shortcut => {
                    const isDisabledInVSCode = isVSCode && shortcut.preventDefaultInVSCode;
                    const displayKey = getShortcutDisplay(shortcut, isMac);

                    return (
                      <div
                        key={shortcut.id}
                        className={`flex items-center justify-between p-2 rounded border ${
                          isDisabledInVSCode
                            ? 'bg-gray-50 border-gray-200 opacity-60'
                            : 'bg-white border-gray-200 hover:bg-gray-50'
                        }`}
                      >
                        <div className="flex-1">
                          <div className="flex items-center gap-2">
                            <span className="text-sm font-medium text-gray-800">
                              {shortcut.name}
                            </span>
                            {isDisabledInVSCode && (
                              <span className="text-[10px] px-1.5 py-0.5 bg-gray-200 text-gray-600 rounded font-semibold">
                                DISABLED IN VSCODE
                              </span>
                            )}
                          </div>
                          <p className="text-xs text-gray-600 mt-0.5">{shortcut.description}</p>
                        </div>
                        <kbd className={`px-2.5 py-1.5 text-xs font-mono rounded border ${
                          isDisabledInVSCode
                            ? 'bg-gray-100 border-gray-300 text-gray-500'
                            : 'bg-gray-800 border-gray-700 text-white'
                        }`}>
                          {displayKey}
                        </kbd>
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>

        {}
        <div className="p-4 border-t border-gray-200 flex justify-between items-center text-xs text-gray-600 flex-shrink-0">
          <div>
            {isMac ? (
              <span>⌘ Command · ⌃ Control · ⌥ Option · ⇧ Shift</span>
            ) : (
              <span>Press the key combination to trigger the action</span>
            )}
          </div>
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm bg-purple-600 text-white rounded-md hover:bg-purple-700"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default KeyboardShortcutsDialog;
