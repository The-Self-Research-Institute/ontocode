import apiClient from './apiClient';

const LS_DISPLAY_MODE = 'ontocode_hierarchy_display_mode';
const LS_ANNOTATION_PROP = 'ontocode_hierarchy_annotation_prop';
const LS_CUSTOM_TEMPLATE = 'ontocode_hierarchy_custom_template';

export const userPreferencesService = {
  async getSyncMode(projectId: string): Promise<'public' | 'private' | null> {
    try {
      const response = await apiClient.get(`/api/preferences/${encodeURIComponent(projectId)}`);
      const data = response?.data ?? response;
      const mode = data?.syncMode;
      if (mode === 'public' || mode === 'private') return mode;
      return null;
    } catch {
      return null;
    }
  },

  saveSyncMode(projectId: string, mode: 'public' | 'private'): void {
    apiClient
      .put(`/api/preferences/${encodeURIComponent(projectId)}`, { syncMode: mode })
      .catch(() => { /* fire-and-forget; localStorage is the fallback */ });
  },

  getDisplayMode(): 'label' | 'id' | 'annotation' | 'custom' {
    const saved = localStorage.getItem(LS_DISPLAY_MODE);
    if (saved === 'label' || saved === 'id' || saved === 'annotation' || saved === 'custom') return saved;
    return 'label';
  },

  getAnnotationPropIri(): string {
    return localStorage.getItem(LS_ANNOTATION_PROP) ?? '';
  },

  getCustomTemplate(): string {
    return localStorage.getItem(LS_CUSTOM_TEMPLATE) ?? '{label} ({id})';
  },

  saveDisplayPreferences(
    mode: 'label' | 'id' | 'annotation' | 'custom',
    annotationPropIri: string,
    customTemplate: string,
  ): void {
    localStorage.setItem(LS_DISPLAY_MODE, mode);
    localStorage.setItem(LS_ANNOTATION_PROP, annotationPropIri);
    localStorage.setItem(LS_CUSTOM_TEMPLATE, customTemplate);
  },
};
