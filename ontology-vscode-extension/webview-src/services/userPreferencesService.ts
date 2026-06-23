import apiClient from './apiClient';

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
};
