import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';

export interface EntityPreferences {
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

interface EntityPreferencesContextType {
  preferences: EntityPreferences;
  updatePreferences: (prefs: EntityPreferences) => void;
  resetPreferences: () => void;
}

const EntityPreferencesContext = createContext<EntityPreferencesContextType | undefined>(undefined);

export const EntityPreferencesProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [preferences, setPreferences] = useState<EntityPreferences>(() => {

    const stored = localStorage.getItem('entityPreferences');
    if (stored) {
      try {
        return JSON.parse(stored);
      } catch (e) {
        console.error('Failed to parse stored preferences:', e);
      }
    }
    return DEFAULT_PREFERENCES;
  });

  useEffect(() => {

    localStorage.setItem('entityPreferences', JSON.stringify(preferences));
  }, [preferences]);

  const updatePreferences = (prefs: EntityPreferences) => {
    setPreferences(prefs);
  };

  const resetPreferences = () => {
    setPreferences(DEFAULT_PREFERENCES);
  };

  return (
    <EntityPreferencesContext.Provider value={{ preferences, updatePreferences, resetPreferences }}>
      {children}
    </EntityPreferencesContext.Provider>
  );
};

export const useEntityPreferences = () => {
  const context = useContext(EntityPreferencesContext);
  if (!context) {
    throw new Error('useEntityPreferences must be used within EntityPreferencesProvider');
  }
  return context;
};

export { DEFAULT_PREFERENCES };
