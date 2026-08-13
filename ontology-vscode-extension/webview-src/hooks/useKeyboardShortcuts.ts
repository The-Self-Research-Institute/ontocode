import { useEffect, useCallback } from 'react';

export interface KeyboardShortcut {
  id: string;
  name: string;
  description: string;
  defaultKey: string;
  ctrlKey?: boolean;
  shiftKey?: boolean;
  altKey?: boolean;
  metaKey?: boolean;
  handler?: () => void;
  enabled?: boolean;
  preventDefaultInVSCode?: boolean; // If true, won't work in VSCode to avoid conflicts
}

interface UseKeyboardShortcutsOptions {
  shortcuts: KeyboardShortcut[];
  enabled?: boolean;
}

export const useKeyboardShortcuts = ({ shortcuts, enabled = true }: UseKeyboardShortcutsOptions) => {
  const isVSCode = typeof window !== 'undefined' && !!(window as any).vscode;
  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPod|iPad/i.test(navigator.platform);

  const handleKeyDown = useCallback((event: KeyboardEvent) => {
    if (!enabled) return;

    const target = event.target as HTMLElement;
    if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) {
      return;
    }

    for (const shortcut of shortcuts) {

      if (shortcut.enabled === false) continue;

      if (isVSCode && shortcut.preventDefaultInVSCode) continue;

      const key = event.key.toLowerCase();
      const shortcutKey = shortcut.defaultKey.toLowerCase();

      const ctrlMatch = shortcut.ctrlKey ? event.ctrlKey : !event.ctrlKey;
      const shiftMatch = shortcut.shiftKey ? event.shiftKey : !event.shiftKey;
      const altMatch = shortcut.altKey ? event.altKey : !event.altKey;
      const metaMatch = shortcut.metaKey ? event.metaKey : !event.metaKey;

      if (key === shortcutKey && ctrlMatch && shiftMatch && altMatch && metaMatch) {
        event.preventDefault();
        event.stopPropagation();
        shortcut.handler?.();
        return;
      }
    }
  }, [shortcuts, enabled, isVSCode]);

  useEffect(() => {
    if (enabled) {
      document.addEventListener('keydown', handleKeyDown);
      return () => document.removeEventListener('keydown', handleKeyDown);
    }
  }, [handleKeyDown, enabled]);

  return {
    isVSCode,
    isMac,
    shortcuts: shortcuts.filter(s => !(isVSCode && s.preventDefaultInVSCode))
  };
};

export const getShortcutDisplay = (shortcut: KeyboardShortcut, isMac: boolean = false): string => {
  const parts: string[] = [];

  if (shortcut.metaKey) {
    parts.push(isMac ? '⌘' : 'Win');
  }
  if (shortcut.ctrlKey) {
    parts.push(isMac ? '⌃' : 'Ctrl');
  }
  if (shortcut.altKey) {
    parts.push(isMac ? '⌥' : 'Alt');
  }
  if (shortcut.shiftKey) {
    parts.push(isMac ? '⇧' : 'Shift');
  }

  parts.push(shortcut.defaultKey.toUpperCase());

  return parts.join(isMac ? '' : '+');
};

export const DEFAULT_SHORTCUTS = {

  ADD_SUBCLASS: {
    id: 'add-subclass',
    name: 'Add Subclass',
    description: 'Create a new subclass of the selected class',
    defaultKey: 'e',
    ctrlKey: true,
    preventDefaultInVSCode: false
  },
  ADD_SIBLING: {
    id: 'add-sibling',
    name: 'Add Sibling',
    description: 'Create a new sibling of the selected class',
    defaultKey: 'e',
    ctrlKey: true,
    shiftKey: true,
    preventDefaultInVSCode: false
  },
  ADD_INDIVIDUAL: {
    id: 'add-individual',
    name: 'Add Individual',
    description: 'Create a new individual',
    defaultKey: 'i',
    ctrlKey: true,
    preventDefaultInVSCode: false
  },

  RENAME_ENTITY: {
    id: 'rename-entity',
    name: 'Rename Entity',
    description: 'Rename the selected entity',
    defaultKey: 'F2',
    preventDefaultInVSCode: false
  },
  DELETE_ENTITY: {
    id: 'delete-entity',
    name: 'Delete Entity',
    description: 'Delete the selected entity',
    defaultKey: 'Delete',
    preventDefaultInVSCode: false
  },
  EDIT_IRI: {
    id: 'edit-iri',
    name: 'Edit IRI',
    description: 'Edit the full IRI of the selected entity',
    defaultKey: 'r',
    ctrlKey: true,
    shiftKey: true,
    preventDefaultInVSCode: false
  },

  COPY_ENTITY: {
    id: 'copy-entity',
    name: 'Copy Entity',
    description: 'Copy the selected entity',
    defaultKey: 'c',
    ctrlKey: true,
    preventDefaultInVSCode: true // Let VSCode handle Ctrl+C normally
  },
  PASTE_ENTITY: {
    id: 'paste-entity',
    name: 'Paste Entity',
    description: 'Paste the copied entity',
    defaultKey: 'v',
    ctrlKey: true,
    preventDefaultInVSCode: true // Let VSCode handle Ctrl+V normally
  },
  DUPLICATE_ENTITY: {
    id: 'duplicate-entity',
    name: 'Duplicate Entity',
    description: 'Duplicate the selected entity',
    defaultKey: 'd',
    ctrlKey: true,
    preventDefaultInVSCode: false
  },

  FOCUS_SEARCH: {
    id: 'focus-search',
    name: 'Focus Search',
    description: 'Focus the entity search box',
    defaultKey: 'f',
    ctrlKey: true,
    preventDefaultInVSCode: true // VSCode uses Ctrl+F for find
  },
  EXPAND_ALL: {
    id: 'expand-all',
    name: 'Expand All',
    description: 'Expand all nodes in the hierarchy',
    defaultKey: 'e',
    ctrlKey: true,
    altKey: true,
    preventDefaultInVSCode: false
  },
  COLLAPSE_ALL: {
    id: 'collapse-all',
    name: 'Collapse All',
    description: 'Collapse all nodes in the hierarchy',
    defaultKey: 'c',
    ctrlKey: true,
    altKey: true,
    preventDefaultInVSCode: false
  },

  TOGGLE_ASSERTED: {
    id: 'toggle-asserted',
    name: 'Toggle Asserted View',
    description: 'Switch to asserted hierarchy view',
    defaultKey: 'a',
    preventDefaultInVSCode: false
  },
  TOGGLE_INFERRED: {
    id: 'toggle-inferred',
    name: 'Toggle Inferred View',
    description: 'Switch to inferred hierarchy view',
    defaultKey: 'i',
    preventDefaultInVSCode: false
  },

  NEXT_TAB: {
    id: 'next-tab',
    name: 'Next Tab',
    description: 'Switch to next tab',
    defaultKey: 'Tab',
    ctrlKey: true,
    preventDefaultInVSCode: true // VSCode uses Ctrl+Tab
  },
  PREV_TAB: {
    id: 'prev-tab',
    name: 'Previous Tab',
    description: 'Switch to previous tab',
    defaultKey: 'Tab',
    ctrlKey: true,
    shiftKey: true,
    preventDefaultInVSCode: true // VSCode uses Ctrl+Shift+Tab
  },

  SAVE: {
    id: 'save',
    name: 'Save',
    description: 'Save the ontology',
    defaultKey: 's',
    ctrlKey: true,
    preventDefaultInVSCode: true // VSCode handles save
  },
  UNDO: {
    id: 'undo',
    name: 'Undo',
    description: 'Undo last action',
    defaultKey: 'z',
    ctrlKey: true,
    preventDefaultInVSCode: true // VSCode handles undo
  },
  REDO: {
    id: 'redo',
    name: 'Redo',
    description: 'Redo last undone action',
    defaultKey: 'y',
    ctrlKey: true,
    preventDefaultInVSCode: true // VSCode handles redo
  }
};
