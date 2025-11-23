import React, { useState, useEffect, useCallback } from 'react';

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

/**
 * Plugin Loader Service
 * Handles dynamic loading, installation, and lifecycle management of plugins
 * Ensures complete independence - plugins can be added/removed without affecting core or other plugins
 */
class PluginLoaderService {
  private installedPlugins: Map<string, InstalledPlugin> = new Map();
  private listeners: Set<() => void> = new Set();

  /**
   * Get auth token from localStorage
   * Returns null if no token is available
   */
  private getAuthToken(): string | null {
    return localStorage.getItem('authToken');
  }

  /**
   * Install plugin from backend service
   */
  async installPlugin(pluginId: string): Promise<void> {
    try {
      // Check if this is a built-in plugin (already registered in pluginManager)
      // Built-in plugins don't have .vsix files - they're compiled into the extension
      const builtInPlugins = ['swrl-editor-plugin', 'graph-view-plugin', 'fuzzy-ontology-plugin'];
      const isBuiltIn = builtInPlugins.includes(pluginId);

      let manifest: PluginManifest;

      if (isBuiltIn) {
        // For built-in plugins, just fetch metadata (no .vsix download needed)
        console.log(`[PluginLoader] Installing built-in plugin: ${pluginId}`);
        
        const token = this.getAuthToken();
        const headers: HeadersInit = { 'Content-Type': 'application/json' };
        if (token) {
          headers['Authorization'] = `Bearer ${token}`;
        }
        
        try {
          const manifestResponse = await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}`, {
            method: 'GET',
            headers
          });

          if (!manifestResponse.ok) {
            console.warn(`[PluginLoader] Could not fetch metadata from backend, using default manifest`);
            // Use default manifest if backend fetch fails
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
          // Fallback manifest if fetch fails
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
        // For external plugins, download the .vsix package
        console.log(`[PluginLoader] Downloading external plugin: ${pluginId}`);
        
        const token = this.getAuthToken();
        const headers: HeadersInit = {};
        if (token) {
          headers['Authorization'] = `Bearer ${token}`;
        }
        
        const response = await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}/download`, {
          method: 'GET',
          headers
        });

        if (!response.ok) {
          throw new Error(`Failed to download plugin: ${response.statusText}`);
        }

        // Extract manifest from plugin package
        const token2 = this.getAuthToken();
        const headers2: HeadersInit = { 'Content-Type': 'application/json' };
        if (token2) {
          headers2['Authorization'] = `Bearer ${token2}`;
        }
        
        const manifestResponse = await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}`, {
          method: 'GET',
          headers: headers2
        });

        if (!manifestResponse.ok) {
          throw new Error(`Failed to fetch manifest: ${manifestResponse.statusText}`);
        }

        const data = await manifestResponse.json();
        manifest = data.manifest || data;
      }

      // Store plugin metadata
      const plugin: InstalledPlugin = {
        id: pluginId,
        manifest,
        component: null,
        loaded: false
      };

      this.installedPlugins.set(pluginId, plugin);
      
      // Persist to localStorage for persistence across sessions
      this.saveToStorage();
      
      // Track installation on backend (best effort - don't fail if this fails)
      try {
        const trackToken = this.getAuthToken();
        const trackHeaders: HeadersInit = {};
        if (trackToken) {
          trackHeaders['Authorization'] = `Bearer ${trackToken}`;
        }
        
        await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}/install?version=${manifest.version}`, {
          method: 'POST',
          headers: trackHeaders
        });
      } catch (error) {
        console.warn('[PluginLoader] Failed to track installation on backend:', error);
      }
      
      // Notify listeners
      this.notifyListeners();

      console.log(`[PluginLoader] ✅ Installed plugin: ${pluginId}`);
    } catch (error) {
      console.error(`[PluginLoader] ❌ Failed to install plugin ${pluginId}:`, error);
      throw error;
    }
  }

  /**
   * Uninstall plugin
   */
  async uninstallPlugin(pluginId: string): Promise<void> {
    if (this.installedPlugins.has(pluginId)) {
      this.installedPlugins.delete(pluginId);
      this.saveToStorage();
      
      // Track uninstallation on backend
      try {
        const uninstallToken = this.getAuthToken();
        const uninstallHeaders: HeadersInit = {};
        if (uninstallToken) {
          uninstallHeaders['Authorization'] = `Bearer ${uninstallToken}`;
        }
        
        await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}/uninstall`, {
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

  /**
   * Load plugin component dynamically
   * This would typically use dynamic import() in production
   */
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
      // Map known plugins to their already-registered components in pluginManager
      // These plugins are already integrated via PluginSystem
      const { pluginManager } = await import('../plugins/PluginSystem');
      
      let component: React.ComponentType<any> | null = null;

      // Map marketplace plugin IDs to internal plugin IDs
      const pluginMapping: Record<string, string> = {
        'swrl-editor-plugin': 'swrl-tab',
        'graph-view-plugin': 'reasoning-graph',
        'fuzzy-ontology-plugin': 'fuzzy-ontology-plugin'
      };

      const internalPluginId = pluginMapping[pluginId];
      if (internalPluginId) {
        const registeredPlugin = pluginManager.getPlugin(internalPluginId);
        if (registeredPlugin) {
          // Activate the plugin if not already active
          if (!pluginManager.isPluginActive(internalPluginId)) {
            await pluginManager.activatePlugin(internalPluginId);
          }
          component = registeredPlugin.component;
          console.log(`[PluginLoader] ✅ Loaded plugin ${pluginId} -> ${internalPluginId}`);
        } else {
          console.warn(`[PluginLoader] ⚠️ Plugin ${internalPluginId} not registered in pluginManager yet - will be available after implementation`);
          // Plugin is marked as installed but component not available yet
          // This is OK for fuzzy-ontology-plugin which isn't implemented yet
        }
      } else {
        console.warn(`[PluginLoader] ⚠️ Unknown plugin: ${pluginId}`);
      }

      plugin.component = component;
      plugin.loaded = true;
      
      this.notifyListeners();
      
      return component;
    } catch (error) {
      console.error(`[PluginLoader] Failed to load plugin ${pluginId}:`, error);
      plugin.error = error instanceof Error ? error.message : 'Unknown error';
      this.notifyListeners();
      return null;
    }
  }

  /**
   * Get all installed plugins
   */
  getInstalledPlugins(): InstalledPlugin[] {
    return Array.from(this.installedPlugins.values());
  }

  /**
   * Check if plugin is installed
   */
  isPluginInstalled(pluginId: string): boolean {
    return this.installedPlugins.has(pluginId);
  }

  /**
   * Subscribe to plugin changes
   */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Notify all listeners of changes
   */
  private notifyListeners(): void {
    this.listeners.forEach(listener => listener());
  }

  /**
   * Save installed plugins to localStorage
   */
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

  /**
   * Load installed plugins from localStorage
   */
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
    } catch (error) {
      console.error('[PluginLoader] Failed to load plugins from storage:', error);
    }
  }

  /**
   * Rate a plugin
   */
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
      const headers: HeadersInit = {
        'Content-Type': 'application/json'
      };
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }
      
      const response = await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}/rate`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          stars,
          review,
          merits,
          demerits,
          recommended
        })
      });

      if (!response.ok) {
        throw new Error(`Failed to rate plugin: ${response.statusText}`);
      }

      console.log(`[PluginLoader] Successfully rated plugin ${pluginId} with ${stars} stars`);
    } catch (error) {
      console.error('[PluginLoader] Failed to rate plugin:', error);
      throw error;
    }
  }

  /**
   * Get user's rating for a plugin
   */
  async getUserRating(pluginId: string): Promise<any> {
    try {
      const token = this.getAuthToken();
      const headers: HeadersInit = {};
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }
      
      const response = await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}/my-rating`, {
        method: 'GET',
        headers
      });

      if (response.status === 204) {
        return null; // No rating yet
      }

      if (!response.ok) {
        throw new Error(`Failed to get rating: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('[PluginLoader] Failed to get user rating:', error);
      return null;
    }
  }

  /**
   * Get all ratings for a plugin
   */
  async getPluginRatings(pluginId: string): Promise<any[]> {
    try {
      const token = this.getAuthToken();
      const headers: HeadersInit = {};
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }
      
      const response = await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}/ratings`, {
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

  /**
   * Get plugin statistics (installs, ratings, etc.)
   */
  async getPluginStats(pluginId: string): Promise<any> {
    try {
      const token = this.getAuthToken();
      const headers: HeadersInit = {};
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }
      
      const response = await fetch(`${window.API_BASE_URL}/api/plugins/${pluginId}/stats`, {
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

// Singleton instance
export const pluginLoader = new PluginLoaderService();

/**
 * React Hook for using plugins
 */
export function usePluginLoader() {
  const [installedPlugins, setInstalledPlugins] = useState<InstalledPlugin[]>([]);

  useEffect(() => {
    // Load from storage on mount
    pluginLoader.loadFromStorage();
    setInstalledPlugins(pluginLoader.getInstalledPlugins());

    // Subscribe to changes
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
