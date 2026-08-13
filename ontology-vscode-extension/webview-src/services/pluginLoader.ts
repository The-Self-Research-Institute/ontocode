import React, { useState, useEffect, useCallback } from 'react';
import apiClient from './apiClient';
import { getApiBaseUrl } from '../config/deploymentConfig';
import { isDesktop } from '../utils/desktop';

const BUILTIN_PLUGIN_IDS = [
  'swrl-editor-plugin',
  'graph-view-plugin',
  'fuzzy-ontology-plugin',
  'change-assistant-plugin',
  'sparql-query-plugin',
  'reasoner-plugin',
] as const;

const PLUGIN_LIBRARY_NAMES: Record<string, string> = {
  'fuzzy-ontology-plugin': 'FuzzyOntologyPlugin',
  'swrl-editor-plugin': 'SWRLEditorPlugin',
  'graph-view-plugin': 'GraphViewPlugin',
  'change-assistant-plugin': 'ChangeAssistantPlugin',
  'sparql-query-plugin': 'SparqlQueryPlugin',
  'reasoner-plugin': 'ReasonerPlugin',
};

function getPluginApiBaseUrl(): string {
  return getApiBaseUrl();
}

export interface PluginManifest {
  name: string;
  displayName: string;
  version: string;
  description: string;
  main: string;
  category: string;
  author: string;
  activationEvents?: string[];
  contributes?: {
    views?: any;
    commands?: any[];
  };
}

export interface InstalledPlugin {
  id: string;
  manifest: PluginManifest;
  component: React.ComponentType<any> | null;
  loaded: boolean;
  error?: string;
}

class PluginLoaderService {
  private installedPlugins: Map<string, InstalledPlugin> = new Map();
  private listeners: Set<() => void> = new Set();

  private getAuthToken(): string | null {
    return localStorage.getItem('authToken');
  }

  private defaultBuiltinManifest(pluginId: string): PluginManifest {
    return {
      name: pluginId,
      displayName: pluginId.replace(/-/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()),
      version: '1.0.0',
      description: 'Built-in plugin',
      main: './index.js',
      category: 'Built-in',
      author: 'OntoCode',
    };
  }

  ensureDefaultBuiltInPlugins(): void {
    let added = false;
    for (const pluginId of BUILTIN_PLUGIN_IDS) {
      if (!this.installedPlugins.has(pluginId)) {
        this.installedPlugins.set(pluginId, {
          id: pluginId,
          manifest: this.defaultBuiltinManifest(pluginId),
          component: null,
          loaded: false,
        });
        added = true;
      }
    }
    if (added) {
      this.saveToStorage();
      this.notifyListeners();
    }
  }

  private loadScriptFromUrl(url: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = url;
      script.type = 'text/javascript';
      script.onload = () => resolve();
      script.onerror = () => reject(new Error(`Failed to load script: ${url}`));
      document.head.appendChild(script);
    });
  }

  async installPlugin(pluginId: string, version?: string): Promise<void> {
    try {

      const isBuiltIn = (BUILTIN_PLUGIN_IDS as readonly string[]).includes(pluginId);

      let manifest: PluginManifest;

      if (isBuiltIn) {

        console.log(`[PluginLoader] Installing built-in plugin: ${pluginId}`);

        const token = this.getAuthToken();
        const headers: HeadersInit = { 'Content-Type': 'application/json' };
        if (token) {
          headers['Authorization'] = `Bearer ${token}`;
        }

        try {
          const manifestResponse = await fetch(`${getPluginApiBaseUrl()}/api/plugins/${pluginId}`, {
            method: 'GET',
            headers
          });

          if (!manifestResponse.ok) {
            console.warn(`[PluginLoader] Could not fetch metadata from backend, using default manifest`);

            manifest = {
              name: pluginId,
              displayName: pluginId.replace(/-/g, ' ').replace(/\b\w/g, l => l.toUpperCase()),
              version: '1.0.0',
              description: 'Built-in plugin',
              main: './index.js',
              category: 'Built-in',
              author: 'OntoCode'
            };
          } else {
            const data = await manifestResponse.json();
            manifest = data.manifest || data;
          }
        } catch (error) {
          console.warn(`[PluginLoader] Backend request failed, using default manifest:`, error);

          manifest = {
            name: pluginId,
            displayName: pluginId.replace(/-/g, ' ').replace(/\b\w/g, l => l.toUpperCase()),
            version: '1.0.0',
            description: 'Built-in plugin',
            main: './index.js',
            category: 'Built-in',
            author: 'OntoCode'
          };
        }
      } else {

        console.log(`[PluginLoader] Downloading external plugin: ${pluginId}`);

        const token = this.getAuthToken();
        const headers: HeadersInit = {};
        if (token) {
          headers['Authorization'] = `Bearer ${token}`;
        }

        const downloadUrl = version
          ? `${getPluginApiBaseUrl()}/api/plugins/${pluginId}/download?version=${encodeURIComponent(version)}`
          : `${getPluginApiBaseUrl()}/api/plugins/${pluginId}/download`;
        const response = await fetch(downloadUrl, {
          method: 'GET',
          headers
        });

        if (!response.ok) {
          throw new Error(`Failed to download plugin: ${response.statusText}`);
        }

        const token2 = this.getAuthToken();
        const headers2: HeadersInit = { 'Content-Type': 'application/json' };
        if (token2) {
          headers2['Authorization'] = `Bearer ${token2}`;
        }

        const manifestResponse = await fetch(`${getPluginApiBaseUrl()}/api/plugins/${pluginId}`, {
          method: 'GET',
          headers: headers2
        });

        if (!manifestResponse.ok) {
          throw new Error(`Failed to fetch manifest: ${manifestResponse.statusText}`);
        }

        const data = await manifestResponse.json();
        manifest = data.manifest || data;
      }

      if (version && manifest) {
        manifest = { ...manifest, version };
      }

      const plugin: InstalledPlugin = {
        id: pluginId,
        manifest,
        component: null,
        loaded: false
      };

      this.installedPlugins.set(pluginId, plugin);

      this.saveToStorage();

      try {
        const trackToken = this.getAuthToken();
        const trackHeaders: HeadersInit = {};
        if (trackToken) {
          trackHeaders['Authorization'] = `Bearer ${trackToken}`;
        }

        await fetch(`${getPluginApiBaseUrl()}/api/plugins/${pluginId}/install?version=${encodeURIComponent(version || manifest.version)}`, {
          method: 'POST',
          headers: trackHeaders
        });
      } catch (error) {
        console.warn('[PluginLoader] Failed to track installation on backend:', error);
      }

      this.notifyListeners();

      console.log(`[PluginLoader] ✅ Installed plugin: ${pluginId}`);
    } catch (error) {
      console.error(`[PluginLoader] ❌ Failed to install plugin ${pluginId}:`, error);
      throw error;
    }
  }

  async uninstallPlugin(pluginId: string): Promise<void> {
    if (this.installedPlugins.has(pluginId)) {
      this.installedPlugins.delete(pluginId);
      this.saveToStorage();

      try {
        const uninstallToken = this.getAuthToken();
        const uninstallHeaders: HeadersInit = {};
        if (uninstallToken) {
          uninstallHeaders['Authorization'] = `Bearer ${uninstallToken}`;
        }

        await fetch(`${getPluginApiBaseUrl()}/api/plugins/${pluginId}/uninstall`, {
          method: 'POST',
          headers: uninstallHeaders
        });
      } catch (error) {
        console.warn('[PluginLoader] Failed to track uninstallation:', error);
      }

      this.notifyListeners();
      console.log(`[PluginLoader] Uninstalled plugin: ${pluginId}`);
    }
  }

  async loadPlugin(pluginId: string): Promise<React.ComponentType<any> | null> {
    const plugin = this.installedPlugins.get(pluginId);
    if (!plugin) {
      console.error(`[PluginLoader] Plugin not found: ${pluginId}`);
      return null;
    }

    if (plugin.loaded && plugin.component) {
      return plugin.component;
    }

    try {
      const baseUrl = `${getPluginApiBaseUrl()}/api/plugins/${pluginId}/download`;

      const maxAttempts = isDesktop() ? 1 : 2;
      const retryDelayMs = 1500;
      let loaded = false;
      let lastErr: unknown;

      for (let attempt = 0; attempt < maxAttempts && !loaded; attempt++) {
        const bundleUrl = attempt > 0 ? `${baseUrl}?_t=${Date.now()}` : baseUrl;
        console.log(`[PluginLoader] 📥 Loading ${pluginId} (attempt ${attempt + 1}/${maxAttempts}) from ${bundleUrl}`);
        try {

          if (isDesktop()) {
            const token = this.getAuthToken();
            const headers: HeadersInit = {};
            if (token) headers['Authorization'] = `Bearer ${token}`;
            const probe = await fetch(bundleUrl, { method: 'HEAD', headers }).catch(() => ({ ok: false, status: 0 }));
            if (!probe.ok) {
              console.log(`[PluginLoader] ℹ️ ${pluginId} not available on desktop backend (${probe.status}) — skipping`);
              return null;
            }
          }
          await this.loadScriptFromUrl(bundleUrl);
          loaded = true;
        } catch (err) {
          lastErr = err;
          if (attempt < maxAttempts - 1) {
            await new Promise((r) => setTimeout(r, retryDelayMs));
          }
        }
      }

      if (!loaded) {
        throw lastErr instanceof Error ? lastErr : new Error('Failed to load plugin script');
      }

      const libraryName = PLUGIN_LIBRARY_NAMES[pluginId];
      if (!libraryName) {
        throw new Error(`Unknown plugin library name for ${pluginId}`);
      }

      const pluginModule = (window as any)[libraryName];

      if (!pluginModule) {
        throw new Error(`Plugin ${pluginId} library not found on window`);
      }

      const component = pluginModule.default || pluginModule;

      if (!component) {
        throw new Error(`Plugin ${pluginId} did not export a default component`);
      }

      plugin.component = component;
      plugin.loaded = true;

      console.log(`[PluginLoader] ✅ Successfully loaded plugin ${pluginId}`);

      this.notifyListeners();

      return component;
    } catch (error) {
      console.error(`[PluginLoader] Failed to load plugin ${pluginId}:`, error);
      plugin.error = error instanceof Error ? error.message : 'Unknown error';
      this.notifyListeners();
      return null;
    }
  }

  getInstalledPlugins(): InstalledPlugin[] {
    return Array.from(this.installedPlugins.values());
  }

  isPluginInstalled(pluginId: string): boolean {
    return this.installedPlugins.has(pluginId);
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private notifyListeners(): void {
    this.listeners.forEach(listener => listener());
  }

  private saveToStorage(): void {
    try {
      const pluginData = Array.from(this.installedPlugins.entries()).map(([id, plugin]) => ({
        id,
        manifest: plugin.manifest
      }));
      localStorage.setItem('ontocode_installed_plugins', JSON.stringify(pluginData));
    } catch (error) {
      console.error('[PluginLoader] Failed to save plugins to storage:', error);
    }
  }

  loadFromStorage(): void {
    try {
      const stored = localStorage.getItem('ontocode_installed_plugins');
      if (stored) {
        const pluginData = JSON.parse(stored);
        pluginData.forEach((data: any) => {
          this.installedPlugins.set(data.id, {
            id: data.id,
            manifest: data.manifest,
            component: null,
            loaded: false
          });
        });
        this.notifyListeners();
      }

      if (isDesktop()) {
        this.ensureDefaultBuiltInPlugins();
      }
    } catch (error) {
      console.error('[PluginLoader] Failed to load plugins from storage:', error);
      if (isDesktop()) {
        this.ensureDefaultBuiltInPlugins();
      }
    }
  }

  async ratePlugin(
    pluginId: string,
    stars: number,
    review?: string,
    merits?: string,
    demerits?: string,
    recommended?: boolean
  ): Promise<void> {
    try {
      const token = this.getAuthToken();
      if (!token) {
        throw new Error('Please log in to rate plugins');
      }

      await apiClient.post(`/api/plugins/${pluginId}/rate`, {
        stars,
        review,
        merits,
        demerits,
        recommended
      });

      console.log(`[PluginLoader] Successfully rated plugin ${pluginId} with ${stars} stars`);
    } catch (error: any) {
      console.error('[PluginLoader] Failed to rate plugin:', error);

      if (error?.status === 401 || error?.message?.includes('Unauthorized')) {
        throw new Error('Please log in to rate plugins');
      }
      throw error;
    }
  }

  async getUserRating(pluginId: string): Promise<any> {
    try {
      const token = this.getAuthToken();
      if (!token) {

        return null;
      }

      const response = await apiClient.get(`/api/plugins/${pluginId}/my-rating`);
      return response;
    } catch (error: any) {

      if (error?.status === 401 || error?.status === 404 || error?.status === 204) {
        return null;
      }
      console.error('[PluginLoader] Failed to get user rating:', error);
      return null;
    }
  }

  async getPluginRatings(pluginId: string): Promise<any[]> {
    try {
      const token = this.getAuthToken();
      const headers: HeadersInit = {};
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }

      const response = await fetch(`${getPluginApiBaseUrl()}/api/plugins/${pluginId}/ratings`, {
        method: 'GET',
        headers
      });

      if (!response.ok) {
        throw new Error(`Failed to get ratings: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[PluginLoader] Failed to get plugin ratings:', error);
      return [];
    }
  }

  async getPluginStats(pluginId: string): Promise<any> {
    try {
      const token = this.getAuthToken();
      const headers: HeadersInit = {};
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }

      const response = await fetch(`${getPluginApiBaseUrl()}/api/plugins/${pluginId}/stats`, {
        method: 'GET',
        headers
      });

      if (!response.ok) {
        throw new Error(`Failed to get stats: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[PluginLoader] Failed to get plugin stats:', error);
      return null;
    }
  }
}

export const pluginLoader = new PluginLoaderService();

export function usePluginLoader() {
  const [installedPlugins, setInstalledPlugins] = useState<InstalledPlugin[]>([]);

  useEffect(() => {

    pluginLoader.loadFromStorage();
    setInstalledPlugins(pluginLoader.getInstalledPlugins());

    const unsubscribe = pluginLoader.subscribe(() => {
      setInstalledPlugins(pluginLoader.getInstalledPlugins());
    });

    return unsubscribe;
  }, []);

  const installPlugin = useCallback(async (pluginId: string) => {
    await pluginLoader.installPlugin(pluginId);
  }, []);

  const uninstallPlugin = useCallback(async (pluginId: string) => {
    await pluginLoader.uninstallPlugin(pluginId);
  }, []);

  const loadPlugin = useCallback(async (pluginId: string) => {
    return await pluginLoader.loadPlugin(pluginId);
  }, []);

  const isInstalled = useCallback((pluginId: string) => {
    return pluginLoader.isPluginInstalled(pluginId);
  }, []);

  return {
    installedPlugins,
    installPlugin,
    uninstallPlugin,
    loadPlugin,
    isInstalled
  };
}
